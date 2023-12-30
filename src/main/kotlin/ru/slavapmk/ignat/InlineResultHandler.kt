package ru.slavapmk.ignat

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.dispatcher.handlers.Handler
import com.github.kotlintelegrambot.entities.ChosenInlineResult
import com.github.kotlintelegrambot.entities.Update

data class InlineResultHandlerEnvironment(
    val bot: Bot,
    val update: Update,
    val inlineResult: ChosenInlineResult
)

class InlineResultHandler(
    private val handleUpdate: (suspend InlineResultHandlerEnvironment.() -> Unit)
) : Handler {
    override fun checkUpdate(update: Update): Boolean {
        return update.chosenInlineResult != null
    }

    override suspend fun handleUpdate(bot: Bot, update: Update) {
        checkNotNull(update.chosenInlineResult)

        handleUpdate(
            InlineResultHandlerEnvironment(
                bot,
                update,
                update.chosenInlineResult!!
            )
        )
    }
}