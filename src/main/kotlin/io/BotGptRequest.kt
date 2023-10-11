package io

import com.github.kotlintelegrambot.entities.Message
import io.openai.OpenaiRequest

data class BotGptRequest (
    val request: OpenaiRequest,
    val requestMessage: Message,
    val context: Int
)