package ru.slavapmk.ignat

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.adapter.rxjava3.RxJava3CallAdapterFactory
import retrofit2.converter.gson.GsonConverterFactory
import ru.slavapmk.ignat.io.YandexTranslateApi
import ru.slavapmk.ignat.io.yandex.TranslateRequest
import ru.slavapmk.ignat.io.yandex.TranslateResponse
import java.net.UnknownHostException

class Translator(private val debugMode: Boolean) {
    private val api = Retrofit
        .Builder()
        .client(
            OkHttpClient
                .Builder()
                .addInterceptor(
                    with(HttpLoggingInterceptor {
                        println("YANDEX  >>  $it")
                    }) {
                        level = when (debugMode) {
                            true -> HttpLoggingInterceptor.Level.BODY
                            false -> HttpLoggingInterceptor.Level.NONE
                        }
                        this
                    }
                )
                .build()
        )
        .baseUrl("https://translate.api.cloud.yandex.net")
        .addConverterFactory(GsonConverterFactory.create())
        .addCallAdapterFactory(RxJava3CallAdapterFactory.create())
        .build().create(YandexTranslateApi::class.java)

    fun translate(request: String, target: String, token: String, folder: String): TranslateResponse {
        while (true) {
            try {
                return api.request(
                    "Bearer $token",
                    TranslateRequest(
                        folder,
                        arrayOf(request),
                        target
                    )
                ).blockingSingle()

            } catch (_: UnknownHostException) {
            }
        }
    }
}