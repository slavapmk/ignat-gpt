package ru.slavapmk.ignat

import com.google.gson.JsonSyntaxException
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.adapter.rxjava3.RxJava3CallAdapterFactory
import retrofit2.converter.gson.GsonConverterFactory
import ru.slavapmk.ignat.io.OpenaiAPI
import ru.slavapmk.ignat.io.openai.OpenaiRequest
import ru.slavapmk.ignat.io.openai.OpenaiResponse
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class OpenaiProcessor(httpClient: OkHttpClient) {
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
            when (error) {
                is HttpException -> {
                    val httpException = error as HttpException
                    retryWait = when (httpException.code()) {
                        401 -> {
                            settingsManager.openaiSwitch()
                            100
                        }

                        403 -> {
                            settingsManager.openaiSwitch()
                            100
                        }

                        429 -> {
                            settingsManager.openaiSwitch()
                            100
                        }

                        503 -> 1000

                        500 -> 1000

                        else -> {
                            val e = gson.fromJson(
                                httpException.response()?.errorBody()?.string()?.replace("null", "\"null\""),
                                OpenaiResponse::class.java
                            )
                            e.error?.code = httpException.code()
                            return e
                        }
                    }
                }

                is UnknownHostException -> retryWait = 5000
                is JsonSyntaxException -> retryWait = 100
                is SocketTimeoutException -> retryWait = 100
                else -> break
            }
        } while (true)

        throw error ?: return result!!
    }
}
