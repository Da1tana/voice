package ru.voiceassistant.domain.usecase

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** Безопасное чтение аргументов инструмента: модель может прислать число строкой и т.п. */
internal fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

internal fun JsonObject.long(key: String): Long? {
    val p = this[key] as? JsonPrimitive ?: return null
    return p.longOrNull ?: p.doubleOrNull?.toLong() ?: p.contentOrNull?.trim()?.toLongOrNull()
}

internal fun JsonObject.double(key: String): Double? {
    val p = this[key] as? JsonPrimitive ?: return null
    return p.doubleOrNull ?: p.contentOrNull?.trim()?.replace(',', '.')?.replace(" ", "")?.toDoubleOrNull()
}

internal fun JsonObject.stringList(key: String): List<String> = when (val v = this[key]) {
    is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }.filter { it.isNotEmpty() }
    is JsonPrimitive -> v.contentOrNull?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
    else -> emptyList()
}

/** Ошибка валидации аргументов — текст уходит модели, чтобы она переспросила пользователя. */
class ToolArgumentException(message: String) : IllegalArgumentException(message)
