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
import ru.slavapmk.ignat.io.db.ChatsTable
import ru.slavapmk.ignat.io.db.ContextsTable
import java.io.IOException

private const val switchTranslatorId = "switch_translator"

class BotPoller(
    private val settings: SettingsManager,
    private val consumer: (QueueRequest) -> Unit
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
                hideSettings(message)

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
                    getUsage(message)
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
                                switchTranslatorId
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
                hideSettings(message)
                process(message)
            }
            callbackQuery(switchTranslatorId) {
                val message = this.callbackQuery.message ?: throw IllegalStateException()
                val usageAndChat = transaction {
                    val newTranslateMode =
                        !ChatsTable.select { ChatsTable.id eq message.chat.id }.single()[ChatsTable.autoTranslate]
                    ChatsTable.update({ ChatsTable.id eq message.chat.id }) {
                        it[autoTranslate] = newTranslateMode
                    }
                    getUsage(message)
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
                                switchTranslatorId
                            )
                        )
                    )
                )
            }
        }
    }

    private fun hideSettings(message: Message) {
        val usageAndChat = getUsage(message)
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
    }

    private fun getUsage(message: Message): Pair<Int, ResultRow> {
        return transaction {
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
        consumer(
            QueueRequest(
                message,
                requestMessage
            ) { _, _ -> 0 }
        )
    }
}