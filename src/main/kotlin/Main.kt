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

val openaiToken = "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"

fun main() {
    val httpLoggingInterceptor = HttpLoggingInterceptor()

    httpLoggingInterceptor.level = when (true) {
        true -> HttpLoggingInterceptor.Level.BODY
        false -> HttpLoggingInterceptor.Level.NONE
    }

    val api: OpenaiAPI = Retrofit
        .Builder()
        .client(OkHttpClient.Builder().addInterceptor(httpLoggingInterceptor).build())
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
        token = "6271637366:AAGi0AdJ8dTIK29RlsO3kR-9ezdNRak39vM"
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
                            it[ChatsTable.id] = message.chat.id
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

                    val requestMessages = mutableListOf<OpenaiMessage>()
                    MessagesTable.select { MessagesTable.contextId eq contextId }.forEach {
                        requestMessages.add(
                            OpenaiMessage(
                                content = it[MessagesTable.text],
                                role = it[MessagesTable.type],
                                name = null
//                                name = if (it[MessagesTable.type] == "user") it[MessagesTable.senderName] else null
                            )
                        )
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
                        max_tokens = 1000,
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
                val result = bot.sendMessage(chatId = ChatId.fromId(message.chat.id), text = "This is test ignat2 bot!")
                result.fold({}, {})
            }
        }
    }
    bot.startPolling()
}