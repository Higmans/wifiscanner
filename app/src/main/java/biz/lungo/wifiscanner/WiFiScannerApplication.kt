package biz.lungo.wifiscanner

import android.app.Application

class WiFiScannerApplication : Application() {

    private val botApiKey: String
        get() = BuildConfig.BOT_API_KEY

    private val botChatId: Long
        get() = BuildConfig.CHAT_ID.toLong()

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