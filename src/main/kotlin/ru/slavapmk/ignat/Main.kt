package ru.slavapmk.ignat

import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.ParseMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.StdOutSqlLogger
import org.jetbrains.exposed.sql.addLogger
import org.jetbrains.exposed.sql.transactions.transaction
import retrofit2.HttpException
import ru.slavapmk.ignat.io.BotGptRequest
import ru.slavapmk.ignat.io.db.ChatsTable
import ru.slavapmk.ignat.io.db.ContextsTable
import ru.slavapmk.ignat.io.db.MessagesTable
import ru.slavapmk.ignat.io.openai.OpenaiResponse
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentLinkedQueue


val settingsManager = SettingsManager()

suspend fun main() {
    if (!settingsManager.readOrInit() || settingsManager.openai.isEmpty() || settingsManager.telegram.isEmpty()) {
        println("Insert tokens")
        return
    }

    if (settingsManager.yandexToken.isEmpty())
        println("Yandex translator disabled")

    val openaiPoller = OpenaiPoller(settingsManager.debug)

    val queue = ConcurrentLinkedQueue<BotGptRequest>()

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

    val translator = Translator(settingsManager.debug)
    poller = BotPoller(settingsManager, consumer, translator)

    poller.bot.startPolling()

    withContext(Dispatchers.IO) {
        while (true) {
            if (queue.isEmpty()) {
                Thread.sleep(500)
                continue
            }
            val request = queue.first()

            var retry: Boolean
            var retryWait = 0L
            var resultText: String

            do {
                if (retryWait != 0L)
                    println("Retry in ${retryWait.toDouble() / 1000}s")
                Thread.sleep(retryWait)

                resultText = try {
                    val resp = openaiPoller.process(
                        settingsManager.openai,
                        request
                    )
                    retry = false
                    resp
                } catch (e: HttpException) {
                    retry = false
                    val toString = e.response()?.errorBody()?.string()
                    println(toString)
                    val error = gson.fromJson(toString, OpenaiResponse::class.java).error
                    when (e.code()) {
                        429 -> {
                            retry = true
                            retryWait = 5000
                            Messages.retry
                        }

                        401 -> Messages.restricted

                        503 -> {
                            retry = true
                            retryWait = 1000
                            Messages.overload
                        }

                        500 -> {
                            retry = true
                            retryWait = 1000
                            Messages.overload
                        }

                        400 ->
                            if (error?.type == "context_length_exceeded")
                                Messages.tooLongContext
                            else
                                Messages.errorInternet(e.code())

                        else -> Messages.errorInternet(e.code())
                    }
                } catch (e: UnknownHostException) {
                    retry = true
                    retryWait = 5000
                    Messages.retry
                } catch (e: Exception) {
                    retry = false
                    Messages.error(e::class.java.name)
                }
            } while (retry)

            if (request.translate)
                resultText = translator.translate(
                    resultText,
                    request.translateTo,
                    settingsManager.yandexToken,
                    settingsManager.yandexFolder
                ).translations.first().text

            queue.poll()
            for ((index, botGptRequest) in queue.withIndex()) {
                poller.bot.editMessageText(
                    messageId = botGptRequest.statusMessageId,
                    chatId = ChatId.fromId(botGptRequest.requestMessage.chat.id),
                    text = Messages.processQueue(index + 1),
                    parseMode = ParseMode.MARKDOWN
                )
            }
            poller.bot.editMessageText(
                chatId = ChatId.fromId(request.requestMessage.chat.id),
                text = resultText,
                messageId = request.statusMessageId,
                parseMode = ParseMode.MARKDOWN
            ).first?.code()?.apply {
                if (this == 400)
                    poller.bot.editMessageText(
                        chatId = ChatId.fromId(request.requestMessage.chat.id),
                        text = resultText,
                        messageId = request.statusMessageId,
                        parseMode = null
                    )
            }
        }
    }
}