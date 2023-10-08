import org.ktorm.database.Database
import org.ktorm.logging.ConsoleLogger
import org.ktorm.logging.LogLevel
import org.ktorm.schema.*

object Departments : Table<Nothing>("t_department") {
    val id = int("id").primaryKey()
    val name = varchar("name")
    val location = varchar("location")
}

object Employees : Table<Nothing>("t_employee") {
    val id = int("id").primaryKey()
    val name = varchar("name")
    val job = varchar("job")
    val managerId = int("manager_id")
    val hireDate = date("hire_date")
    val salary = long("salary")
    val departmentId = int("department_id")
}
fun main() {
    val database = Database.connect(
        url = "jdbc:sqlite:database.sqlite",
        driver = "org.sqlite.JDBC",
        logger = ConsoleLogger(threshold = LogLevel.DEBUG)
    )

    println()
//database.ch
//    database.insert(Chats) {
//        set(it.id, 1)
//        set(it.autoTranslate, true)
//        set(it.contextId, 1)
//        set(it.darkMode, false)
//    }

//    val bot = bot {
//        logLevel = LogLevel.Error
//        token = "6271637366:AAGi0AdJ8dTIK29RlsO3kR-9ezdNRak39vM"
//        dispatch {
//            message(Filter.Custom {
//                return@Custom (chat.type == "private" || text?.startsWith("Игнат, ") == true || text?.startsWith("Ignat, ") == true)
//            }) {
//                val request = when {
//                    message.text?.startsWith("Игнат, ") == true -> message.text?.removePrefix("Игнат, ")
//                    message.text?.startsWith("Ignat, ") == true -> message.text?.removePrefix("Игнат, ")
//                    else -> message.text
//                }
//
//            }
//            command("start") {
////                println("Message")
//                val result = bot.sendMessage(chatId = ChatId.fromId(message.chat.id), text = "Hi there!")
//                result.fold({
////                    println("Message sent")
//                }, {
////                    println("Message not sent")
//                })
//            }
//        }
//    }
//    bot.startPolling()
}