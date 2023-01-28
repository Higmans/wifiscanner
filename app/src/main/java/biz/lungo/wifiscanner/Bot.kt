package biz.lungo.wifiscanner

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class Bot(private val botApiKey: String?, private val botChatId: Long?) {

    private val retrofit = Retrofit.Builder()
        .baseUrl(BOT_API_URL)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
    private val botApiService = retrofit.create(BotApi::class.java)

    fun sendMessage(message: String): Boolean {
        if (botApiKey == null || botChatId == null) return false
        flow {
            botApiService.sendMessage(botApiKey, MessageRequest(botChatId, message, ParseMode.HTML.value))
            emit(Unit)
        }.launchIn(CoroutineScope(Dispatchers.Default))
        return true
    }

    companion object {
        private const val BOT_API_URL = "https://api.telegram.org"
    }
}