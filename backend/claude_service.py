"""Работа с Anthropic Messages API и хранение контекста диалогов."""
from __future__ import annotations

import asyncio
import json
import logging
import time
from dataclasses import dataclass, field
from typing import Any, AsyncIterator, Optional

import anthropic

from config import Settings
from models import AssistantRequest, AssistantResponse, ToolResultRequest
from tools import REPLY_HINTS, SYSTEM_PROMPT, TOOL_NAMES, TOOLS

log = logging.getLogger("assistant.claude")

FALLBACK_TEXT = "Не совсем расслышал. Повторите, пожалуйста."


class UnknownConversationError(Exception):
    """Диалог не найден или истёк."""


class ToolMismatchError(Exception):
    """Результат пришёл для инструмента, который не ожидался."""


@dataclass
class Conversation:
    messages: list[dict[str, Any]] = field(default_factory=list)
    pending_tool_id: Optional[str] = None
    pending_tool_name: Optional[str] = None
    locale: str = "ru-RU"
    timezone: str = "Europe/Moscow"
    current_time: str = ""
    updated_at: float = field(default_factory=time.monotonic)
    lock: asyncio.Lock = field(default_factory=asyncio.Lock)


class ConversationStore:
    """Хранилище диалогов в памяти процесса с TTL.

    Для одного пользователя и одного инстанса backend этого достаточно.
    Для нескольких реплик замените на Redis.
    """

    def __init__(self, ttl_seconds: int, max_messages: int) -> None:
        self._ttl = ttl_seconds
        self._max_messages = max_messages
        self._items: dict[str, Conversation] = {}

    def _evict(self) -> None:
        now = time.monotonic()
        expired = [k for k, v in self._items.items() if now - v.updated_at > self._ttl]
        for k in expired:
            del self._items[k]

    def get_or_create(self, conversation_id: str) -> Conversation:
        self._evict()
        conv = self._items.get(conversation_id)
        if conv is None:
            conv = Conversation()
            self._items[conversation_id] = conv
        conv.updated_at = time.monotonic()
        return conv

    def get(self, conversation_id: str) -> Conversation:
        self._evict()
        conv = self._items.get(conversation_id)
        if conv is None:
            raise UnknownConversationError(conversation_id)
        conv.updated_at = time.monotonic()
        return conv

    def trim(self, conv: Conversation) -> None:
        """Обрезает историю, сохраняя корректную последовательность сообщений.

        История должна начинаться с сообщения пользователя с обычным текстом,
        а не с tool_result — иначе API вернёт ошибку.
        """
        msgs = conv.messages
        if len(msgs) <= self._max_messages:
            return
        start = len(msgs) - self._max_messages
        while start < len(msgs) and not _is_plain_user_message(msgs[start]):
            start += 1
        conv.messages = msgs[start:]

    def __len__(self) -> int:
        return len(self._items)


def _is_plain_user_message(msg: dict[str, Any]) -> bool:
    if msg.get("role") != "user":
        return False
    content = msg.get("content")
    if isinstance(content, str):
        return True
    return not any(isinstance(b, dict) and b.get("type") == "tool_result" for b in content)


def _context_block(conv: Conversation) -> str:
    return (
        f"Текущее время пользователя: {conv.current_time}. "
        f"Часовой пояс: {conv.timezone}. Язык: {conv.locale}. "
        "Все даты в аргументах инструментов указывай в этом часовом поясе в формате ISO-8601 со смещением."
    )


def _assistant_content_to_dicts(content: list[Any]) -> list[dict[str, Any]]:
    """Преобразует блоки ответа SDK в dict для повторной отправки в истории."""
    out: list[dict[str, Any]] = []
    for block in content:
        if block.type == "text":
            if block.text:
                out.append({"type": "text", "text": block.text})
        elif block.type == "tool_use":
            out.append({"type": "tool_use", "id": block.id, "name": block.name, "input": block.input})
        # thinking и прочие блоки нам не нужны: extended thinking не включён
    return out


class ClaudeService:
    def __init__(self, settings: Settings, client: Optional[anthropic.AsyncAnthropic] = None) -> None:
        self._s = settings
        self._client = client or anthropic.AsyncAnthropic(
            api_key=settings.anthropic_api_key.get_secret_value(),
            timeout=settings.claude_timeout_seconds,
            max_retries=1,
        )
        self.store = ConversationStore(settings.conversation_ttl_seconds, settings.conversation_max_messages)

    # ---------- подготовка истории ----------

    def _prepare_user_turn(self, conv: Conversation, req: AssistantRequest) -> None:
        conv.locale, conv.timezone, conv.current_time = req.locale, req.device_timezone, req.current_time
        text = req.text.strip()[: self._s.max_text_length]
        content: list[dict[str, Any]] = []
        # Если предыдущий tool_call так и не получил результата (сеть, отмена),
        # закрываем его, иначе API отклонит историю.
        if conv.pending_tool_id:
            content.append(
                {
                    "type": "tool_result",
                    "tool_use_id": conv.pending_tool_id,
                    "content": "Действие не было выполнено: пользователь задал новый вопрос.",
                    "is_error": True,
                }
            )
            conv.pending_tool_id = conv.pending_tool_name = None
        content.append({"type": "text", "text": text})
        conv.messages.append({"role": "user", "content": content})
        self.store.trim(conv)

    def _prepare_tool_result(self, conv: Conversation, req: ToolResultRequest) -> None:
        if not conv.pending_tool_id:
            raise ToolMismatchError("Нет ожидающего вызова инструмента")
        if req.tool != conv.pending_tool_name or (req.tool_call_id and req.tool_call_id != conv.pending_tool_id):
            raise ToolMismatchError("Результат не соответствует ожидаемому инструменту")
        if req.status == "success":
            payload = json.dumps(req.result, ensure_ascii=False)
        else:
            payload = json.dumps({"error": req.error or "Не удалось выполнить действие", **req.result}, ensure_ascii=False)
        conv.messages.append(
            {
                "role": "user",
                "content": [
                    {
                        "type": "tool_result",
                        "tool_use_id": conv.pending_tool_id,
                        "content": payload,
                        "is_error": req.status != "success",
                    }
                ],
            }
        )
        conv.pending_tool_id = conv.pending_tool_name = None

    def _request_kwargs(self, conv: Conversation) -> dict[str, Any]:
        return {
            "model": self._s.claude_model,
            "max_tokens": self._s.claude_max_tokens,
            "system": [
                {"type": "text", "text": SYSTEM_PROMPT, "cache_control": {"type": "ephemeral"}},
                {"type": "text", "text": _context_block(conv)},
            ],
            "tools": TOOLS,
            # Приложение выполняет действия по одному, поэтому параллельные вызовы выключены.
            "tool_choice": {"type": "auto", "disable_parallel_tool_use": True},
            "messages": conv.messages,
        }

    def _to_response(self, conversation_id: str, conv: Conversation, message: Any) -> AssistantResponse:
        content = _assistant_content_to_dicts(message.content)
        tool_use = next((b for b in content if b["type"] == "tool_use"), None)
        text = " ".join(b["text"] for b in content if b["type"] == "text").strip()

        if tool_use and tool_use["name"] in TOOL_NAMES:
            conv.messages.append({"role": "assistant", "content": content})
            conv.pending_tool_id = tool_use["id"]
            conv.pending_tool_name = tool_use["name"]
            log.info("conv=%s tool_call=%s", conversation_id[:8], tool_use["name"])
            return AssistantResponse(
                type="tool_call",
                conversation_id=conversation_id,
                tool=tool_use["name"],
                tool_call_id=tool_use["id"],
                arguments=tool_use["input"] or {},
                reply_after_execution=REPLY_HINTS.get(tool_use["name"]),
                text=text or None,
            )

        if tool_use:  # неизвестный инструмент — не должен случаться
            log.warning("conv=%s unknown tool requested", conversation_id[:8])
            content = [b for b in content if b["type"] == "text"]

        final_text = text or FALLBACK_TEXT
        conv.messages.append({"role": "assistant", "content": content or [{"type": "text", "text": final_text}]})
        log.info("conv=%s reply len=%d", conversation_id[:8], len(final_text))
        return AssistantResponse(type="reply", conversation_id=conversation_id, text=final_text)

    async def _complete(self, conversation_id: str, conv: Conversation) -> AssistantResponse:
        message = await self._client.messages.create(**self._request_kwargs(conv))
        return self._to_response(conversation_id, conv, message)

    async def _stream(self, conversation_id: str, conv: Conversation) -> AsyncIterator[tuple[str, Any]]:
        async with self._client.messages.stream(**self._request_kwargs(conv)) as stream:
            async for text in stream.text_stream:
                yield "delta", text
            message = await stream.get_final_message()
        yield "done", self._to_response(conversation_id, conv, message)

    # ---------- публичное API ----------

    async def handle_request(self, req: AssistantRequest) -> AssistantResponse:
        conv = self.store.get_or_create(req.conversation_id)
        async with conv.lock:
            snapshot = list(conv.messages), conv.pending_tool_id, conv.pending_tool_name
            self._prepare_user_turn(conv, req)
            try:
                return await self._complete(req.conversation_id, conv)
            except Exception:
                conv.messages, conv.pending_tool_id, conv.pending_tool_name = snapshot
                raise

    async def handle_tool_result(self, req: ToolResultRequest) -> AssistantResponse:
        conv = self.store.get(req.conversation_id)
        async with conv.lock:
            snapshot = list(conv.messages), conv.pending_tool_id, conv.pending_tool_name
            self._prepare_tool_result(conv, req)
            try:
                return await self._complete(req.conversation_id, conv)
            except Exception:
                conv.messages, conv.pending_tool_id, conv.pending_tool_name = snapshot
                raise

    async def stream_request(self, req: AssistantRequest) -> AsyncIterator[tuple[str, Any]]:
        conv = self.store.get_or_create(req.conversation_id)
        async with conv.lock:
            snapshot = list(conv.messages), conv.pending_tool_id, conv.pending_tool_name
            self._prepare_user_turn(conv, req)
            try:
                async for event in self._stream(req.conversation_id, conv):
                    yield event
            except Exception:
                conv.messages, conv.pending_tool_id, conv.pending_tool_name = snapshot
                raise

    async def stream_tool_result(self, req: ToolResultRequest) -> AsyncIterator[tuple[str, Any]]:
        conv = self.store.get(req.conversation_id)
        async with conv.lock:
            snapshot = list(conv.messages), conv.pending_tool_id, conv.pending_tool_name
            self._prepare_tool_result(conv, req)
            try:
                async for event in self._stream(req.conversation_id, conv):
                    yield event
            except Exception:
                conv.messages, conv.pending_tool_id, conv.pending_tool_name = snapshot
                raise
