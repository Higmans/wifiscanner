package biz.lungo.wifiscanner.service

import android.util.Log
import biz.lungo.wifiscanner.MessageRequest
import biz.lungo.wifiscanner.ParseMode
import biz.lungo.wifiscanner.data.Status
import biz.lungo.wifiscanner.data.Status.*
import biz.lungo.wifiscanner.data.Storage
import biz.lungo.wifiscanner.data.UkrainianDictionary
import biz.lungo.wifiscanner.data.WiFi
import biz.lungo.wifiscanner.network.BotApi
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.retryWhen
import nl.mirrajabi.humanize.duration.DurationHumanizer
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

class Bot(
    private val botApiKey: String?,
    private val botChatId: Long?,
    private val adminChatId: Long?,
    private val scanner: Scanner,
    private val scheduler: Scheduler,
    private val storage: Storage
) : Scanner.OnStateChangedListener, Scheduler.ScheduleListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    var isBotEnabled = botApiKey != null && botChatId != null
    var shouldSendMessage = isBotEnabled
        set(value) {
            field = value && isBotEnabled
            if (field) {
                scanner.subscribe(this)
            } else {
                scanner.unsubscribe(this)
            }
        }

    var shouldSendReminder = isBotEnabled
        set(value) {
            field = value && isBotEnabled
            if (field) {
                scheduler.subscribe(this)
            } else {
                scheduler.unsubscribe(this)
            }
        }

    private val humanizer = DurationHumanizer()
    private val languages = mapOf("ukr" to UkrainianDictionary())
    private val humanizerOptions = DurationHumanizer.Options(language = "Ukrainian", delimiter = " ", languages = languages, fallbacks = listOf("ukr"))

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
        }.retryWhen { cause, attempt ->
            Log.e("Bot", "Failed to send message: $message", cause)
            delay(RETRY_DELAY)
            attempt < MAX_RETRY_COUNT
        }.catch {
            Log.e("Bot", "Failed to send message: $message", it)
        }.launchIn(scope)
    }

    private fun sendPrivateMessage(message: String) {
        if (botApiKey == null || adminChatId == null) {
            throw java.lang.RuntimeException("Bot is not configured")
        }
        flow {
            botApiService.sendMessage(botApiKey, MessageRequest(adminChatId, message, ParseMode.HTML.value))
            emit(Unit)
        }.retryWhen { cause, attempt ->
            Log.e("Bot", "Failed to send message: $message", cause)
            delay(RETRY_DELAY)
            attempt < MAX_RETRY_COUNT
        }.catch {
            Log.e("Bot", "Failed to send message: $message", it)
        }.launchIn(scope)
    }

    private fun formatBlackoutMessage(status: Status, lastStatusTime: LocalDateTime?): String {
        return when (status) {
            is Online -> {
                val humanDuration = formatHumanDuration(status.since, lastStatusTime)
                val readableSince = humanDuration?.let { "${br}Його не було $it" } ?: ""
//                val nextBlackoutString = scheduler.nextScheduledBlackout.toJavaLocalDateTime().toNextBlackoutString()
                val nextBlackoutString = ""
                "<b>Світло є!</b> \uD83D\uDCA1$readableSince$nextBlackoutString"
            }
            is Offline -> {
                "Світло відключили... ❌"
            }
        }
    }

    private fun LocalDateTime.toNextBlackoutString() =
        "${br}Наступне ймовірне відключення - ${this.dayOfMonth.asString()}.${this.monthValue.asString()} ${this.hour.asString()}:${this.minute.asString()}"

    private fun Int.asString() = if (this < 10) "0$this" else this.toString()

    private fun formatReminderMessage(minutesUntil: Long) =
        "⌛ Можливе відключення світла за графіком через $minutesUntil хв."

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
        sendMessage(formatBlackoutMessage(status, lastStatusTime))
    }

    override fun onNetworksReceived(networks: List<WiFi>) {
        val threshold = storage.getNetworksThreshold()
        val filtered = networks.map { WiFi(it.name, it.level) }.toSet()
        if (filtered.size >= threshold && storage.getLastStatus() is Offline) {
            sendPrivateMessage("Check power, networks available: ${filtered.size}")
        }
    }

    override fun onSchedule(diffMinutes: Long) {
        if (storage.getLastStatus() is Online) {
            sendMessage(formatReminderMessage(diffMinutes))
        }
    }

    override fun onNextBlackoutUpdated(nextBlackout: LocalDateTime) {
        // no-op
    }

    fun destroy() {
        scope.cancel()
    }

    companion object {
        private const val BOT_API_URL = "https://api.telegram.org"
        private const val MAX_RETRY_COUNT = 10
        private const val RETRY_DELAY = 500L
        private val br = System.lineSeparator()
    }
}