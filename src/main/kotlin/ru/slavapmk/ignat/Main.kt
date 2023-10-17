package ru.slavapmk.ignat

import com.github.kotlintelegrambot.entities.ChatAction
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.ParseMode
import ru.slavapmk.ignat.io.BotGptRequest
import ru.slavapmk.ignat.io.OpenaiAPI
import ru.slavapmk.ignat.io.db.ChatsTable
import ru.slavapmk.ignat.io.db.ContextsTable
import ru.slavapmk.ignat.io.db.MessagesTable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.commonmark.parser.Parser
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.adapter.rxjava3.RxJava3CallAdapterFactory
import retrofit2.converter.gson.GsonConverterFactory
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit


val settingsManager = SettingsManager()

fun isMarkdownValid(text: String): Boolean {
    val parser = Parser.builder().build()
    return try {
        parser.parse(text)
        true
    } catch (e: Exception) {
        false
    }
}

suspend fun main() {
    if (!settingsManager.readOrInit() || settingsManager.openai.isEmpty() || settingsManager.telegram.isEmpty()) {
        println("Insert tokens")
        return
    }

    val queue = ConcurrentLinkedQueue<BotGptRequest>()

    val httpLoggingInterceptor = HttpLoggingInterceptor()

    httpLoggingInterceptor.level = when (settingsManager.debug) {
        true -> HttpLoggingInterceptor.Level.BODY
        false -> HttpLoggingInterceptor.Level.NONE
    }

    val api: OpenaiAPI = Retrofit
        .Builder()
        .client(
            OkHttpClient
                .Builder()
                .addInterceptor(httpLoggingInterceptor)
                .readTimeout(120, TimeUnit.SECONDS)
                .build()
        )
        .baseUrl("https://api.openai.com")
        .addConverterFactory(GsonConverterFactory.create())
        .addCallAdapterFactory(RxJava3CallAdapterFactory.create())
        .build().create(OpenaiAPI::class.java)

    Database.connect(
        url = "jdbc:sqlite:storage/database.sqlite",
        driver = "org.sqlite.JDBC",
    )
    transaction {
        addLogger(StdOutSqlLogger)
        SchemaUtils.create(ChatsTable, MessagesTable, ContextsTable)
    }

    var poller: BotPoller? = null

    val consumer: (BotGptRequest) -> Unit = { gptRequest ->
        queue.add(gptRequest)

        poller?.bot?.editMessageText(
            chatId = ChatId.fromId(gptRequest.requestMessage.chat.id),
            text = Messages.processQueue(queue.size),
            parseMode = ParseMode.MARKDOWN,
            messageId = gptRequest.statusMessageId
        )
    }

    poller = BotPoller(settingsManager.telegram, consumer)

    poller.bot.startPolling()

    withContext(Dispatchers.IO) {
        while (true) {
            if (queue.isEmpty()) {
                Thread.sleep(500)
                continue
            }
            val request = queue.first()

            val startTime = System.currentTimeMillis()
            val typingStatusThread = Thread {
                while (true) {
                    poller.bot.sendChatAction(ChatId.fromId(request.requestMessage.chat.id), ChatAction.TYPING)
                    try {
                        Thread.sleep(4500)
                    } catch (e: InterruptedException) {
                        return@Thread
                    }
                }
            }

            typingStatusThread.start()

            var resultText = ""
            var retry = false
            var retryWait = 0L
            api
                .request("Bearer ${settingsManager.openai}", request.request)
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
                        resultText = if (it is HttpException) {
                            if (!settingsManager.debug) println(it.response())
                            when (it.code()) {
                                429 -> {
                                    retry = true
                                    retryWait = 60000
                                    ""
                                }

                                else -> Messages.errorInternet(it.code())
                            }
                        } else {
                            if (it is UnknownHostException) {
                                retry = true
                                Messages.retry
                            } else {
                                println(it)
                                Messages.error(it::class.java.name)
                            }
                        }
                    }
                )
            if (retry) {
                if (retryWait != 0L)
                    println("Retry in ${retryWait.toDouble() / 1000}s")
                Thread.sleep(retryWait)
                typingStatusThread.interrupt()
                continue
            }
            typingStatusThread.interrupt()

            queue.poll()
            for ((index, botGptRequest) in queue.withIndex()) {
                poller.bot.editMessageText(
                    messageId = botGptRequest.statusMessageId,
                    chatId = ChatId.fromId(botGptRequest.requestMessage.chat.id),
                    text = Messages.processQueue(index + 1),
                    parseMode = ParseMode.MARKDOWN
                )
            }

            Thread {
                try {
                    val i = (System.currentTimeMillis() - startTime) % 5000
                    Thread.sleep(if (i < 0) 0 else i)
                    poller.bot.editMessageText(
                        chatId = ChatId.fromId(request.requestMessage.chat.id),
                        text = resultText,
                        messageId = request.statusMessageId,
                        parseMode = if (isMarkdownValid(resultText)) ParseMode.MARKDOWN else null
                    )
                } catch (e: InterruptedException) {
                    println(e)
                    return@Thread
                }
            }.start()
        }
    }
}