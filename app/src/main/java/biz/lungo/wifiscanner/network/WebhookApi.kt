package biz.lungo.wifiscanner.network

import retrofit2.Response
import retrofit2.http.POST
import retrofit2.http.Path

interface WebhookApi {

    @POST("/api/webhook/{webhookId}")
    suspend fun notifyPowerOnline(@Path("webhookId") webhookId: String): Response<Unit>

}
