package ru.slavapmk.ignat

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.dispatcher.callbackQuery
import com.github.kotlintelegrambot.dispatcher.command
import com.github.kotlintelegrambot.dispatcher.message
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.InlineKeyboardMarkup
import com.github.kotlintelegrambot.entities.Message
import com.github.kotlintelegrambot.entities.ParseMode
import com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton
import com.github.kotlintelegrambot.extensions.filters.Filter
import com.github.kotlintelegrambot.logging.LogLevel
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import ru.slavapmk.ignat.Messages.errorQueryEmpty
import ru.slavapmk.ignat.Messages.helpMessage
import ru.slavapmk.ignat.Messages.tooLongContext
import ru.slavapmk.ignat.io.BotGptRequest
import ru.slavapmk.ignat.io.db.ChatsTable
import ru.slavapmk.ignat.io.db.ContextsTable
import ru.slavapmk.ignat.io.db.MessagesTable
import ru.slavapmk.ignat.io.openai.OpenaiMessage
import ru.slavapmk.ignat.io.openai.OpenaiRequest
import java.io.IOException

class BotPoller(
    private val settings: SettingsManager,
    private val consumer: (BotGptRequest) -> Unit,
    private val translator: Translator?
) {
    val bot: Bot = bot {
        logLevel = when (settings.debug) {
            true -> LogLevel.All()
            false -> LogLevel.Error
        }
        token = settings.telegram
        dispatch {
            command("start") {
                bot.sendMessage(chatId = ChatId.fromId(message.chat.id), text = "This is test ignat2 bot!")
            }
            command("newcontext") {
                val usageAndChat = transaction {
                    val chat =
                        ChatsTable.select { ChatsTable.id eq message.chat.id }.singleOrNull() ?: ChatsTable.insert {
                            it[id] = message.chat.id
                        }.resultedValues?.get(0) ?: throw IOException("Db error")

                    val contextId = chat[ChatsTable.contextId]
                    if (contextId != null)
                        ContextsTable.select { ContextsTable.id eq contextId }.singleOrNull()?.let {
                            Pair(it[ContextsTable.usage], chat)
                        } ?: Pair(0, chat)
                    else Pair(0, chat)
                }
                transaction {
                    return@transaction ChatsTable.select { ChatsTable.id eq message.chat.id }.singleOrNull()
                }?.get(ChatsTable.lastSettings).apply {
                    if (this == null) return@apply
                    bot.editMessageText(
                        chatId = ChatId.fromId(message.chat.id),
                        messageId = this,
                        text = Messages.settings(usageAndChat.first, usageAndChat.second[ChatsTable.autoTranslate]),
                        replyMarkup = null,
                        parseMode = ParseMode.MARKDOWN,
                        disableWebPagePreview = true
                    )
                }

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
                val usageAndChat = transaction {
                    val chat =
                        ChatsTable.select { ChatsTable.id eq message.chat.id }.singleOrNull() ?: ChatsTable.insert {
                            it[id] = message.chat.id
                        }.resultedValues?.get(0) ?: throw IOException("Db error")

                    val contextId = chat[ChatsTable.contextId]
                    if (contextId != null)
                        ContextsTable.select { ContextsTable.id eq contextId }.singleOrNull()?.let {
                            Pair(it[ContextsTable.usage], chat)
                        } ?: Pair(0, chat)
                    else Pair(0, chat)
                }

                bot.sendMessage(
                    ChatId.fromId(message.chat.id),
                    Messages.settings(usageAndChat.first, usageAndChat.second[ChatsTable.autoTranslate]),
                    ParseMode.MARKDOWN,
                    true,
                    replyMarkup = InlineKeyboardMarkup.create(
                        listOf(
                            InlineKeyboardButton.CallbackData(
                                if (usageAndChat.second[ChatsTable.autoTranslate]) "Отключить автоперевод"
                                else "Включить автоперевод",
                                "switch_translator"
                            )
                        )
                    )
                ).getOrNull()?.let { message ->
                    transaction {
                        ChatsTable.update({ ChatsTable.id eq message.chat.id }) {
                            it[lastSettings] = message.messageId
                        }
                    }
                }
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
                val usageAndChat = transaction {
                    val chat =
                        ChatsTable.select { ChatsTable.id eq message.chat.id }.singleOrNull() ?: ChatsTable.insert {
                            it[id] = message.chat.id
                        }.resultedValues?.get(0) ?: throw IOException("Db error")

                    val contextId = chat[ChatsTable.contextId]
                    if (contextId != null)
                        ContextsTable.select { ContextsTable.id eq contextId }.singleOrNull()?.let {
                            Pair(it[ContextsTable.usage], chat)
                        } ?: Pair(0, chat)
                    else Pair(0, chat)
                }
                transaction {
                    return@transaction ChatsTable.select { ChatsTable.id eq message.chat.id }.singleOrNull()
                }?.get(ChatsTable.lastSettings).apply {
                    if (this == null) return@apply
                    bot.editMessageText(
                        chatId = ChatId.fromId(message.chat.id),
                        messageId = this,
                        text = Messages.settings(usageAndChat.first, usageAndChat.second[ChatsTable.autoTranslate]),
                        replyMarkup = null,
                        parseMode = ParseMode.MARKDOWN,
                        disableWebPagePreview = true
                    )
                }
                process(message)
            }
            callbackQuery("switch_translator") {
                val message = this.callbackQuery.message ?: throw IllegalStateException()
                val usageAndChat = transaction {
                    val newTranslateMode =
                        !ChatsTable.select { ChatsTable.id eq message.chat.id }.single()[ChatsTable.autoTranslate]
                    ChatsTable.update({ ChatsTable.id eq message.chat.id }) {
                        it[autoTranslate] = newTranslateMode
                    }

                    val chat =
                        ChatsTable.select { ChatsTable.id eq message.chat.id }.singleOrNull() ?: ChatsTable.insert {
                            it[id] = message.chat.id
                        }.resultedValues?.get(0) ?: throw IOException("Db error")

                    val contextId = chat[ChatsTable.contextId]
                    if (contextId != null)
                        ContextsTable.select { ContextsTable.id eq contextId }.singleOrNull()?.let {
                            Pair(it[ContextsTable.usage], chat)
                        } ?: Pair(0, chat)
                    else Pair(0, chat)
                }

                bot.editMessageText(
                    chatId = ChatId.fromId(message.chat.id),
                    messageId = message.messageId,
                    text = Messages.settings(usageAndChat.first, usageAndChat.second[ChatsTable.autoTranslate]),
                    parseMode = ParseMode.MARKDOWN,
                    disableWebPagePreview = true,
                    replyMarkup = InlineKeyboardMarkup.create(
                        listOf(
                            InlineKeyboardButton.CallbackData(
                                if (usageAndChat.second[ChatsTable.autoTranslate]) "Отключить автоперевод"
                                else "Включить автоперевод",
                                "switch_translator"
                            )
                        )
                    )
                )
            }
        }
    }

    private fun process(message: Message) {
        var requestMessage = message.text?.removePrefix(
            Messages.namedPrefixes.find { message.text?.startsWith(it) == true } ?: ""
        ) ?: ""
        var translateFrom = ""

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


        val translating = settings.translator && chat?.get(ChatsTable.autoTranslate) == true
        if (translating) {
            translator?.translate(
                requestMessage,
                "en",
                settingsManager.yandexToken,
                settings.yandexFolder
            ).apply {
                requestMessage = this?.translations?.first()?.text ?: requestMessage
                translateFrom = this?.translations?.first()?.detectedLanguageCode ?: ""
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
                request, message, sendMessage.get().messageId, contextId!!,
                translating, translateFrom
            )
        )
    }
}