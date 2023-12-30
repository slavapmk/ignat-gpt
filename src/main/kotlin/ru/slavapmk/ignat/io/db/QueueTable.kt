package ru.slavapmk.ignat.io.db

import org.jetbrains.exposed.sql.Table

object QueueTable : Table("queue") {
    val id = long("id").autoIncrement()
    val chatId = long("chat_id")
    val chatName = text("chat_name")
    val userName = text("user_name")
    val request = text("request")
    val callbackMessage = long("callback_message").nullable()
    val callbackInline = text("callback_inline").nullable()
    val status = text("status").default("in_queue")
    override val primaryKey = PrimaryKey(id, name = "Queue_ID")
}