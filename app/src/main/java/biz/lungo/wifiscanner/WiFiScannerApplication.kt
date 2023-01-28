package biz.lungo.wifiscanner

import android.app.Application

class WiFiScannerApplication : Application() {

    private val botApiKey
        get() = if (BuildConfig.BOT_API_KEY == "null") null else BuildConfig.BOT_API_KEY

    private val botChatId
        get() = try {
            BuildConfig.CHAT_ID.toLong()
        } catch (e: NumberFormatException) {
            null
        }

    val bot: Bot by lazy { Bot(botApiKey, botChatId) }
    val scanner: Scanner by lazy { Scanner(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: WiFiScannerApplication
            private set
    }
}