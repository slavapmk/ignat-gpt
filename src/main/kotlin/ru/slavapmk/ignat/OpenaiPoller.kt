package ru.slavapmk.ignat

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.adapter.rxjava3.RxJava3CallAdapterFactory
import retrofit2.converter.gson.GsonConverterFactory
import ru.slavapmk.ignat.io.OpenaiAPI
import ru.slavapmk.ignat.io.openai.OpenaiRequest
import ru.slavapmk.ignat.io.openai.OpenaiResponse
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

class OpenaiPoller(private val debugMode: Boolean, private val proxies: List<String>) {
    private val httpClient: OkHttpClient = OkHttpClient
        .Builder()
        .addInterceptor(
            with(HttpLoggingInterceptor {
                println("OPENAI  >>  $it")
            }) {
                level = when (debugMode) {
                    true -> HttpLoggingInterceptor.Level.BODY
                    false -> HttpLoggingInterceptor.Level.NONE
                }
                this
            }
        ).apply {
            if (proxies.isEmpty())
                println("Proxy dis-activated")
            else {
                proxySelector(SwitchProxySelector(proxies))
                println("Proxy activated")
            }
        }
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(600, TimeUnit.SECONDS)
        .build()
    private val api: OpenaiAPI = Retrofit
        .Builder()
        .client(httpClient)
        .baseUrl("https://api.openai.com")
        .addConverterFactory(GsonConverterFactory.create())
        .addCallAdapterFactory(RxJava3CallAdapterFactory.create())
        .build().create(OpenaiAPI::class.java)

    fun process(settingsManager: SettingsManager, request: OpenaiRequest): OpenaiResponse {
        var result: OpenaiResponse? = null
        var error: Throwable? = null
        var retryWait = 0L
        do {
            if (retryWait != 0L)
                println("Retry in ${retryWait.toDouble() / 1000}s")
            Thread.sleep(retryWait)

            api
                .request("Bearer ${settingsManager.openaiToken}", request)
                .blockingSubscribe(
                    { result = it },
                    { error = it }
                )
            if (error is HttpException) {
                val httpException = error as HttpException
                val e = gson.fromJson(
                    httpException.response()?.errorBody()?.string(),
                    OpenaiResponse::class.java
                )
                e.error?.code = httpException.code()
                retryWait = when (httpException.code()) {
                    401, 403, 429 -> {
                        settingsManager.openaiSwitch()
                        100
                    }

                    503 -> {
                        1000
                    }

                    500 -> {
                        1000
                    }

                    else -> return e
                }
            } else if (error is UnknownHostException) {
                retryWait = 5000
            } else {
                break
            }
        } while (true)

        throw error ?: return result!!
    }
}
