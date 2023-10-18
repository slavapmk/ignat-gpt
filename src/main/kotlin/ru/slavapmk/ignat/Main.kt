package ru.slavapmk.ignat

import com.github.kotlintelegrambot.entities.ChatAction
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.ParseMode
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.schedulers.Schedulers
import ru.slavapmk.ignat.io.BotGptRequest
import ru.slavapmk.ignat.io.db.ChatsTable
import ru.slavapmk.ignat.io.db.ContextsTable
import ru.slavapmk.ignat.io.db.MessagesTable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.commonmark.parser.Parser
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import retrofit2.HttpException
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
    val openaiPoller = OpenaiPoller()

    if (!settingsManager.readOrInit() || settingsManager.openai.isEmpty() || settingsManager.telegram.isEmpty()) {
        println("Insert tokens")
        return
    }

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
                        Thread.sleep(1000)
                    } catch (e: InterruptedException) {
                        return@Thread
                    }
                }
            }

            typingStatusThread.start()

            var retry = false
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
                    when (e.code()) {
                        429 -> {
                            retry = true
                            retryWait = 0
                            Messages.retry
                        }

                        401 -> {
                            retry = false
                            Messages.restricted
                        }

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

                        else -> {
                            Messages.errorInternet(e.code())
                        }
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

            queue.poll()
            for ((index, botGptRequest) in queue.withIndex()) {
                poller.bot.editMessageText(
                    messageId = botGptRequest.statusMessageId,
                    chatId = ChatId.fromId(botGptRequest.requestMessage.chat.id),
                    text = Messages.processQueue(index + 1),
                    parseMode = ParseMode.MARKDOWN
                )
            }

            typingStatusThread.interrupt()

            Completable
                .timer(
                    5000 - ((System.currentTimeMillis() - startTime) % 1000),
                    TimeUnit.MILLISECONDS,
                    Schedulers.newThread()
                )
                .subscribe {
                    poller.bot.editMessageText(
                        chatId = ChatId.fromId(request.requestMessage.chat.id),
                        text = resultText,
                        messageId = request.statusMessageId,
                        parseMode = if (isMarkdownValid(resultText)) ParseMode.MARKDOWN else null
                    )
                }
        }
    }
}