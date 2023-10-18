package ru.slavapmk.ignat.io.openai

data class OpenaiResponse(
    val choices: List<OpenaiResponseChoice>,
    val usage: OpenaiResponseUsage,
    val error: Error?
)
