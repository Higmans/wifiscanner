package biz.lungo.wifiscanner

import android.app.Application
import biz.lungo.wifiscanner.data.Storage
import biz.lungo.wifiscanner.service.Bot
import biz.lungo.wifiscanner.service.Scanner
import biz.lungo.wifiscanner.service.Scheduler

class WiFiScannerApplication : Application() {

    private val botApiKey
        get() = if (BuildConfig.BOT_API_KEY == "null") null else BuildConfig.BOT_API_KEY

    private val botChatId = BuildConfig.CHAT_ID.toLongOrNull()

    private val adminChatId = BuildConfig.ADMIN_CHAT_ID.toLongOrNull()

    val bot: Bot by lazy { Bot(botApiKey, botChatId, adminChatId) }
    val scanner: Scanner by lazy { Scanner(this) }
    val scheduler: Scheduler by lazy { Scheduler(Storage(this)) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        scheduler.start()
    }

    companion object {
        lateinit var instance: WiFiScannerApplication
            private set
    }
}