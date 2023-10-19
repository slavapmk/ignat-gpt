package ru.slavapmk.ignat.io.yandex

data class TranslateResponse(
    val translations: Array<Translation>
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as TranslateResponse

        return translations.contentEquals(other.translations)
    }

    override fun hashCode(): Int {
        return translations.contentHashCode()
    }
}