package ru.voiceassistant.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class AssistantRequestDto(
    val text: String,
    val locale: String,
    @SerialName("device_timezone") val deviceTimezone: String,
    @SerialName("current_time") val currentTime: String,
    @SerialName("conversation_id") val conversationId: String,
)

@Serializable
data class ToolResultRequestDto(
    @SerialName("conversation_id") val conversationId: String,
    val tool: String,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    val status: String,
    val result: JsonObject = JsonObject(emptyMap()),
    val error: String? = null,
)

@Serializable
data class AssistantResponseDto(
    val type: String,
    @SerialName("conversation_id") val conversationId: String? = null,
    val text: String? = null,
    val tool: String? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    val arguments: JsonObject? = null,
    @SerialName("reply_after_execution") val replyAfterExecution: String? = null,
)

@Serializable
data class ErrorDto(val error: String? = null)

/** Разобранный ответ backend. */
sealed interface AssistantReply {
    data class Reply(val text: String) : AssistantReply
    data class ToolCall(
        val tool: String,
        val toolCallId: String?,
        val arguments: JsonObject,
        val hint: String?,
    ) : AssistantReply
}

/** Преобразует DTO в доменный ответ. Бросает [BackendException.BadResponse] при неверном формате. */
fun AssistantResponseDto.toReply(): AssistantReply = when (type) {
    "reply" -> AssistantReply.Reply(text?.takeIf { it.isNotBlank() } ?: throw BackendException.BadResponse())
    "tool_call" -> AssistantReply.ToolCall(
        tool = tool?.takeIf { it.isNotBlank() } ?: throw BackendException.BadResponse(),
        toolCallId = toolCallId,
        arguments = arguments ?: JsonObject(emptyMap()),
        hint = replyAfterExecution,
    )
    else -> throw BackendException.BadResponse()
}
