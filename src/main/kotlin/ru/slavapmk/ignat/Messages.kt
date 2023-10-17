package ru.slavapmk.ignat

object Messages {
    val botName = "Ignat"
    val errorDbUnknown =
        "Похоже, на стороне бота произошла ошибка доступа к БД, пожалуйста подождите или напишите администратору"
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

    val tooLongContext =
        "Похоже, ваш диалог слишком длинный. Чтобы начать его заного воспользуйтесь командой /newcontext"

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

    val process = "*Ваш запрос в обработке*"
    fun processQueue(i: Int) = "*Ваш запрос в обработке*\nНа этот момент запросов в очереди: $i"
    fun errorInternet(code: Int) = "Получена неизвестная сетевая ошибка $code. Просьба обратиться к администратору"
    fun error(name: String) = "Получена неизвестная внутренняя ошибка сервера $name. Просьба обратиться к администратору"

    val retry = "Повторение запроса"
}