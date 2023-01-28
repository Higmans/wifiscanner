package biz.lungo.wifiscanner

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import nl.mirrajabi.humanize.duration.DurationHumanizer
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

class Bot(private val botApiKey: String?, private val botChatId: Long?) :
    Scanner.OnStateChangedListener {

    var isBotEnabled = botApiKey != null && botChatId != null
    var shouldSendMessage = isBotEnabled
        set(value) {
            field = value && isBotEnabled
            if (field) {
                WiFiScannerApplication.instance.scanner.subscribe(this)
            } else {
                WiFiScannerApplication.instance.scanner.unsubscribe(this)
            }
        }

    private val humanizer = DurationHumanizer()
    private val languages = mapOf("ukr" to UkrainianDictionary())
    private val humanizerOptions = DurationHumanizer.Options(language = "Ukrainian", delimiter = "", languages = languages, fallbacks = listOf("ukr"))

    private val retrofit = Retrofit.Builder()
        .baseUrl(BOT_API_URL)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
    private val botApiService = retrofit.create(BotApi::class.java)

    private fun sendMessage(message: String) {
        if (botApiKey == null || botChatId == null) {
            throw java.lang.RuntimeException("Bot is not configured")
        }
        flow {
            botApiService.sendMessage(botApiKey, MessageRequest(botChatId, message, ParseMode.HTML.value))
            emit(Unit)
        }.launchIn(CoroutineScope(Dispatchers.Default))
    }

    private fun formatMessage(status: Status, lastStatusTime: LocalDateTime?): String {
        return when (status) {
            is Status.Online -> {
                val humanDuration = formatHumanDuration(status.since, lastStatusTime)
                val readableSince = humanDuration?.let { "${br}Його не було $it" } ?: ""
                "<b>Світло ввімкнули!</b> \uD83D\uDCA1$readableSince"
            }
            is Status.Offline -> {
                "Світло вимкнули... ❌"
            }
        }
    }

    private fun formatHumanDuration(
        current: LocalDateTime?,
        lastStatusTime: LocalDateTime?
    ): String? {
        return if (current != null && lastStatusTime != null) {
            val zdtCurrent: ZonedDateTime = current.truncatedTo(ChronoUnit.MINUTES).atZone(ZoneId.systemDefault())
            val zdtLast: ZonedDateTime = lastStatusTime.truncatedTo(ChronoUnit.MINUTES).atZone(
                ZoneId.systemDefault())
            val diff = zdtCurrent.toInstant().toEpochMilli() - zdtLast.toInstant().toEpochMilli()
            humanizer.humanize(diff, humanizerOptions)
        } else {
            null
        }
    }

    override fun onScanThrottled() {
        // no-op
    }

    override fun onScanComplete() {
        // no-op
    }

    override fun onStateChanged(status: Status, lastStatusTime: LocalDateTime?) {
        sendMessage(formatMessage(status, lastStatusTime))
    }

    override fun onNetworksReceived(networks: List<WiFi>) {
        // no-op
    }

    companion object {
        private const val BOT_API_URL = "https://api.telegram.org"
        private val br = System.lineSeparator()
    }
}