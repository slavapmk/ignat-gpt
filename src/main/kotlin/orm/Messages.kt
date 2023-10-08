package orm

import org.ktorm.schema.Table
import org.ktorm.schema.int
import org.ktorm.schema.text

object Messages : Table<Nothing>("messages") {
    val contextId = int("context_id")
    val text = text("message")
}