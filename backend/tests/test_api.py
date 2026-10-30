"""Тесты backend без обращения к реальному API: клиент Anthropic подменяется фейком."""
import json
import os
import sys
from types import SimpleNamespace
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
os.environ.setdefault("ANTHROPIC_API_KEY", "test-key")
os.environ["APP_TOKEN"] = "secret-token"

from fastapi.testclient import TestClient  # noqa: E402

import main  # noqa: E402
from claude_service import ClaudeService  # noqa: E402
from config import get_settings  # noqa: E402

AUTH = {"Authorization": "Bearer secret-token"}


def text_block(t):
    return SimpleNamespace(type="text", text=t)


def tool_block(name, inp, id_="toolu_1"):
    return SimpleNamespace(type="tool_use", id=id_, name=name, input=inp)


class FakeStream:
    def __init__(self, message):
        self._message = message

    async def __aenter__(self):
        return self

    async def __aexit__(self, *a):
        return False

    @property
    def text_stream(self):
        async def gen():
            for b in self._message.content:
                if b.type == "text":
                    for part in b.text.split(" "):
                        yield part + " "
        return gen()

    async def get_final_message(self):
        return self._message


class FakeMessages:
    def __init__(self, responses):
        self.responses = list(responses)
        self.calls = []

    async def create(self, **kwargs):
        self.calls.append(json.loads(json.dumps(kwargs, default=str)))
        return SimpleNamespace(content=self.responses.pop(0))

    def stream(self, **kwargs):
        self.calls.append(json.loads(json.dumps(kwargs, default=str)))
        return FakeStream(SimpleNamespace(content=self.responses.pop(0)))


@pytest.fixture
def make_client():
    def _make(responses):
        fake = SimpleNamespace(messages=FakeMessages(responses))
        client = TestClient(main.app)
        client.__enter__()
        main.app.state.service = ClaudeService(get_settings(), client=fake)
        return client, fake.messages
    return _make


def body(text="Добавь задачу купить хлеб завтра в шесть вечера", cid="c1"):
    return {"text": text, "locale": "ru-RU", "device_timezone": "Europe/Moscow",
            "current_time": "2026-10-30T12:00:00+03:00", "conversation_id": cid}


def test_health(make_client):
    client, _ = make_client([])
    r = client.get("/health")
    assert r.status_code == 200 and r.json()["status"] == "ok"


def test_requires_token(make_client):
    client, _ = make_client([])
    assert client.post("/assistant", json=body()).status_code == 401


def test_plain_reply(make_client):
    client, fake = make_client([[text_block("Привет! Чем помочь?")]])
    r = client.post("/assistant", json=body("Привет"), headers=AUTH)
    assert r.status_code == 200
    assert r.json() == {"type": "reply", "conversation_id": "c1", "text": "Привет! Чем помочь?"}
    sent = fake.calls[0]
    assert sent["tool_choice"]["disable_parallel_tool_use"] is True
    assert "2026-10-30T12:00:00+03:00" in sent["system"][1]["text"]


def test_tool_call_then_result(make_client):
    client, fake = make_client([
        [tool_block("add_task", {"title": "Купить хлеб", "due_at": "2026-10-31T18:00:00+03:00"})],
        [text_block("Добавил задачу на завтра, на шесть вечера.")],
    ])
    r = client.post("/assistant", json=body(), headers=AUTH).json()
    assert r["type"] == "tool_call" and r["tool"] == "add_task"
    assert r["arguments"]["due_at"] == "2026-10-31T18:00:00+03:00"
    assert r["tool_call_id"] == "toolu_1"

    r2 = client.post("/assistant/result", headers=AUTH, json={
        "conversation_id": "c1", "tool": "add_task", "tool_call_id": "toolu_1",
        "status": "success", "result": {"task_id": 12}})
    assert r2.status_code == 200
    assert r2.json()["type"] == "reply"
    last = fake.calls[1]["messages"][-1]["content"][0]
    assert last["type"] == "tool_result" and last["tool_use_id"] == "toolu_1"
    assert json.loads(last["content"]) == {"task_id": 12}


def test_result_for_wrong_tool_rejected(make_client):
    client, _ = make_client([[tool_block("add_task", {"title": "x"})]])
    client.post("/assistant", json=body(), headers=AUTH)
    r = client.post("/assistant/result", headers=AUTH, json={
        "conversation_id": "c1", "tool": "add_note", "status": "success", "result": {}})
    assert r.status_code == 409


def test_unknown_conversation(make_client):
    client, _ = make_client([])
    r = client.post("/assistant/result", headers=AUTH, json={
        "conversation_id": "nope", "tool": "add_task", "status": "success", "result": {}})
    assert r.status_code == 404


def test_abandoned_tool_call_is_closed(make_client):
    client, fake = make_client([
        [tool_block("add_task", {"title": "x"})],
        [text_block("Хорошо.")],
    ])
    client.post("/assistant", json=body(), headers=AUTH)
    client.post("/assistant", json=body("Ладно, не надо"), headers=AUTH)
    first_block = fake.calls[1]["messages"][-1]["content"][0]
    assert first_block["type"] == "tool_result" and first_block["is_error"] is True


def test_stream(make_client):
    client, _ = make_client([[text_block("Сегодня у вас две задачи.")]])
    with client.stream("POST", "/assistant/stream", json=body("Что у меня сегодня?"), headers=AUTH) as r:
        raw = "".join(r.iter_text())
    events = [blk for blk in raw.strip().split("\n\n") if blk]
    assert events[0].startswith("event: delta")
    done = events[-1]
    assert done.startswith("event: done")
    payload = json.loads(done.split("data: ", 1)[1])
    assert payload["type"] == "reply" and "две задачи" in payload["text"]


def test_history_trim_keeps_valid_start():
    from claude_service import Conversation, ConversationStore
    store = ConversationStore(ttl_seconds=100, max_messages=3)
    conv = Conversation(messages=[
        {"role": "user", "content": [{"type": "text", "text": "a"}]},
        {"role": "assistant", "content": [{"type": "tool_use", "id": "t", "name": "add_task", "input": {}}]},
        {"role": "user", "content": [{"type": "tool_result", "tool_use_id": "t", "content": "{}"}]},
        {"role": "assistant", "content": [{"type": "text", "text": "ok"}]},
        {"role": "user", "content": [{"type": "text", "text": "b"}]},
    ])
    store.trim(conv)
    assert conv.messages[0]["content"][0]["text"] == "b"
