package ru.slavapmk.ignat

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.adapter.rxjava3.RxJava3CallAdapterFactory
import retrofit2.converter.gson.GsonConverterFactory
import ru.slavapmk.ignat.io.YandexTranslateApi
import ru.slavapmk.ignat.io.yandex.TranslateRequest

class Translator(private val debugMode: Boolean) {
    private val api = Retrofit
        .Builder()
        .client(
            OkHttpClient
                .Builder()
                .addInterceptor(
                    with(HttpLoggingInterceptor()) {
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

    fun translate(request: String, target: String, token: String, folder: String) =
        api.request(
            "Bearer $token",
            TranslateRequest(
                folder,
                arrayOf(request),
                target
            )
        ).blockingSingle()
}


fun main() {
    val settingsManager1 = SettingsManager()
    settingsManager1.readOrInit()
//    println(settingsManager1.yandexToken)
    val translator = Translator(false)
    val request = "ПРивет, хуесос"


    println(request)
    println()

    val message = translator.translate(
        request,
        settingsManager1.yandexToken,
        "en",
        settingsManager1.yandexFolder
    )

    println(message.translations[0].text)

    println()

    val translate = translator.translate(
        message.translations[0].text,
        settingsManager1.yandexToken,
        message.translations[0].detectedLanguageCode,
        settingsManager1.yandexFolder
    )

    println(translate.translations[0].text)

}