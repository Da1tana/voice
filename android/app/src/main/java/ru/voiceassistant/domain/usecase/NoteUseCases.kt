package ru.voiceassistant.domain.usecase

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import ru.voiceassistant.data.repository.NoteRepository
import ru.voiceassistant.domain.model.Note
import ru.voiceassistant.domain.model.ToolResult
import ru.voiceassistant.util.DateTimeUtils
import ru.voiceassistant.util.Formatters
import ru.voiceassistant.util.TimeProvider
import ru.voiceassistant.util.Validation
import javax.inject.Inject

class AddNoteUseCase @Inject constructor(
    private val notes: NoteRepository,
    private val time: TimeProvider,
) {
    /** Если заголовок не указан, берётся первая строка (до 60 символов). */
    suspend operator fun invoke(text: String, tags: List<String> = emptyList(), title: String? = null): Long {
        val content = text.trim().take(Validation.MAX_TEXT)
        if (content.isEmpty()) throw ToolArgumentException("Текст заметки пустой")
        val now = time.nowMillis()
        return notes.add(
            Note(
                title = title?.trim()?.takeIf { it.isNotEmpty() } ?: deriveTitle(content),
                content = content,
                tags = Formatters.parseTags(tags.joinToString(",")),
                createdAt = now,
                updatedAt = now,
            )
        )
    }

    suspend fun fromTool(args: JsonObject): ToolResult {
        val text = args.str("text") ?: throw ToolArgumentException("Не указан текст заметки")
        val tags = args.stringList("tags")
        val id = invoke(text, tags)
        return ToolResult.Success("add_note", buildJsonObject {
            put("note_id", id)
            putJsonArray("tags") { Formatters.parseTags(tags.joinToString(",")).forEach { add(it) } }
        })
    }

    companion object {
        fun deriveTitle(content: String): String {
            val line = content.lineSequence().first().trim()
            return if (line.length <= 60) line else line.take(57).trimEnd() + "…"
        }
    }
}

class SearchNotesUseCase @Inject constructor(
    private val notes: NoteRepository,
    private val time: TimeProvider,
) {
    suspend operator fun invoke(query: String, limit: Int = 10): List<Note> {
        val q = query.trim().removePrefix("#")
        if (q.isEmpty()) return emptyList()
        val direct = notes.search(q, limit)
        if (direct.isNotEmpty()) return direct
        // Голосовой запрос часто длиннее текста заметки: ищем по отдельным словам.
        return q.split(' ').filter { it.length >= 3 }
            .flatMap { notes.search(it, limit) }
            .distinctBy { it.id }
            .take(limit)
    }

    suspend fun fromTool(args: JsonObject): ToolResult {
        val query = args.str("query") ?: throw ToolArgumentException("Не указано, что искать")
        val found = invoke(query, MAX_ITEMS)
        return ToolResult.Success("search_notes", buildJsonObject {
            put("query", query)
            put("count", found.size)
            putJsonArray("notes") {
                found.forEach { n ->
                    addJsonObject {
                        put("note_id", n.id)
                        put("title", n.title)
                        put("content", n.content.take(MAX_CONTENT))
                        put("updated_at", DateTimeUtils.toIsoOffset(n.updatedAt, time.zone()))
                    }
                }
            }
        })
    }

    private companion object {
        const val MAX_ITEMS = 5
        const val MAX_CONTENT = 500
    }
}
