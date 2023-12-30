package ru.slavapmk.ignat.io.openai

data class OpenaiMessage(
    val content: String,
    val name: String?=null,
    val role: String
)