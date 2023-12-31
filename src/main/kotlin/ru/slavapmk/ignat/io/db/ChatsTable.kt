package ru.slavapmk.ignat.io.db

import org.jetbrains.exposed.sql.Table

object ChatsTable : Table("chats") {
    val id = long("user_id")
    val contextId = (integer("context_id") references ContextsTable.id).nullable()
    val autoTranslate = bool("auto_translate").default(false)
    val darkMode = bool("dark_mode").default(false)
    val maxTokens = integer("max_tokens").default(1500)
    val lastSettings = long("last_settings").nullable()
    override val primaryKey = PrimaryKey(id, name = "Chat_ID")
}