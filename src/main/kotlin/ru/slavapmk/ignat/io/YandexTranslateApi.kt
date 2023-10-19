package ru.slavapmk.ignat.io

import io.reactivex.rxjava3.core.Observable
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import ru.slavapmk.ignat.io.yandex.TranslateRequest
import ru.slavapmk.ignat.io.yandex.TranslateResponse

interface YandexTranslateApi {
    @POST("/translate/v2/translate")
    fun request(
        @Header("Authorization") token: String,
        @Body request: TranslateRequest
    ): Observable<TranslateResponse>
}