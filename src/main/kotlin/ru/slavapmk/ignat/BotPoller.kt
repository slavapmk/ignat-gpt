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

val helpMessage = """
                            *Это бот-клиент для OpenAI GPT-3.5 (ChatGPT)* - умной текстовой нейросети. Всё что тебе нужно - это отправить сообщение, и я тебе на него отвечу. Я общаюсь в пределе одного диалога, то есть у меня есть своеобразная "памать". Если нужно начать новый диалог, то воспользуйся командой /newcontext.
                            *Пример:* `Как дела?`
                            Если вы используете бота в групповом чате, то все запросы выполняйте, обращаясь по моему имени - Игнат 
                            *Пример:* `Игнат, Как дела?`
                            *Примечание*: максимальный размер диалога примерно 3500 токенов. Где 1 токен - примерно 4 латинских символа или 1 любой другой, какой как русский, китайский, или символы пунктуации.
                            (Вы можете использовать команду /profile чтобы узнать, сколько потрачено).
                            Если ваш диалог слишком длиный, мы вам сообщим, в вы - очищайте его с помощью /newcontext
                            [Поддержать автора](https://www.donationalerts.com/r/slavapmk)
                        """.trimIndent()

class BotPoller(
    private val telegramToken: String,
    private val consumer: (BotGptRequest) -> Unit
) {
    val bot: Bot = bot {
        logLevel = LogLevel.Error
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
                    "*Диалог сброшен*. Теперь бот не помнит о чём вы говорили, но у вас снова доступен весь запас в 3500 токенов.",
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

                val text = arrayOf(
                    "*Язык*: Исходный (функция временно отключена)",
                    "*DarkGPT*: Выключён (функция временно отключена)",
                    "*Размер диалога*: $usage из 3500 токенов (осталось ${3500 - usage})"
                ).joinToString("\n")

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
                text != null && !text!!.startsWith("/") && (chat.type == "private" || text!!.startsWith("Игнат, ") || text!!.startsWith(
                    "Ignat, "
                ))
            }) {
                process(message)
            }
        }
    }

    private fun process(message: Message) {
        val requestMessage = when {
            message.text?.startsWith("Игнат, ") == true -> message.text?.removePrefix("Игнат, ")
            message.text?.startsWith("Ignat, ") == true -> message.text?.removePrefix("Игнат, ")
            else -> message.text
        } ?: ""
        if (requestMessage.isEmpty()) {
            bot.sendMessage(
                chatId = ChatId.fromId(message.chat.id),
                text = "Вы не можете использовать пустой запрос"
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
                text = "Похоже, на стороне бота произошла ошибка доступа к БД, пожалуйста подождите или напишите администратору"
            )
        }

        val settingsMaxTokens = chat?.get(ChatsTable.maxTokens) ?: 1500

        val remains = 3500 - contextUsage!!

        val maxTokens = if (remains > settingsMaxTokens) settingsMaxTokens
        else if (remains > 300) remains
        else {
            bot.sendMessage(
                chatId = ChatId.fromId(message.chat.id),
                text = "Похоже, ваш диалог слишком длинный. Чтобы начать его заного воспользуйтесь командой /newcontext"
            )
            return
        }

        if (requestMessages.isEmpty()) requestMessages.add(
            OpenaiMessage(
                "You are telegram chatbot", null, "system"
            )
        )

        var name = message.from?.firstName ?: ""
        message.from?.lastName?.let {
            name += "_${it}"
        }

        val senderName = with(name.replace(Regex("[^a-zA-Z0-9_-]"), "")) {
            if (length > 50) substring(0, 50) else this
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
            text = "Ваш запрос в обработке",
            replyToMessageId = message.messageId
        )

        consumer(
            BotGptRequest(
                request, message, sendMessage.get().messageId, contextId!!
            )
        )
    }
}