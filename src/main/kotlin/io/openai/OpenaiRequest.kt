package io.openai

data class OpenaiRequest(
    val frequency_penalty: Int,
    val max_tokens: Int,
    val messages: List<OpenaiMessage>,
    val model: String,
    val presence_penalty: Int,
    val temperature: Int,
    val top_p: Int
)