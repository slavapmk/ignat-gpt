import com.github.kotlintelegrambot.entities.ChatAction
import com.github.kotlintelegrambot.entities.ChatId
import io.BotGptRequest
import io.OpenaiAPI
import io.db.ChatsTable
import io.db.ContextsTable
import io.db.MessagesTable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.adapter.rxjava3.RxJava3CallAdapterFactory
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit


suspend fun main() {
    val settingsManager = SettingsManager()
    if (!settingsManager.readOrInit()) {
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
            text = "Ваш запрос в обработке. На этот момент запросов в очереди: ${queue.size}",
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
            val thread = Thread {
                while (true) {
                    poller.bot.sendChatAction(ChatId.fromId(request.requestMessage.chat.id), ChatAction.TYPING)
                    try {
                        Thread.sleep(4500)
                    } catch (e: InterruptedException) {
                        return@Thread
                    }
                }
            }

            thread.start()

            var resultText = ""
            var retry = false
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
                            when (it.code()) {
                                429 -> {
                                    retry = true
                                    ""
                                }
                                else -> "Получена неизвестная сетевая ошибка ${it.code()}. Просьба обратиться к администратору"
                            }
                        } else {
                            "Получена неизвестная внутренняя ошибка сервера. Просьба обратиться к администратору"
                        }
                        println(it)
                    }
                )

            if (retry) {
                Thread.sleep(9000)
                thread.interrupt()
                continue
            }
            thread.interrupt()


            Thread.sleep((System.currentTimeMillis() - startTime) % 5000 - 500)

            queue.poll()
            for ((index, botGptRequest) in queue.withIndex()) {
                poller.bot.editMessageText(
                    messageId = botGptRequest.statusMessageId,
                    chatId = ChatId.fromId(botGptRequest.requestMessage.chat.id),
                    text = "Ваш запрос в обработке. На этот момент запросов в очереди: ${index + 1}"
                )
            }
            poller.bot.editMessageText(
                chatId = ChatId.fromId(request.requestMessage.chat.id),
                text = resultText,
                messageId = request.statusMessageId,
            )
        }
    }
}