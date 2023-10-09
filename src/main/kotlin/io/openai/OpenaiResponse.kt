package io.openai

data class OpenaiResponse(
    val choices: List<OpenaiResponseChoice>,
    val usage: OpenaiResponseUsage
)
