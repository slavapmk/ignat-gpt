package ru.slavapmk.ignat

import com.github.kotlintelegrambot.entities.Message
import com.github.kotlintelegrambot.entities.ParseMode

data class QueueRequest(
    val message: Message,
    val requestMessage: String,
    var submitCallBack: (text: String, parseMode: ParseMode?) -> Int
)