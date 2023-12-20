package ru.slavapmk.ignat

import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.ParseMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import ru.slavapmk.ignat.io.db.ChatsTable
import ru.slavapmk.ignat.io.db.ContextsTable
import ru.slavapmk.ignat.io.db.MessagesTable
import ru.slavapmk.ignat.io.openai.OpenaiMessage
import ru.slavapmk.ignat.io.openai.OpenaiRequest
import java.util.concurrent.ConcurrentLinkedQueue


val settingsManager = SettingsManager()
val translator by lazy { Translator(settingsManager.debugMode) }

suspend fun main() {
    if (!settingsManager.readOrInit() || settingsManager.openaiToken.isEmpty() || settingsManager.telegramToken.isEmpty()) {
        println("Insert tokens")
        return
    }

    if (!settingsManager.enableYandex)
        println("Yandex translator disabled")

    val openaiPoller = OpenaiPoller(settingsManager.debugMode, settingsManager.proxies)

    val queue = ConcurrentLinkedQueue<QueueRequest>()

    Database.connect(
        url = "jdbc:sqlite:storage/database.sqlite",
        driver = "org.sqlite.JDBC",
    )
    transaction {
        addLogger(StdOutSqlLogger)
        SchemaUtils.create(ChatsTable, MessagesTable, ContextsTable)
    }

    var poller: BotPoller? = null

    val consumer: (QueueRequest) -> Unit = { queueRequest ->
        val chatId = ChatId.fromId(queueRequest.message.chat.id)
        val id = poller?.bot?.sendMessage(
            chatId = chatId,
            text = Messages.processQueue(queue.size),
            parseMode = ParseMode.MARKDOWN,
        )?.get()?.messageId

        queueRequest.submitCallBack = { text, parseMode ->
            poller?.bot?.editMessageText(
                chatId = chatId,
                text = text,
                messageId = id,
                parseMode = parseMode
            )?.first?.code()!!
        }

        queue.add(queueRequest)
    }

    poller = BotPoller(settingsManager, consumer)

    poller.bot.startPolling()

    withContext(Dispatchers.IO) {
        while (true) {
            if (queue.isEmpty()) {
                Thread.sleep(500)
                continue
            }
            val request = queue.first()

            val prepareRequest = prepareRequest(request)
            var resultText = ""
            val translate = prepareRequest.translate
            val translateTo = prepareRequest.translateFrom

            try {
                val process = openaiPoller.process(
                    settingsManager,
                    prepareRequest
                )

                if (process.error != null) {
                    val errorMessage = when (process.error.code) {
                        401 -> Messages.restricted

                        400 ->
                            if (process.error.type == "context_length_exceeded")
                                Messages.tooLongContext
                            else
                                Messages.errorInternet(process.error.code)

                        else -> Messages.errorInternet(process.error.code)
                    }
                    request.submitCallBack(errorMessage, ParseMode.MARKDOWN)
                } else
                    resultText = process.choices.first().message.content
            } catch (e: Exception) {
                request.submitCallBack(Messages.error(e::class.java.name), ParseMode.MARKDOWN)
            }

            if (translate)
                resultText = translator.translate(
                    resultText,
                    translateTo,
                    settingsManager.yandexToken,
                    settingsManager.yandexAuthFolder
                ).translations.first().text

            queue.poll()
            for ((i, _) in queue.withIndex()) {
                request.submitCallBack(Messages.processQueue(i), ParseMode.MARKDOWN)
            }

            if (resultText.isNotBlank())
                request.submitCallBack(resultText, ParseMode.MARKDOWN).apply {
                    if (this == 400) request.submitCallBack(resultText, null)
                }
        }
    }
}


fun prepareRequest(queue: QueueRequest): OpenaiRequest {
    var chat: ResultRow? = null
    var contextId: Int? = null
    var contextUsage: Int? = null
    val requestMessages = mutableListOf<OpenaiMessage>()

    transaction {
        chat = ChatsTable.select { ChatsTable.id eq queue.message.chat.id }.singleOrNull() ?: ChatsTable.insert {
            it[id] = queue.message.chat.id
        }.resultedValues?.first()

        contextId = chat?.get(ChatsTable.contextId) ?: ContextsTable.insert {
            it[chatId] = queue.message.chat.id
        }[ContextsTable.id]

        ChatsTable.update({ ChatsTable.id eq queue.message.chat.id }) {
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

    var requestMessage = queue.requestMessage

    val translate = settingsManager.enableYandex && chat?.get(ChatsTable.autoTranslate) == true
    var translateFrom = ""

    if (translate) {
        translator.translate(
            requestMessage,
            "en",
            settingsManager.yandexToken,
            settingsManager.yandexAuthFolder
        ).apply {
            requestMessage = this.translations.first().text
            translateFrom = this.translations.first().detectedLanguageCode
        }
    }

    if (contextUsage == null || contextId == null || chat == null) {
        queue.submitCallBack(Messages.errorDb, ParseMode.MARKDOWN)
    }

    val settingsMaxTokens = chat?.get(ChatsTable.maxTokens) ?: 1500

    val remains = 3500 - contextUsage!! - estimatedTokenLength(requestMessage)

    val maxTokens = if (remains > settingsMaxTokens) settingsMaxTokens
    else if (remains > 300) remains
    else {
        queue.submitCallBack(Messages.tooLongContext, ParseMode.MARKDOWN)
        throw IllegalArgumentException()
    }


    var name = queue.message.from?.firstName ?: ""
    queue.message.from?.lastName?.let {
        name += "_${it}"
    }

    val senderName = with(translit(name).replace(Regex("[^a-zA-Z0-9_-]"), "")) {
        if (length > 50) substring(0, 50) else this
    }

    if (requestMessages.isEmpty()) {
        val isPm = queue.message.chat.type == "private"
        requestMessages.add(
            OpenaiMessage(
                Messages.assistantPrompt(
                    when (isPm) {
                        true -> "${queue.message.chat.firstName} ${queue.message.chat.lastName ?: ""}".trim()
                        false -> queue.message.chat.title ?: ""
                    },
                    isPm
                ),
                null,
                "system"
            )
        )
    }
    requestMessages.add(
        OpenaiMessage(
            requestMessage,
            senderName.ifEmpty { null },
            "user"
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