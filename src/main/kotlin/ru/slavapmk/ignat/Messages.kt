package ru.slavapmk.ignat

object Messages {
    val reset =
        "*Диалог сброшен*\nТеперь бот не помнит о чём вы говорили, но у вас снова доступен весь запас в 3500 токенов."
    val botName = "Ignat"
    val helpMessage = """
                            *Это бот-клиент для OpenAI GPT-3.5 (ChatGPT)* - умной текстовой нейросети. Всё что тебе нужно - это отправить сообщение, и я тебе на него отвечу. Я общаюсь в пределе одного диалога, то есть у меня есть своеобразная "памать". Если нужно начать новый диалог, то воспользуйся командой /newcontext.
                            *Пример:* `Как дела?`
                            Если вы используете бота в групповом чате, то все запросы выполняйте, обращаясь по моему имени - Игнат 
                            *Пример:* `Игнат, Как дела?`
                            *Примечание*: максимальный размер диалога примерно 3500 токенов. Где 1 токен - примерно 4 латинских символа или 1 любой другой, какой как русский, китайский, или символы пунктуации.
                            (Вы можете использовать команду /profile чтобы узнать, сколько потрачено).
                            Если ваш диалог слишком длиный, мы вам сообщим, в вы - очищайте его с помощью /newcontext
                            [Поддержать автора](https://www.donationalerts.com/r/slavapmk)
                        """.trimIndent()

    val namedPrefixes = arrayOf("Ignat, ", "Игнат, ")
    val errorQueryEmpty = "Вы не можете использовать пустой запрос"

    val tooLongContext = "*Ваш диалог слишком длинный*\n" +
            "Чтобы начать его заного воспользуйтесь командой /newcontext"

    private val assistantPrompt =
        "You are responsible for the chatbot in telegram, which name is $botName. You are in {group_or_pm} \"{chat_name}\""

    fun assistantPrompt(chatName: String, isGroup: Boolean) =
        assistantPrompt.replace(
            "{group_or_pm}",
            if (isGroup) "group, named" else "personal chat with user"
        ).replace(
            "{chat_name}",
            chatName
        )

    fun processQueue(i: Long) = when (i) {
        0L -> "*Ваш запрос в обработке*"
        -1L -> "*Ваш запрос в очереди*"
        else -> "*Ваш запрос в очереди*\nНа этот момент запросов: $i"
    }

    val errorDb =
        "*Получена ошибка доступа к БД*\nПопробуйте начать новый контекст и повторить позже, либо напишите [администратору](https://t.me/viniclemk)"

    fun errorInternet(code: Int) =
        "*Получена неизвестная сетевая ошибка $code*\n" +
                "Попробуйте начать новый контекст и повторить позже, либо напишите [администратору](https://t.me/viniclemk)"

    fun error(name: String) =
        "*Получена неизвестная внутренняя ошибка $name*\n" +
                "Попробуйте начать новый контекст и повторить позже, либо напишите [администратору](https://t.me/viniclemk)"

    val version = "2.0.2"
    fun settings(usage: Int, translator: Boolean, jailbreak: Boolean): String {
        return arrayOf(
            "*IgnatGPT Kotlin v$version Beta*",
            "",
            "*Автоперевод*: ${
                if (translator) "сквозной"
                else "Отключен"
            }",
            "*Режим Баженова*: ${
                if (jailbreak) "Включен"
                else "Отключен"
            }",
            "*Размер диалога*: $usage из 3500 токенов (осталось ${3500 - usage})"
        ).joinToString("\n")
    }

    val restricted: String = "Сервера OpenAI перегружены\n" +
            "Попробуйте повторить позже, либо напишите [администратору](https://t.me/viniclemk)"
}
