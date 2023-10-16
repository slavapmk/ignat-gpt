package ru.slavapmk.ignat.io.openai

data class OpenaiResponseChoice(
    val finish_reason: String,
    val message: OpenaiMessage
)
