package ru.slavapmk.ignat

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import retrofit2.Retrofit
import retrofit2.adapter.rxjava3.RxJava3CallAdapterFactory
import retrofit2.converter.gson.GsonConverterFactory
import ru.slavapmk.ignat.io.BotGptRequest
import ru.slavapmk.ignat.io.OpenaiAPI
import ru.slavapmk.ignat.io.db.ContextsTable
import ru.slavapmk.ignat.io.db.MessagesTable
import java.util.concurrent.TimeUnit

class OpenaiPoller {
    val api: OpenaiAPI = Retrofit
        .Builder()
        .client(
            OkHttpClient
                .Builder()
                .addInterceptor(
                    with(HttpLoggingInterceptor()) {
                        level = when (settingsManager.debug) {
                            true -> HttpLoggingInterceptor.Level.BODY
                            false -> HttpLoggingInterceptor.Level.NONE
                        }
                        this
                    }
                )
                .readTimeout(120, TimeUnit.SECONDS)
                .build()
        )
        .baseUrl("https://api.openai.com")
        .addConverterFactory(GsonConverterFactory.create())
        .addCallAdapterFactory(RxJava3CallAdapterFactory.create())
        .build().create(OpenaiAPI::class.java)

    fun process(token: String, request: BotGptRequest): String {
        var resultText = ""
        var error: Throwable? = null
        api
            .request("Bearer $token", request.request)
            .blockingSubscribe(
                { resp ->
                    val responseChoice = resp.choices[0]
                    transaction {
                        MessagesTable.insert {
                            it[type] = responseChoice.message.role
                            it[contextId] = request.contextId
                            it[text] = responseChoice.message.content
                        }
                        ContextsTable.update({ ContextsTable.id eq request.contextId }) {
                            it[usage] = resp.usage.total_tokens
                        }
                    }
                    resultText = responseChoice.message.content
                },
                {
                    error = it
                }
            )

        throw error ?: return resultText
    }
}