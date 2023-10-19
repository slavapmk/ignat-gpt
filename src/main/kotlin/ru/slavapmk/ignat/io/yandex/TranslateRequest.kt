package ru.slavapmk.ignat.io.yandex

data class TranslateRequest(
    val folderId: String,
    val texts: Array<String>,
    val targetLanguageCode: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as TranslateRequest

        if (folderId != other.folderId) return false
        if (!texts.contentEquals(other.texts)) return false
        if (targetLanguageCode != other.targetLanguageCode) return false

        return true
    }

    override fun hashCode(): Int {
        var result = folderId.hashCode()
        result = 31 * result + texts.contentHashCode()
        result = 31 * result + targetLanguageCode.hashCode()
        return result
    }
}
