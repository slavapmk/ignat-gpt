package ru.slavapmk.ignat

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.dispatcher.command
import com.github.kotlintelegrambot.dispatcher.message
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.Message
import com.github.kotlintelegrambot.entities.ParseMode
import com.github.kotlintelegrambot.extensions.filters.Filter
import com.github.kotlintelegrambot.logging.LogLevel
import ru.slavapmk.ignat.io.BotGptRequest
import ru.slavapmk.ignat.io.db.ChatsTable
import ru.slavapmk.ignat.io.db.ContextsTable
import ru.slavapmk.ignat.io.db.MessagesTable
import ru.slavapmk.ignat.io.openai.OpenaiMessage
import ru.slavapmk.ignat.io.openai.OpenaiRequest
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import ru.slavapmk.ignat.Messages.errorQueryEmpty
import ru.slavapmk.ignat.Messages.helpMessage
import ru.slavapmk.ignat.Messages.tooLongContext

class BotPoller(
    private val telegramToken: String,
    private val consumer: (BotGptRequest) -> Unit
) {
    val bot: Bot = bot {
        logLevel = when (settingsManager.debug) {
            true -> LogLevel.All()
            false -> LogLevel.Error
        }
        token = telegramToken
        dispatch {
            command("start") {
                bot.sendMessage(chatId = ChatId.fromId(message.chat.id), text = "This is test ignat2 bot!")
            }
            command("newcontext") {
                transaction {
                    ChatsTable.update({ ChatsTable.id eq message.chat.id }) {
                        it[contextId] = null
                    }
                }
                bot.sendMessage(
                    ChatId.fromId(message.chat.id),
                    Messages.reset,
                    ParseMode.MARKDOWN,
                    true
                )
            }
            command("profile") {
                val usage = transaction {
                    val contextId =
                        ChatsTable.select { ChatsTable.id eq message.chat.id }.singleOrNull()?.get(ChatsTable.contextId)
                    if (contextId != null)
                        ContextsTable.select { ContextsTable.id eq contextId }.singleOrNull()?.get(ContextsTable.usage)
                            ?: 0
                    else 0
                }

                val text = Messages.settings(usage)

                bot.sendMessage(
                    ChatId.fromId(message.chat.id),
                    text,
                    ParseMode.MARKDOWN,
                    true
                )
            }
            command("help") {
                bot.sendMessage(
                    ChatId.fromId(message.chat.id),
                    helpMessage,
                    ParseMode.MARKDOWN,
                    true
                )
            }
            command("start") {
                bot.sendMessage(
                    ChatId.fromId(message.chat.id),
                    helpMessage,
                    ParseMode.MARKDOWN,
                    true
                )
            }
            message(Filter.Custom {
                text != null && !text!!.startsWith("/") && (chat.type == "private" || Messages.namedPrefixes.any {
                    text?.startsWith(
                        it
                    ) == true
                })
            }) {
                process(message)
            }
        }
    }

    private fun process(message: Message) {
        val requestMessage = message.text?.removePrefix(
            Messages.namedPrefixes.find { message.text?.startsWith(it) == true } ?: ""
        ) ?: ""

        if (requestMessage.isEmpty()) {
            bot.sendMessage(
                chatId = ChatId.fromId(message.chat.id),
                text = errorQueryEmpty
            )
            return
        }

        var chat: ResultRow? = null
        var contextId: Int? = null
        var contextUsage: Int? = null
        val requestMessages = mutableListOf<OpenaiMessage>()

        transaction {
            chat = ChatsTable.select { ChatsTable.id eq message.chat.id }.singleOrNull() ?: ChatsTable.insert {
                it[id] = message.chat.id
            }.resultedValues?.first()

            contextId = chat?.get(ChatsTable.contextId) ?: ContextsTable.insert {
                it[chatId] = message.chat.id
            }[ContextsTable.id]

            ChatsTable.update({ ChatsTable.id eq message.chat.id }) {
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

        if (contextUsage == null || contextId == null || chat == null) {
            bot.sendMessage(
                chatId = ChatId.fromId(message.chat.id),
                text = Messages.errorDb,
                parseMode = ParseMode.MARKDOWN
            )
        }

        val settingsMaxTokens = chat?.get(ChatsTable.maxTokens) ?: 1500

        val remains = 3500 - contextUsage!! - estimatedTokenLength(requestMessage)

        val maxTokens = if (remains > settingsMaxTokens) settingsMaxTokens
        else if (remains > 300) remains
        else {
            bot.sendMessage(
                chatId = ChatId.fromId(message.chat.id),
                text = tooLongContext,
                parseMode = ParseMode.MARKDOWN
            )
            return
        }


        var name = message.from?.firstName ?: ""
        message.from?.lastName?.let {
            name += "_${it}"
        }

        val senderName = with(translit(name).replace(Regex("[^a-zA-Z0-9_-]"), "")) {
            if (length > 50) substring(0, 50) else this
        }

        if (requestMessages.isEmpty()) {
            val isPm = message.chat.type == "private"
            requestMessages.add(
                OpenaiMessage(
                    Messages.assistantPrompt(
                        when (isPm) {
                            true -> "${message.chat.firstName} ${message.chat.lastName ?: ""}".trim()
                            false -> message.chat.title ?: ""
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
        val request = OpenaiRequest(
            model = "gpt-3.5-turbo",
            temperature = 1,
            max_tokens = maxTokens,
            top_p = 1,
            frequency_penalty = 0,
            presence_penalty = 0,
            messages = requestMessages
        )
        transaction {
            MessagesTable.insert {
                it[MessagesTable.senderName] = senderName
                it[type] = "user"
                it[MessagesTable.contextId] = contextId!!
                it[text] = requestMessage
            }
        }

        val sendMessage = bot.sendMessage(
            chatId = ChatId.fromId(message.chat.id),
            text = Messages.process,
            replyToMessageId = message.messageId,
            parseMode = ParseMode.MARKDOWN
        )

        consumer(
            BotGptRequest(
                request, message, sendMessage.get().messageId, contextId!!
            )
        )
    }
}