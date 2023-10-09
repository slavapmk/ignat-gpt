package io.openai

data class OpenaiMessage(
    val content: String,
    val name: String?,
    val role: String
)