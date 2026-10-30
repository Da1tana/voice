# Backend голосового ассистента (FastAPI + Claude)

Тонкий прокси между Android-приложением и Anthropic Messages API. Хранит ключ Anthropic, системный промпт, описание инструментов и короткий контекст диалога. Сами действия (задачи, заметки, расходы) выполняет телефон.

## Запуск локально

```bash
cd backend
python -m venv .venv && source .venv/bin/activate      # Windows: .venv\Scripts\activate
pip install -r requirements.txt
cp .env.example .env                                   # впишите ANTHROPIC_API_KEY и APP_TOKEN
uvicorn main:app --host 0.0.0.0 --port 8000
```

Проверка: `curl http://127.0.0.1:8000/health`

## Запуск в Docker

```bash
cp .env.example .env   # заполнить
docker compose up -d --build
```

Порт опубликован только на 127.0.0.1 — наружу выставляйте через reverse proxy с HTTPS. Пример Caddyfile:

```
assistant.example.ru {
    reverse_proxy 127.0.0.1:8000
}
```

## API

Все запросы, кроме `/health`, требуют заголовок `Authorization: Bearer <APP_TOKEN>` (если APP_TOKEN задан).

| Метод | Путь | Описание |
|---|---|---|
| GET | `/health` | `{"status":"ok","model":"..."}` |
| POST | `/assistant` | текст команды → `reply` или `tool_call` |
| POST | `/assistant/result` | результат действия → `reply` или следующий `tool_call` |
| POST | `/assistant/stream` | как `/assistant`, но SSE |
| POST | `/assistant/result/stream` | как `/assistant/result`, но SSE |

Пример:

```bash
curl -s http://127.0.0.1:8000/assistant \
  -H "Authorization: Bearer $APP_TOKEN" -H "Content-Type: application/json" \
  -d '{"text":"Добавь задачу купить хлеб завтра в шесть вечера","locale":"ru-RU",
       "device_timezone":"Europe/Moscow","current_time":"2026-10-30T12:00:00+03:00",
       "conversation_id":"test-1"}'
```

Ответ `tool_call` содержит `tool_call_id` — приложение передаёт его обратно в `/assistant/result` (поле необязательное, но помогает отловить рассинхронизацию).

### Формат SSE

```
event: delta
data: {"text": "Сегодня у вас "}

event: done
data: {"type":"reply","conversation_id":"...","text":"Сегодня у вас две задачи."}

event: error
data: {"status": 502, "error": "Сервис модели недоступен"}
```

## Коды ошибок

401 — неверный токен, 404 — диалог истёк (TTL 30 минут), 409 — результат не для того инструмента, 429 — лимит Anthropic, 502 — Anthropic недоступен, 504 — таймаут модели.

## Тесты

```bash
pip install -r requirements-dev.txt
pytest -q
```

Тесты подменяют клиент Anthropic и не тратят токены.

## Что важно знать

- Контекст диалогов хранится в памяти одного процесса, поэтому запускайте один воркер. Для нескольких реплик замените `ConversationStore` на Redis.
- Тексты запросов и ответов в лог не пишутся — только служебные события (имя инструмента, длина ответа, тип ошибки).
- Модель меняется переменной `CLAUDE_MODEL` без правки кода.
