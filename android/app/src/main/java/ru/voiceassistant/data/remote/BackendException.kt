package ru.voiceassistant.data.remote

/** Ошибки связи с backend с готовыми текстами для пользователя. */
sealed class BackendException(val userMessage: String) : Exception(userMessage) {
    class NotConfigured : BackendException("Укажите адрес сервера ассистента в настройках")
    class NoConnection : BackendException("Нет подключения к серверу ассистента")
    class Timeout : BackendException("Сервер ассистента не ответил вовремя. Попробуйте ещё раз")
    class Unauthorized : BackendException("Сервер отклонил токен приложения. Проверьте его в настройках")
    class ConversationExpired : BackendException("Диалог устарел. Повторите команду")
    class RateLimited : BackendException("Слишком много запросов. Подождите немного")
    class Server(code: Int) : BackendException("Сервер ассистента недоступен (код $code)")
    class BadResponse : BackendException("Сервер вернул непонятный ответ")
}
