package ru.voiceassistant.data.remote

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Url

/**
 * Адрес backend меняется в настройках во время работы, поэтому каждый метод
 * принимает полный URL через @Url, а базовый адрес Retrofit — заглушка.
 */
interface AssistantApi {
    @POST
    suspend fun assistant(
        @Url url: String,
        @Header("Authorization") auth: String?,
        @Body body: AssistantRequestDto,
    ): Response<AssistantResponseDto>

    @POST
    suspend fun result(
        @Url url: String,
        @Header("Authorization") auth: String?,
        @Body body: ToolResultRequestDto,
    ): Response<AssistantResponseDto>

    @GET
    suspend fun health(@Url url: String): Response<Unit>
}
