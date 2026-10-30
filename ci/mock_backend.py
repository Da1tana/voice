"""Настоящий backend (main.py) с подменённым клиентом Anthropic — для сквозного теста на эмуляторе.

Модель имитируется правилами по ключевым словам. Всё остальное — HTTP, авторизация,
контекст диалога, tool_call → tool_result — работает как в продакшене.

Запуск: python ci/mock_backend.py  (порт 8000)
"""
import os
import sys
from datetime import datetime, timedelta
from pathlib import Path
from types import SimpleNamespace

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "backend"))
os.environ.setdefault("ANTHROPIC_API_KEY", "ci-fake-key")
os.environ.setdefault("APP_TOKEN", "ci-token")

import uvicorn  # noqa: E402

import main  # noqa: E402
from claude_service import ClaudeService  # noqa: E402
from config import get_settings  # noqa: E402


def text(t):
    return SimpleNamespace(type="text", text=t)


def tool(name, args):
    return SimpleNamespace(type="tool_use", id=f"toolu_{name}_{datetime.now().timestamp()}", name=name, input=args)


def now_from(kwargs) -> datetime:
    ctx = kwargs["system"][1]["text"]  # «Текущее время пользователя: 2026-...+03:00. ...»
    iso = ctx.split("Текущее время пользователя: ", 1)[1].split(". ", 1)[0]
    return datetime.fromisoformat(iso)


class ScriptedMessages:
    async def create(self, **kwargs):
        msgs = kwargs["messages"]
        last = msgs[-1]["content"]
        # После выполнения действия — финальная фраза.
        results = [b for b in last if isinstance(b, dict) and b.get("type") == "tool_result"]
        if results:
            r = results[-1]
            if r.get("is_error"):
                return SimpleNamespace(content=[text("Не получилось выполнить действие.")])
            return SimpleNamespace(content=[text("Готово, выполнено.")])

        user_text = " ".join(b.get("text", "") for b in last if isinstance(b, dict)).lower()
        now = now_from(kwargs)
        if "task" in user_text:
            due = now.replace(hour=23, minute=30, second=0, microsecond=0)
            return SimpleNamespace(content=[tool("add_task", {"title": "Купить хлеб", "due_at": due.isoformat()})])
        if "expense" in user_text:
            return SimpleNamespace(content=[tool("add_expense", {"amount": 350, "category": "кафе", "comment": "кофе"})])
        if "remind" in user_text:
            when = (now + timedelta(seconds=75)).replace(microsecond=0)
            return SimpleNamespace(content=[tool("set_reminder", {"text": "Проверка напоминания", "when": when.isoformat()})])
        if "note" in user_text:
            return SimpleNamespace(content=[tool("add_note", {"text": "Код домофона 1234", "tags": ["дом"]})])
        if "summary" in user_text:
            return SimpleNamespace(content=[tool("get_today_summary", {})])
        return SimpleNamespace(content=[text("Привет! Чем помочь?")])


class Patched(ClaudeService):
    def __init__(self, settings):
        super().__init__(settings, client=SimpleNamespace(messages=ScriptedMessages()))


main.ClaudeService = Patched  # lifespan создаст сервис уже с фейковым клиентом

if __name__ == "__main__":
    get_settings.cache_clear()
    uvicorn.run(main.app, host="0.0.0.0", port=8000, log_level="info")
