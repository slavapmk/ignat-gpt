package orm

import org.ktorm.schema.Table
import org.ktorm.schema.boolean
import org.ktorm.schema.int

object Chats : Table<Nothing>("chats") {
    val id = int("id")
    val contextId = int("context_id")
    val autoTranslate = boolean("auto_translate")
    val darkMode = boolean("dark_mode")
}