package biz.lungo.wifiscanner

import retrofit2.Response
import retrofit2.http.GET

interface WebhookApi {

    @GET("bRkgdH_7m2O37mrAA649DN")
    suspend fun callWebhook(): Response<String>

}