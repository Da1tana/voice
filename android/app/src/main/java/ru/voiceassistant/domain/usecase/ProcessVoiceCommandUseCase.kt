package ru.voiceassistant.domain.usecase

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import ru.voiceassistant.data.remote.AssistantReply
import ru.voiceassistant.data.remote.BackendException
import ru.voiceassistant.data.repository.AppJson
import ru.voiceassistant.data.repository.AssistantRepository
import ru.voiceassistant.data.repository.ConversationRepository
import ru.voiceassistant.domain.model.ConversationEntry
import ru.voiceassistant.domain.model.ConversationStatus
import ru.voiceassistant.domain.model.ToolResult
import ru.voiceassistant.util.AppLog
import ru.voiceassistant.util.TimeProvider
import javax.inject.Inject

sealed interface CommandOutcome {
    data class Answer(val text: String, val actions: List<String>) : CommandOutcome
    data class Failed(val message: String) : CommandOutcome
}

/**
 * Полный цикл одной голосовой команды:
 * текст → backend → (tool_call → локальное выполнение → результат → backend)* → ответ.
 * Каждая команда записывается в историю диалога.
 */
class ProcessVoiceCommandUseCase @Inject constructor(
    private val assistant: AssistantRepository,
    private val tools: ToolExecutor,
    private val history: ConversationRepository,
    private val time: TimeProvider,
) {
    suspend operator fun invoke(text: String, conversationId: String, locale: String): CommandOutcome {
        val startedAt = time.nowMillis()
        val executed = mutableListOf<Pair<String, ToolResult>>()
        var anySuccess = false

        val outcome: CommandOutcome = try {
            var reply = assistant.sendCommand(text, conversationId, locale)
            var steps = 0
            while (reply is AssistantReply.ToolCall) {
                if (++steps > MAX_TOOL_STEPS) {
                    AppLog.w(TAG, "too many tool steps")
                    break
                }
                val result = tools.execute(reply.tool, reply.arguments)
                executed += reply.tool to result
                if (result is ToolResult.Success) anySuccess = true
                reply = assistant.sendToolResult(conversationId, reply.toolCallId, result)
            }
            when (reply) {
                is AssistantReply.Reply -> CommandOutcome.Answer(reply.text, executed.map { it.first })
                is AssistantReply.ToolCall -> CommandOutcome.Answer(fallbackText(anySuccess), executed.map { it.first })
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: BackendException) {
            AppLog.w(TAG, "backend failure", e)
            // Действие уже выполнено на телефоне, а ответ сервера потерян — не пугаем пользователя.
            if (anySuccess) CommandOutcome.Answer(fallbackText(true), executed.map { it.first })
            else CommandOutcome.Failed(e.userMessage)
        } catch (e: Exception) {
            AppLog.e(TAG, "unexpected failure", e)
            CommandOutcome.Failed("Что-то пошло не так. Попробуйте ещё раз")
        }

        saveHistory(text, outcome, executed, startedAt)
        return outcome
    }

    private suspend fun saveHistory(
        text: String,
        outcome: CommandOutcome,
        executed: List<Pair<String, ToolResult>>,
        createdAt: Long,
    ) {
        try {
            history.add(
                ConversationEntry(
                    requestText = text,
                    responseText = when (outcome) {
                        is CommandOutcome.Answer -> outcome.text
                        is CommandOutcome.Failed -> outcome.message
                    },
                    actionType = executed.map { it.first }.distinct().joinToString(",").ifEmpty { null },
                    actionPayload = executed.takeIf { it.isNotEmpty() }?.let { payload(it) },
                    status = if (outcome is CommandOutcome.Answer) ConversationStatus.SUCCESS else ConversationStatus.ERROR,
                    createdAt = createdAt,
                )
            )
        } catch (e: Exception) {
            AppLog.e(TAG, "history save failed", e)
        }
    }

    private fun payload(executed: List<Pair<String, ToolResult>>): String {
        val arr = JsonArray(executed.map { (tool, r) ->
            JsonObject(
                mapOf(
                    "tool" to JsonPrimitive(tool),
                    "status" to JsonPrimitive(if (r is ToolResult.Success) "success" else "error"),
                    "result" to when (r) {
                        is ToolResult.Success -> r.data
                        is ToolResult.Failure -> r.data
                    },
                )
            )
        })
        return AppJson.encodeToString(JsonArray.serializer(), arr)
    }

    private fun fallbackText(anySuccess: Boolean) =
        if (anySuccess) "Готово. Сервер ассистента не прислал ответ, но действие выполнено."
        else "Не получилось выполнить команду. Попробуйте ещё раз."

    private companion object {
        const val TAG = "Command"
        const val MAX_TOOL_STEPS = 4
    }
}
