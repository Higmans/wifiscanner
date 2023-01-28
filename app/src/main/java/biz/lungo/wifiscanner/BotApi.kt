package biz.lungo.wifiscanner

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Path

interface BotApi {

    @POST("/bot{botApiKey}/sendMessage")
    suspend fun sendMessage(@Path("botApiKey") botApiKey: String, @Body messageRequest: MessageRequest): Response<SendMessageResult>

}