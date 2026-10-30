package ru.voiceassistant.domain.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Результат локального действия, который отправляется обратно на backend. */
sealed interface ToolResult {
    val tool: String

    data class Success(override val tool: String, val data: JsonObject) : ToolResult

    data class Failure(override val tool: String, val message: String) : ToolResult {
        val data: JsonObject get() = buildJsonObject { put("message", message) }
    }
}
