package ru.voiceassistant

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import ru.voiceassistant.data.remote.AssistantApi
import ru.voiceassistant.data.remote.AssistantReply
import ru.voiceassistant.data.remote.AssistantResponseDto
import ru.voiceassistant.data.remote.BackendException
import ru.voiceassistant.data.remote.toReply
import ru.voiceassistant.data.repository.AppJson
import ru.voiceassistant.data.repository.AppSettings
import ru.voiceassistant.data.repository.BackupRepository
import ru.voiceassistant.data.repository.RemoteAssistantRepository
import ru.voiceassistant.data.repository.SettingsRepository
import ru.voiceassistant.domain.model.ToolResult
import ru.voiceassistant.util.DateTimeUtils
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.ZoneId
import java.util.concurrent.TimeUnit

class BackendParsingTest {

    @Test
    fun parsesReply() {
        val dto = AppJson.decodeFromString(AssistantResponseDto.serializer(), """{"type":"reply","text":"Готово."}""")
        assertEquals(AssistantReply.Reply("Готово."), dto.toReply())
    }

    @Test
    fun parsesToolCallFromSpecExample() {
        val json = """
            {"type":"tool_call","tool":"add_task",
             "arguments":{"title":"Купить хлеб","due_at":"2026-10-31T18:00:00+03:00"},
             "reply_after_execution":"Скажи пользователю, что задача добавлена.",
             "some_future_field": 1}
        """.trimIndent()
        val reply = AppJson.decodeFromString(AssistantResponseDto.serializer(), json).toReply()
        assertTrue(reply is AssistantReply.ToolCall)
        reply as AssistantReply.ToolCall
        assertEquals("add_task", reply.tool)
        assertEquals("Купить хлеб", reply.arguments["title"]!!.jsonPrimitive.content)
    }

    @Test(expected = BackendException.BadResponse::class)
    fun unknownTypeIsBadResponse() {
        AppJson.decodeFromString(AssistantResponseDto.serializer(), """{"type":"weird"}""").toReply()
    }

    @Test(expected = BackendException.BadResponse::class)
    fun emptyReplyIsBadResponse() {
        AppJson.decodeFromString(AssistantResponseDto.serializer(), """{"type":"reply","text":"  "}""").toReply()
    }

    @Test
    fun dateParsingVariants() {
        val z = ZoneId.of("Europe/Moscow")
        val a = DateTimeUtils.parseToEpochMillis("2026-10-31T18:00:00+03:00", z)
        val b = DateTimeUtils.parseToEpochMillis("2026-10-31T15:00:00Z", z)
        val c = DateTimeUtils.parseToEpochMillis("2026-10-31T18:00", z)
        assertEquals(a, b)
        assertEquals(a, c)
        assertEquals(null, DateTimeUtils.parseToEpochMillis("послезавтра", z))
    }

    @Test(expected = IllegalArgumentException::class)
    fun backupRejectsForeignJson() {
        BackupRepository.parse("""{"hello":"world"}""")
    }

    // ---------- HTTP через MockWebServer ----------

    private lateinit var server: MockWebServer
    private lateinit var repo: RemoteAssistantRepository

    @Before
    fun startServer() {
        server = MockWebServer().also { it.start() }
        val api = Retrofit.Builder()
            .baseUrl("http://localhost/")
            .client(OkHttpClient.Builder().readTimeout(1, TimeUnit.SECONDS).build())
            .addConverterFactory(AppJson.asConverterFactory("application/json".toMediaType()))
            .build().create(AssistantApi::class.java)
        val settings = object : SettingsRepository {
            val s = AppSettings(backendUrl = server.url("/").toString().trimEnd('/'), appToken = "tkn")
            override val settings = MutableStateFlow(s)
            override suspend fun current() = s
            override suspend fun update(transform: (AppSettings) -> AppSettings) = Unit
        }
        repo = RemoteAssistantRepository(api, settings, FixedTime())
    }

    @After
    fun stopServer() = server.shutdown()

    @Test
    fun sendsRequestInSpecFormat() = runTest {
        server.enqueue(MockResponse().setBody("""{"type":"reply","text":"Привет"}"""))
        val reply = repo.sendCommand("привет", "conv-1", "ru-RU")
        assertEquals(AssistantReply.Reply("Привет"), reply)

        val req = server.takeRequest()
        assertEquals("/assistant", req.path)
        assertEquals("Bearer tkn", req.getHeader("Authorization"))
        val body = AppJson.parseToJsonElement(req.body.readUtf8()) as JsonObject
        assertEquals("Europe/Moscow", body["device_timezone"]!!.jsonPrimitive.content)
        assertEquals("2026-10-30T12:00:00+03:00", body["current_time"]!!.jsonPrimitive.content)
        assertEquals("conv-1", body["conversation_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun sendsToolResult() = runTest {
        server.enqueue(MockResponse().setBody("""{"type":"reply","text":"Задача добавлена"}"""))
        repo.sendToolResult("conv-1", "toolu_1", ToolResult.Success("add_task", JsonObject(mapOf("task_id" to JsonPrimitive(12)))))
        val body = AppJson.parseToJsonElement(server.takeRequest().body.readUtf8()) as JsonObject
        assertEquals("success", body["status"]!!.jsonPrimitive.content)
        assertEquals("12", (body["result"] as JsonObject)["task_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun mapsHttpErrors() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"x"}"""))
        expect<BackendException.Unauthorized> { repo.sendCommand("a", "c", "ru-RU") }
        server.enqueue(MockResponse().setResponseCode(409))
        expect<BackendException.ConversationExpired> {
            repo.sendToolResult("c", null, ToolResult.Failure("add_task", "x"))
        }
        server.enqueue(MockResponse().setResponseCode(502))
        expect<BackendException.Server> { repo.sendCommand("a", "c", "ru-RU") }
    }

    @Test
    fun timeoutIsReported() = runTest {
        server.enqueue(MockResponse().setBody("""{"type":"reply","text":"поздно"}""").setBodyDelay(3, TimeUnit.SECONDS))
        expect<BackendException.Timeout> { repo.sendCommand("a", "c", "ru-RU") }
    }

    private suspend inline fun <reified T : Throwable> expect(block: () -> Unit) {
        try {
            block()
            fail("Ожидалось ${T::class.simpleName}")
        } catch (e: Throwable) {
            if (e !is T) throw AssertionError("Ожидалось ${T::class.simpleName}, получено ${e::class.simpleName}", e)
        }
    }
}
