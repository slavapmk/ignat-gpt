package ru.slavapmk.ignat.io.yandex

import java.time.Instant
import java.time.format.DateTimeParseException

data class IamTokenResponse(
    val iamToken: String,
    val expiresAt: String
) {
    val expiresEpoch
        get() = try {
            Instant.parse(expiresAt).toEpochMilli()
        } catch (e: DateTimeParseException) {
            -1L
        }

}
