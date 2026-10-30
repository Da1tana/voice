"""Конфигурация backend. Все значения читаются из переменных окружения / .env."""
from functools import lru_cache

from pydantic import Field, SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    # Ключ Anthropic хранится ТОЛЬКО в переменной окружения ANTHROPIC_API_KEY.
    anthropic_api_key: SecretStr = Field(..., alias="ANTHROPIC_API_KEY")
    claude_model: str = Field("claude-sonnet-5-5", alias="CLAUDE_MODEL")
    claude_max_tokens: int = Field(512, alias="CLAUDE_MAX_TOKENS")
    claude_timeout_seconds: float = Field(30.0, alias="CLAUDE_TIMEOUT_SECONDS")

    # Общий секрет между приложением и backend (заголовок Authorization: Bearer ...).
    # Пустое значение отключает проверку — допустимо только для локальной разработки.
    app_token: SecretStr = Field(SecretStr(""), alias="APP_TOKEN")

    # CORS разрешён только для одного origin. Мобильному приложению CORS не нужен,
    # он нужен только если вы делаете веб-клиент.
    cors_origin: str = Field("", alias="CORS_ORIGIN")

    # Сколько хранить диалог в памяти и сколько сообщений держать в контексте.
    conversation_ttl_seconds: int = Field(1800, alias="CONVERSATION_TTL_SECONDS")
    conversation_max_messages: int = Field(30, alias="CONVERSATION_MAX_MESSAGES")
    max_text_length: int = Field(2000, alias="MAX_TEXT_LENGTH")

    log_level: str = Field("INFO", alias="LOG_LEVEL")


@lru_cache
def get_settings() -> Settings:
    return Settings()  # type: ignore[call-arg]
