package io.db

import org.jetbrains.exposed.sql.Table

object ContextsTable : Table("contexts") {
    val id = integer("id").autoIncrement()
    val chatId = long("context_id") references ChatsTable.id
    val usage = integer("usage").default(0)
    override val primaryKey = PrimaryKey(id, name = "Context_ID")
}