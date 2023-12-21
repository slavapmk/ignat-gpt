package ru.slavapmk.ignat

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.adapter.rxjava3.RxJava3CallAdapterFactory
import retrofit2.converter.gson.GsonConverterFactory
import ru.slavapmk.ignat.io.YandexIamApi
import ru.slavapmk.ignat.io.yandex.IamTokenRequest
import ru.slavapmk.ignat.io.yandex.IamTokenResponse
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.io.IOException

val gson: Gson = GsonBuilder().setPrettyPrinting().create()

class SettingsManager {
    var proxies = emptyList<String>()
        private set

    private var openaiTokens = emptyList<String>()

    var telegramToken: String = ""
        private set
    var debugMode: Boolean = false
        private set
    var enableYandex: Boolean = true
        private set
    var yandexAuthFolder: String = ""
        private set
    private var yandexOauthToken: String = ""

    @Transient
    private lateinit var yandexIam: IamTokenResponse

    @Transient
    private lateinit var api: YandexIamApi

    @Transient
    private var currentOpenai = 0

    val openaiToken: String
        get() = openaiTokens[currentOpenai]

    fun openaiSwitch() {
        currentOpenai = (++currentOpenai) / openaiTokens.size
        println("Switched openai to $currentOpenai")
    }

    val yandexToken
        get():String {
            return if (!enableYandex) ""
            else if (!this::yandexIam.isInitialized || yandexIam.expiresEpoch < System.currentTimeMillis())
                with(api.request(IamTokenRequest(yandexOauthToken)).blockingSingle()) {
                    yandexIam = this
                    this.iamToken
                }
            else yandexIam.iamToken
        }

    fun readOrInit(): Boolean {
        val file = File("storage/settings.json").absoluteFile

        val parentFile = file.parentFile
        if (!file.exists() || !parentFile.exists()) {
            if (!parentFile.exists())
                if (!parentFile.mkdir())
                    if (!parentFile.mkdirs())
                        throw IOException()
            FileWriter(file).use {
                gson.toJson(this, it)
            }
            return false
        }

        val fromJson = FileReader(file).use {
            gson.fromJson(it, SettingsManager::class.java)
        }

        openaiTokens = fromJson.openaiTokens
        telegramToken = fromJson.telegramToken
        debugMode = fromJson.debugMode
        yandexOauthToken = fromJson.yandexOauthToken
        yandexAuthFolder = fromJson.yandexAuthFolder
        proxies = fromJson.proxies


        api = Retrofit
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
            .baseUrl("https://iam.api.cloud.yandex.net")
            .addConverterFactory(GsonConverterFactory.create())
            .addCallAdapterFactory(RxJava3CallAdapterFactory.create())
            .build().create(YandexIamApi::class.java)

        return true
    }


}