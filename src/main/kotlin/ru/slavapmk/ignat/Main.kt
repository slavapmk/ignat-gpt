package ru.slavapmk.ignat

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.Message
import com.github.kotlintelegrambot.entities.ParseMode
import com.github.kotlintelegrambot.network.Response
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import ru.slavapmk.ignat.io.db.ChatsTable
import ru.slavapmk.ignat.io.db.ContextsTable
import ru.slavapmk.ignat.io.db.MessagesTable
import ru.slavapmk.ignat.io.db.QueueTable
import ru.slavapmk.ignat.io.openai.OpenaiMessage
import ru.slavapmk.ignat.io.openai.OpenaiRequest
import java.io.IOException
import java.util.concurrent.TimeUnit


val settingsManager = SettingsManager()
val translator by lazy { Translator(settingsManager.debugMode.yandex) }

suspend fun main() {
    if (!settingsManager.readOrInit() || settingsManager.openaiToken.isEmpty() || settingsManager.telegramToken.isEmpty()) {
        println("Insert tokens")
        return
    }

    if (!settingsManager.enableYandex) println("Yandex translator disabled")

    val httpClient: OkHttpClient = OkHttpClient
        .Builder()
        .addInterceptor(
            with(HttpLoggingInterceptor {
                println("OPENAI  >>  $it")
            }) {
                level = when (settingsManager.debugMode.openai) {
                    true -> HttpLoggingInterceptor.Level.BODY
                    false -> HttpLoggingInterceptor.Level.NONE
                }
                this
            }
        ).apply {
            if (
                settingsManager.proxies.isEmpty())
                println("Proxy dis-activated")
            else {
                proxySelector(
                    SwitchProxySelector(
                        settingsManager.proxies
                    )
                )
                println("Proxy activated")
            }
        }
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(600, TimeUnit.SECONDS)
        .build()

    val openaiPoller = OpenaiProcessor(httpClient)

    Database.connect(
        url = "jdbc:sqlite:storage/database.sqlite",
        driver = "org.sqlite.JDBC",
    )
    transaction {
        addLogger(StdOutSqlLogger)
        SchemaUtils.create(ChatsTable, MessagesTable, ContextsTable, QueueTable)
        QueueTable.update({ QueueTable.status eq "in_work" }) {
            it[status] = "in_queue"
        }
    }

    val bot = BotPoller(settingsManager).bot
    bot.startPolling()

    coroutineScope {
        launch {
            loop(openaiPoller, bot)
        }
        launch {
            while (true) {
                val request = Request.Builder()
                    .url("https://api.openai.com/")
                    .get()
                    .build()
                try {
                    httpClient.newCall(request).execute()
                } catch (e: IOException) {
                    continue
                }
                delay(120000L)
            }
        }
        println("Launched")
    }
}

private fun loop(
    openaiPoller: OpenaiProcessor,
    bot: Bot
) {
    while (true) {
        val queueUnit = transaction {
            QueueTable.select { QueueTable.status eq "in_queue" }.firstOrNull()
        }
        if (queueUnit == null) {
            Thread.sleep(500)
            continue
        }

        val update = transaction {
            QueueTable.update({
                (QueueTable.id eq queueUnit[QueueTable.id]) and (QueueTable.status eq "in_queue")
            }) {
                it[status] = "in_work"
            }
        }
        if (update != 1)
            continue


        val prepareRequest = if (queueUnit[QueueTable.callbackMessage] != null)
            prepareRequest(queueUnit, bot)
        else
            OpenaiRequest(
                model = "gpt-3.5-turbo",
                temperature = 1,
                max_tokens = 1024,
                top_p = 1,
                frequency_penalty = 0,
                presence_penalty = 0,
                messages = listOf(
                    OpenaiMessage(
                        content = Messages.assistantPrompt(
                            queueUnit[QueueTable.chatName],
                            false
                        ),
                        role = "system"
                    ),
                    OpenaiMessage(
                        content = queueUnit[QueueTable.request],
                        name = with(translit(queueUnit[QueueTable.userName]).replace(Regex("[^a-zA-Z0-9_-]"), "")) {
                            if (length > 50) substring(0, 50) else this
                        },
                        role = "system"
                    ),
                ),
                translate = false,
                translateFrom = ""
            )
        val callback: (String, ParseMode?) -> Pair<retrofit2.Response<Response<Message>?>?, Exception?> =
            if (queueUnit[QueueTable.callbackMessage] != null) {
                { text, format ->
                    bot.editMessageText(
                        chatId = ChatId.fromId(queueUnit[QueueTable.chatId]),
                        messageId = queueUnit[QueueTable.callbackMessage],
                        text = text,
                        parseMode = format
                    )
                }
            } else {
                { text, format ->
                    bot.editMessageText(
                        text = text,
                        parseMode = format,
                        inlineMessageId = queueUnit[QueueTable.callbackInline]
                    )
                }
            }

        var resultText = ""
        val translate = prepareRequest.translate
        val translateTo = prepareRequest.translateFrom

        try {
            val process = openaiPoller.process(
                settingsManager, prepareRequest
            )

            if (process.error != null) {
                val errorMessage = when (process.error.code) {
                    401 -> Messages.restricted

                    400 -> when (process.error.type) {
                        "context_length_exceeded" -> Messages.tooLongContext
                        else -> Messages.errorInternet(process.error.code)
                    }

                    else -> Messages.errorInternet(process.error.code)
                }
                callback(errorMessage, ParseMode.MARKDOWN)
            } else resultText = process.choices.first().message.content
        } catch (e: Exception) {
            callback(Messages.error(e::class.java.name), ParseMode.MARKDOWN)
        }

        if (translate && settingsManager.enableYandex) resultText = translator.translate(
            resultText, translateTo, settingsManager.yandexToken, settingsManager.yandexAuthFolder
        ).translations.first().text


        transaction {
            QueueTable.update({
                QueueTable.id eq queueUnit[QueueTable.id]
            }) {
                it[status] = "done"
            }

            for ((index, resultRow) in QueueTable.select {
                QueueTable.status eq "in_queue"
            }.withIndex()) {
                if (resultRow[QueueTable.callbackMessage] != null)
                    bot.editMessageText(
                        chatId = ChatId.fromId(resultRow[QueueTable.chatId]),
                        messageId = resultRow[QueueTable.callbackMessage],
                        text = Messages.processQueue(index.toLong()),
                        parseMode = ParseMode.MARKDOWN
                    )
                else
                    bot.editMessageText(
                        inlineMessageId = resultRow[QueueTable.callbackInline],
                        text = Messages.processQueue(index.toLong()),
                        parseMode = ParseMode.MARKDOWN
                    )
            }
        }

        if (queueUnit[QueueTable.callbackInline] != null)
            resultText = "> ${queueUnit[QueueTable.request]}\n\n> $resultText"

        if (resultText.isNotBlank())
            callback(resultText, ParseMode.MARKDOWN)
                .apply {
                    if ((first?.code() ?: 0) == 400)
                        callback(resultText, null)
                }
    }
}

val russianAlph = "абвгдеёжзийклмнопрстуфхцчшщъыьэюяАБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯ".toCharArray()

fun prepareRequest(queueUnit: ResultRow, bot: Bot): OpenaiRequest {
    var chat: ResultRow? = null
    var contextId: Int? = null
    var contextUsage: Int? = null
    val requestMessages = mutableListOf<OpenaiMessage>()

    transaction {
        chat = ChatsTable.select { ChatsTable.id eq queueUnit[QueueTable.chatId] }.singleOrNull() ?: ChatsTable.insert {
            it[id] = queueUnit[QueueTable.chatId]
        }.resultedValues?.first()

        contextId = chat?.get(ChatsTable.contextId) ?: ContextsTable.insert {
            it[chatId] = queueUnit[QueueTable.chatId]
        }[ContextsTable.id]

        ChatsTable.update({ ChatsTable.id eq queueUnit[QueueTable.chatId] }) {
            it[ChatsTable.contextId] = contextId
        }

        contextUsage = ContextsTable.select { ContextsTable.id eq contextId!! }.single()[ContextsTable.usage]

        MessagesTable.select { MessagesTable.contextId eq contextId!! }.forEach {
            requestMessages.add(
                OpenaiMessage(
                    content = it[MessagesTable.text], role = it[MessagesTable.type], name = null
                )
            )
        }
    }

    var requestMessage = queueUnit[QueueTable.request]

    val translate = chat?.get(ChatsTable.autoTranslate) == true
    var translateFrom = ""

    if (translate && settingsManager.enableYandex) {
        translator.translate(
            requestMessage, "en", settingsManager.yandexToken, settingsManager.yandexAuthFolder
        ).apply {
            requestMessage = this.translations.first().text
            translateFrom = this.translations.first().detectedLanguageCode
        }
    }

    if (chat?.get(ChatsTable.darkMode) == true) {
        val prefix = if (
            translateFrom == "ru" ||
            requestMessage.count { russianAlph.contains(it) } * 100 / requestMessage.length > 90
        ) {
            "ОТВЕЧАЙ СТРОГО НА РУССКОМ ЯЗЫКЕ. "
        } else ""
        requestMessage = settingsManager.jailbreakPrompt.replace("\${PROMPT}", prefix + requestMessage)
    }

    val settingsMaxTokens = chat?.get(ChatsTable.maxTokens) ?: 1500

    val remains = 3500 - contextUsage!! - estimatedTokenLength(requestMessage)

    val maxTokens = if (remains > settingsMaxTokens) settingsMaxTokens
    else if (remains > 300) remains
    else {
        bot.editMessageText(
            chatId = ChatId.fromId(queueUnit[QueueTable.chatId]),
            messageId = queueUnit[QueueTable.callbackMessage],
            text = Messages.tooLongContext,
            parseMode = ParseMode.MARKDOWN
        )
        throw IllegalArgumentException()
    }

    val senderName = with(translit(queueUnit[QueueTable.userName]).replace(Regex("[^a-zA-Z0-9_-]"), "")) {
        if (length > 50) substring(0, 50) else this
    }

    if (requestMessages.isEmpty()) {
        requestMessages.add(
            OpenaiMessage(
                Messages.assistantPrompt(
                    queueUnit[QueueTable.chatName],
                    queueUnit[QueueTable.chatId].toString().startsWith("-100").not()
                ),
                senderName.ifEmpty { null },
                "system"
            )
        )
    }
    requestMessages.add(
        OpenaiMessage(
            requestMessage, senderName.ifEmpty { null }, "user"
        )
    )
    transaction {
        MessagesTable.insert {
            it[MessagesTable.senderName] = senderName
            it[type] = "user"
            it[MessagesTable.contextId] = contextId!!
            it[text] = requestMessage
        }
    }

    return OpenaiRequest(
        model = "gpt-3.5-turbo",
        temperature = 1,
        max_tokens = maxTokens,
        top_p = 1,
        frequency_penalty = 0,
        presence_penalty = 0,
        messages = requestMessages,
        translate = translate,
        translateFrom = translateFrom
    )
}