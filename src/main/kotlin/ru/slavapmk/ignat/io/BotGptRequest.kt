package ru.slavapmk.ignat.io

import com.github.kotlintelegrambot.entities.Message
import ru.slavapmk.ignat.io.openai.OpenaiRequest

data class BotGptRequest (
    val request: OpenaiRequest,
    val requestMessage: Message,
    val statusMessageId: Long,
    val contextId: Int
)