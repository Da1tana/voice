"""Pydantic-модели запросов и ответов API."""
from typing import Any, Literal, Optional

from pydantic import BaseModel, Field


class AssistantRequest(BaseModel):
    text: str = Field(..., min_length=1)
    locale: str = "ru-RU"
    device_timezone: str = "Europe/Moscow"
    current_time: str = Field(..., description="ISO-8601 с часовым поясом устройства")
    conversation_id: str = Field(..., min_length=1, max_length=64)


class ToolResultRequest(BaseModel):
    conversation_id: str = Field(..., min_length=1, max_length=64)
    tool: str
    tool_call_id: Optional[str] = None
    status: Literal["success", "error"]
    result: dict[str, Any] = Field(default_factory=dict)
    error: Optional[str] = None


class AssistantResponse(BaseModel):
    """Либо type=reply с text, либо type=tool_call с tool/arguments."""

    type: Literal["reply", "tool_call"]
    conversation_id: str
    text: Optional[str] = None
    tool: Optional[str] = None
    tool_call_id: Optional[str] = None
    arguments: Optional[dict[str, Any]] = None
    reply_after_execution: Optional[str] = None


class HealthResponse(BaseModel):
    status: str = "ok"
    model: str
