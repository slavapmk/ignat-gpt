package ru.slavapmk.ignat.io

import ru.slavapmk.ignat.io.openai.OpenaiRequest
import ru.slavapmk.ignat.io.openai.OpenaiResponse
import io.reactivex.rxjava3.core.Observable
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

interface OpenaiAPI {
    @POST("/v1/chat/completions")
    fun request(
        @Header("Authorization") token: String,
        @Body request: OpenaiRequest
    ): Observable<OpenaiResponse>
}