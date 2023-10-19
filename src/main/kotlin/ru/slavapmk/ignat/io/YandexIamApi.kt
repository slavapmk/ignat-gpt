package ru.slavapmk.ignat.io

import io.reactivex.rxjava3.core.Observable
import retrofit2.http.Body
import retrofit2.http.POST
import ru.slavapmk.ignat.io.yandex.IamTokenRequest
import ru.slavapmk.ignat.io.yandex.IamTokenResponse

interface YandexIamApi {
    @POST("/iam/v1/tokens")
    fun request(
        @Body request: IamTokenRequest
    ): Observable<IamTokenResponse>
}