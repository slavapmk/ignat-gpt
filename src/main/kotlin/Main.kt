import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.dispatcher.command
import com.github.kotlintelegrambot.dispatcher.message
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.extensions.filters.Filter
import com.github.kotlintelegrambot.logging.LogLevel
import io.OpenaiAPI
import io.openai.OpenaiMessage
import io.openai.OpenaiRequest
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import io.db.ChatsTable
import io.db.ContextsTable
import io.db.MessagesTable
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.adapter.rxjava3.RxJava3CallAdapterFactory
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

const val openaiToken = ""
const val telegramToken = ""
const val debugMode = true

fun main() {
    val httpLoggingInterceptor = HttpLoggingInterceptor()

    httpLoggingInterceptor.level = when (debugMode) {
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
        url = "jdbc:sqlite:database.sqlite",
        driver = "org.sqlite.JDBC",
    )
    transaction {
        addLogger(StdOutSqlLogger)
        SchemaUtils.create(ChatsTable, MessagesTable, ContextsTable)
    }

    val bot = bot {
        logLevel = LogLevel.Error
        token = telegramToken
        dispatch {
            message(Filter.Custom {
                return@Custom (chat.type == "private" || text?.startsWith("Игнат, ") == true || text?.startsWith("Ignat, ") == true)
            }) {
                val requestMessage = when {
                    message.text?.startsWith("Игнат, ") == true -> message.text?.removePrefix("Игнат, ")
                    message.text?.startsWith("Ignat, ") == true -> message.text?.removePrefix("Игнат, ")
                    else -> message.text
                } ?: ""

                transaction {
                    val chat = ChatsTable.select { ChatsTable.id eq message.chat.id }.singleOrNull()

                    if (chat == null)
                        ChatsTable.insert {
                            it[id] = message.chat.id
                        }

                    var contextId =
                        chat?.get(ChatsTable.contextId)

                    if (contextId == null) {
                        contextId = ContextsTable.insert {
                            it[chatId] = message.chat.id
                        }[ContextsTable.id]
                        ChatsTable.update({ ChatsTable.id eq message.chat.id }) {
                            it[ChatsTable.contextId] = contextId
                        }
                    }

                    val contextUsage =
                        ContextsTable.select { ContextsTable.id eq contextId }.single()[ContextsTable.usage]

                    val requestMessages = mutableListOf<OpenaiMessage>()
                    MessagesTable.select { MessagesTable.contextId eq contextId }.forEach {
                        requestMessages.add(
                            OpenaiMessage(
                                content = it[MessagesTable.text],
                                role = it[MessagesTable.type],
                                name = null
                            )
                        )
                    }

                    val remains = 3500 - contextUsage

                    val maxTokens =
                        if (remains > 900) 900
                        else if (remains > 100) remains
                        else {
                            bot.sendMessage(
                                chatId = ChatId.fromId(message.chat.id),
                                text = "Похоже, ваш диалог слишком длинный. Чтобы начать его заного воспользуйтесь командой /newcontext"
                            )
                            return@transaction
                        }

                    if (requestMessages.isEmpty())
                        requestMessages.add(
                            OpenaiMessage(
                                "You are telegram chatbot",
                                null,
                                "system"
                            )
                        )
                    val senderName = (message.senderChat?.firstName ?: "") + " " + (message.senderChat?.lastName ?: "")
                    requestMessages.add(
                        OpenaiMessage(
                            requestMessage,
                            null,
                            "user"
                        )
                    )
                    val request = OpenaiRequest(
                        model = "gpt-3.5-turbo",
                        temperature = 1,
                        max_tokens = maxTokens,
                        top_p = 1,
                        frequency_penalty = 0,
                        presence_penalty = 0,
                        messages = requestMessages
                    )
                    MessagesTable.insert {
                        it[MessagesTable.senderName] = senderName
                        it[type] = "user"
                        it[MessagesTable.contextId] = contextId
                        it[text] = requestMessage
                    }
                    api.request(
                        "Bearer $openaiToken",
                        request
                    ).subscribe(
                        { resp ->
                            println("Success $resp")
                            val responseChoice = resp.choices[0]

                            transaction {
                                MessagesTable.insert {
                                    it[type] = "assistant"
                                    it[MessagesTable.contextId] = contextId
                                    it[text] = responseChoice.message.content
                                }

                                ContextsTable.update({ ContextsTable.id eq contextId }) {
                                    it[usage] = resp.usage.total_tokens
                                }
                            }

                            bot.sendMessage(
                                chatId = ChatId.fromId(message.chat.id),
                                text = responseChoice.message.content
                            )
                        },
                        {
                            println("Error $it")
                        }
                    )
                }
            }
            command("start") {
                bot.sendMessage(chatId = ChatId.fromId(message.chat.id), text = "This is test ignat2 bot!")
            }
            command("newcontext") {
                transaction {
                    ChatsTable.update({ ChatsTable.id eq message.chat.id }) {
                        it[contextId] = null
                    }
                }
                bot.sendMessage(chatId = ChatId.fromId(message.chat.id), text = "Диалог сброшен")
            }
        }
    }
    bot.startPolling()
}