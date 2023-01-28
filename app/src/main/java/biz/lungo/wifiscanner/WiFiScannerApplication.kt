package biz.lungo.wifiscanner

import android.app.Application

class WiFiScannerApplication : Application() {

    val botApiKey: String
        get() = BuildConfig.BOT_API_KEY

    val botChatId: Long
        get() = BuildConfig.CHAT_ID.toLong()

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        var instance: WiFiScannerApplication? = null
            private set
    }
}