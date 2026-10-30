package ru.voiceassistant.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import retrofit2.Response
import ru.voiceassistant.data.remote.AssistantApi
import ru.voiceassistant.data.remote.AssistantReply
import ru.voiceassistant.data.remote.AssistantRequestDto
import ru.voiceassistant.data.remote.AssistantResponseDto
import ru.voiceassistant.data.remote.BackendException
import ru.voiceassistant.data.remote.ToolResultRequestDto
import ru.voiceassistant.data.remote.toReply
import ru.voiceassistant.domain.model.ToolResult
import ru.voiceassistant.util.AppLog
import ru.voiceassistant.util.DateTimeUtils
import ru.voiceassistant.util.TimeProvider
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton

interface AssistantRepository {
    suspend fun sendCommand(text: String, conversationId: String, locale: String): AssistantReply
    suspend fun sendToolResult(conversationId: String, toolCallId: String?, result: ToolResult): AssistantReply
    suspend fun checkHealth(baseUrl: String): Boolean
}

@Singleton
class RemoteAssistantRepository @Inject constructor(
    private val api: AssistantApi,
    private val settings: SettingsRepository,
    private val time: TimeProvider,
) : AssistantRepository {

    override suspend fun sendCommand(text: String, conversationId: String, locale: String): AssistantReply {
        val s = settings.current()
        val body = AssistantRequestDto(
            text = text,
            locale = locale,
            deviceTimezone = time.zone().id,
            currentTime = DateTimeUtils.toIsoOffset(time.nowMillis(), time.zone()),
            conversationId = conversationId,
        )
        return call(isResult = false) { api.assistant(endpoint(s, "/assistant"), auth(s), body) }
    }

    override suspend fun sendToolResult(conversationId: String, toolCallId: String?, result: ToolResult): AssistantReply {
        val s = settings.current()
        val body = when (result) {
            is ToolResult.Success -> ToolResultRequestDto(conversationId, result.tool, toolCallId, "success", result.data)
            is ToolResult.Failure -> ToolResultRequestDto(
                conversationId, result.tool, toolCallId, "error", result.data, result.message,
            )
        }
        return call(isResult = true) { api.result(endpoint(s, "/assistant/result"), auth(s), body) }
    }

    override suspend fun checkHealth(baseUrl: String): Boolean = try {
        api.health(baseUrl.trim().trimEnd('/') + "/health").isSuccessful
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AppLog.w(TAG, "health check failed", e)
        false
    }

    private fun endpoint(s: AppSettings, path: String): String {
        if (s.backendUrl.isBlank()) throw BackendException.NotConfigured()
        return s.backendUrl.trimEnd('/') + path
    }

    private fun auth(s: AppSettings): String? = s.appToken.takeIf { it.isNotBlank() }?.let { "Bearer $it" }

    private suspend fun call(isResult: Boolean, block: suspend () -> Response<AssistantResponseDto>): AssistantReply {
        val response = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: BackendException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw BackendException.Timeout()
        } catch (e: InterruptedIOException) {
            throw BackendException.Timeout()
        } catch (e: UnknownHostException) {
            throw BackendException.NoConnection()
        } catch (e: IOException) {
            throw BackendException.NoConnection()
        } catch (e: kotlinx.serialization.SerializationException) {
            throw BackendException.BadResponse()
        }
        AppLog.i(TAG, "backend http=${response.code()}")
        if (!response.isSuccessful) throw mapHttpError(response.code(), isResult)
        return response.body()?.toReply() ?: throw BackendException.BadResponse()
    }

    private companion object {
        const val TAG = "Backend"
    }
}

/** 404/409 означают «диалог истёк» только для /assistant/result; для /assistant 404 — неверный адрес. */
internal fun mapHttpError(code: Int, isResult: Boolean): BackendException = when (code) {
    401, 403 -> BackendException.Unauthorized()
    404, 409 -> if (isResult) BackendException.ConversationExpired() else BackendException.Server(code)
    429 -> BackendException.RateLimited()
    504 -> BackendException.Timeout()
    else -> BackendException.Server(code)
}

/** Общий экземпляр Json: незнакомые поля из backend не ломают разбор. */
val AppJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    isLenient = true
}
