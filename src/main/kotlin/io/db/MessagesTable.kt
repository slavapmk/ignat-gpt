package io.db

import org.jetbrains.exposed.sql.Table


object MessagesTable : Table("messages") {
    val id = integer("id").autoIncrement()
    val contextId = integer("context_id") references ContextsTable.id
    val type = varchar("type", length = 10)
    val senderName = varchar("sender_name", length = 100).nullable()
    val text = text("message")
    override val primaryKey = PrimaryKey(id, name = "Message_ID")
}