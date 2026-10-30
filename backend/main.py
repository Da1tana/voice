"""FastAPI backend голосового ассистента.

Эндпоинты:
  GET  /health                     — проверка живости
  POST /assistant                  — текст команды → reply | tool_call
  POST /assistant/result           — результат локального действия → reply | tool_call
  POST /assistant/stream           — то же, что /assistant, но SSE-поток
  POST /assistant/result/stream    — то же, что /assistant/result, но SSE-поток
"""
from __future__ import annotations

import json
import logging
import secrets
from contextlib import asynccontextmanager
from typing import Any, AsyncIterator

import anthropic
from fastapi import Depends, FastAPI, Header, HTTPException, Request, status
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse, StreamingResponse

from claude_service import ClaudeService, ToolMismatchError, UnknownConversationError
from config import Settings, get_settings
from models import AssistantRequest, AssistantResponse, HealthResponse, ToolResultRequest

settings = get_settings()
logging.basicConfig(level=settings.log_level, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
# httpx на уровне INFO пишет полные URL; тело запросов он не логирует, но лишний шум не нужен.
logging.getLogger("httpx").setLevel(logging.WARNING)
log = logging.getLogger("assistant.api")


@asynccontextmanager
async def lifespan(app: FastAPI):
    app.state.service = ClaudeService(settings)
    if not settings.app_token.get_secret_value():
        log.warning("APP_TOKEN не задан: backend доступен без авторизации. Используйте только локально.")
    yield


app = FastAPI(title="Voice Assistant Backend", version="1.0.0", lifespan=lifespan)

if settings.cors_origin:
    app.add_middleware(
        CORSMiddleware,
        allow_origins=[settings.cors_origin],
        allow_methods=["GET", "POST"],
        allow_headers=["Authorization", "Content-Type"],
    )


def get_service(request: Request) -> ClaudeService:
    return request.app.state.service


def require_token(authorization: str = Header(default=""), s: Settings = Depends(get_settings)) -> None:
    expected = s.app_token.get_secret_value()
    if not expected:
        return
    provided = authorization.removeprefix("Bearer ").strip()
    if not secrets.compare_digest(provided.encode(), expected.encode()):
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Неверный токен приложения")


def _map_error(exc: Exception) -> HTTPException:
    if isinstance(exc, UnknownConversationError):
        return HTTPException(status.HTTP_404_NOT_FOUND, "Диалог не найден или истёк")
    if isinstance(exc, ToolMismatchError):
        return HTTPException(status.HTTP_409_CONFLICT, str(exc))
    if isinstance(exc, anthropic.APITimeoutError):
        return HTTPException(status.HTTP_504_GATEWAY_TIMEOUT, "Модель не ответила вовремя")
    if isinstance(exc, anthropic.RateLimitError):
        return HTTPException(status.HTTP_429_TOO_MANY_REQUESTS, "Слишком много запросов, попробуйте позже")
    if isinstance(exc, (anthropic.APIConnectionError, anthropic.APIStatusError)):
        log.error("Anthropic API error: %s", type(exc).__name__)
        return HTTPException(status.HTTP_502_BAD_GATEWAY, "Сервис модели недоступен")
    log.exception("Unexpected error: %s", type(exc).__name__)
    return HTTPException(status.HTTP_500_INTERNAL_SERVER_ERROR, "Внутренняя ошибка сервера")


@app.exception_handler(HTTPException)
async def http_exception_handler(_: Request, exc: HTTPException) -> JSONResponse:
    return JSONResponse(status_code=exc.status_code, content={"error": exc.detail})


@app.get("/health", response_model=HealthResponse)
async def health() -> HealthResponse:
    return HealthResponse(model=settings.claude_model)


@app.post("/assistant", response_model=AssistantResponse, response_model_exclude_none=True,
          dependencies=[Depends(require_token)])
async def assistant(req: AssistantRequest, service: ClaudeService = Depends(get_service)) -> AssistantResponse:
    try:
        return await service.handle_request(req)
    except Exception as exc:  # noqa: BLE001
        raise _map_error(exc) from exc


@app.post("/assistant/result", response_model=AssistantResponse, response_model_exclude_none=True,
          dependencies=[Depends(require_token)])
async def assistant_result(req: ToolResultRequest, service: ClaudeService = Depends(get_service)) -> AssistantResponse:
    try:
        return await service.handle_tool_result(req)
    except Exception as exc:  # noqa: BLE001
        raise _map_error(exc) from exc


def _sse(event: str, data: Any) -> str:
    return f"event: {event}\ndata: {json.dumps(data, ensure_ascii=False)}\n\n"


async def _sse_stream(events: AsyncIterator[tuple[str, Any]]) -> AsyncIterator[str]:
    """Формат событий:
    event: delta  data: {"text": "..."}         — кусок текста по мере генерации
    event: done   data: AssistantResponse       — итоговый ответ (reply или tool_call)
    event: error  data: {"status": 502, "error": "..."}
    """
    try:
        async for kind, payload in events:
            if kind == "delta":
                yield _sse("delta", {"text": payload})
            else:
                yield _sse("done", payload.model_dump(exclude_none=True))
    except Exception as exc:  # noqa: BLE001
        http = _map_error(exc)
        yield _sse("error", {"status": http.status_code, "error": http.detail})


_SSE_HEADERS = {"Cache-Control": "no-cache", "X-Accel-Buffering": "no"}


@app.post("/assistant/stream", dependencies=[Depends(require_token)])
async def assistant_stream(req: AssistantRequest, service: ClaudeService = Depends(get_service)) -> StreamingResponse:
    return StreamingResponse(_sse_stream(service.stream_request(req)), media_type="text/event-stream",
                             headers=_SSE_HEADERS)


@app.post("/assistant/result/stream", dependencies=[Depends(require_token)])
async def assistant_result_stream(req: ToolResultRequest,
                                  service: ClaudeService = Depends(get_service)) -> StreamingResponse:
    # Проверяем диалог до начала потока, чтобы вернуть нормальный 404, а не SSE-ошибку.
    try:
        service.store.get(req.conversation_id)
    except UnknownConversationError as exc:
        raise _map_error(exc) from exc
    return StreamingResponse(_sse_stream(service.stream_tool_result(req)), media_type="text/event-stream",
                             headers=_SSE_HEADERS)
