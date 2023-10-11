import com.github.kotlintelegrambot.entities.ChatId
import io.BotGptRequest
import io.OpenaiAPI
import io.db.ChatsTable
import io.db.ContextsTable
import io.db.MessagesTable
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import retrofit2.Retrofit
import retrofit2.adapter.rxjava3.RxJava3CallAdapterFactory
import retrofit2.converter.gson.GsonConverterFactory
import java.util.*
import java.util.concurrent.TimeUnit

const val openaiToken = "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"
const val telegramToken = "6271637366:AAGi0AdJ8dTIK29RlsO3kR-9ezdNRak39vM"
const val debugMode = true

fun main() {
    val queue: Queue<String> = LinkedList()

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

    var poller: BotPoller? = null

    val consumer: (BotGptRequest) -> Unit = { gptRequest ->
        api.request(
            "Bearer $openaiToken",
            gptRequest.request
        ).subscribe(
            { resp ->
                val responseChoice = resp.choices[0]

                transaction {
                    MessagesTable.insert {
                        it[type] = "assistant"
                        it[contextId] = gptRequest.context
                        it[text] = responseChoice.message.content
                    }

                    ContextsTable.update({ ContextsTable.id eq gptRequest.context }) {
                        it[usage] = resp.usage.total_tokens
                    }
                }

                poller?.bot?.sendMessage(
                    chatId = ChatId.fromId(gptRequest.requestMessage.chat.id),
                    text = responseChoice.message.content
                )
            },
            {
                println("Error $it")
            }
        )
    }

    poller = BotPoller(consumer)

    poller.bot.startPolling()
}