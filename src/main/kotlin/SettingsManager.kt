import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.io.IOException

val gson: Gson = GsonBuilder().setPrettyPrinting().create()

class SettingsManager {
    private var openaiToken: String = ""
    private var telegramToken: String = ""
    private var debugMode: Boolean = false
    val openai get() = openaiToken
    val telegram get() = telegramToken
    val debug get() = debugMode

    fun readOrInit(): Boolean {
        val file = File("settings.json").absoluteFile

        val parentFile = file.parentFile
        if (!file.exists() || !parentFile.exists()) {
            if (!parentFile.exists())
                if (!parentFile.mkdir())
                    if (!parentFile.mkdirs())
                        throw IOException()
            FileWriter(file).use {
                gson.toJson(this, it)
            }
            return false
        }

        val fromJson = FileReader(file).use {
            gson.fromJson(it, SettingsManager::class.java)
        }

        openaiToken = fromJson.openaiToken
        telegramToken = fromJson.telegramToken

        return true
    }


}