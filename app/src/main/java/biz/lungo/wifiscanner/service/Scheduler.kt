package biz.lungo.wifiscanner.service

import biz.lungo.wifiscanner.data.Storage
import biz.lungo.wifiscanner.util.tickerFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.datetime.*
import kotlin.time.Duration.Companion.minutes

class Scheduler(private val storage: Storage) {

    private var job: Job? = null
    private val subscribers = mutableSetOf<ScheduleListener>()
    var nextScheduledBlackout = Instant.fromEpochMilliseconds(0).toLocalDateTime(TimeZone.currentSystemDefault())
        private set

    init {
        updateNextScheduled(Clock.System.now())
    }

    fun start() {
        job = tickerFlow(1.minutes).onEach {
            checkTime()
        }.launchIn(CoroutineScope(Dispatchers.Default))
    }

    private fun stop() {
        job?.cancel()
        job = null
    }

    fun subscribe(listener: ScheduleListener) {
        subscribers.add(listener)
        if (job == null) {
            start()
        }
    }

    fun unsubscribe(listener: ScheduleListener) {
        subscribers.remove(listener)
        if (subscribers.isEmpty()) {
            stop()
        }
    }

    private fun checkTime() {
        val now = Clock.System.now()
        updateNextScheduled(now)
        val diff = now.until(
            nextScheduledBlackout.toInstant(TimeZone.currentSystemDefault()),
            DateTimeUnit.MINUTE
        )
        val lastTriggered = storage.getLastTriggeredSchedule()
        if (lastTriggered != nextScheduledBlackout.toLastTriggeredSchedule() && diff <= MINUTES_THRESHOLD) {
            if (subscribers.isNotEmpty()) {
                subscribers.forEach {
                    it.onSchedule(diff)
                }
                storage.setLastTriggeredSchedule(nextScheduledBlackout.toLastTriggeredSchedule())
            }
        }
    }

    private fun updateNextScheduled(now: Instant) {
        val localDateTime = now.toLocalDateTime(TimeZone.currentSystemDefault())
        val dayOfWeek = localDateTime.dayOfWeek
        when (dayOfWeek) {
            DayOfWeek.MONDAY -> Day.Monday
            DayOfWeek.TUESDAY -> Day.Tuesday
            DayOfWeek.WEDNESDAY -> Day.Wednesday
            DayOfWeek.THURSDAY -> Day.Thursday
            DayOfWeek.FRIDAY -> Day.Friday
            DayOfWeek.SATURDAY -> Day.Saturday
            DayOfWeek.SUNDAY -> Day.Sunday
        }.let { day ->
            val overflow = day.times.last() <= localDateTime.hour
            val targetDay = if (overflow) day.next else day
            val targetTime = if (overflow) targetDay.times.first() else when (localDateTime.hour) {
                in 0 until targetDay.times[0] -> targetDay.times[0]
                in targetDay.times[0] until targetDay.times[1] -> targetDay.times[1]
                else -> targetDay.times[2]
            }
            (if (overflow) localDateTime.date.plus(1, DateTimeUnit.DAY) else localDateTime.date).atTime(targetTime, 0, 0).let {
                if (it != nextScheduledBlackout) {
                    nextScheduledBlackout = it
                    subscribers.forEach { listener ->
                        listener.onNextBlackoutUpdated(it.toJavaLocalDateTime())
                    }
                }
            }
        }
    }

    private fun LocalDateTime.toLastTriggeredSchedule(): TriggeredSchedule {
        return when (this.dayOfWeek) {
            DayOfWeek.MONDAY -> TriggeredSchedule(Day.Monday, this.hour)
            DayOfWeek.TUESDAY -> TriggeredSchedule(Day.Tuesday, this.hour)
            DayOfWeek.WEDNESDAY -> TriggeredSchedule(Day.Wednesday, this.hour)
            DayOfWeek.THURSDAY -> TriggeredSchedule(Day.Thursday, this.hour)
            DayOfWeek.FRIDAY -> TriggeredSchedule(Day.Friday, this.hour)
            DayOfWeek.SATURDAY -> TriggeredSchedule(Day.Saturday, this.hour)
            DayOfWeek.SUNDAY -> TriggeredSchedule(Day.Sunday, this.hour)
        }
    }

    sealed class Day(val dow: DayOfWeek, val times: List<Int>, val next: Day) {
        object Monday : Day(DayOfWeek.MONDAY, listOf(0, 9, 18), Tuesday)
        object Tuesday : Day(DayOfWeek.TUESDAY, listOf(3, 12, 21), Wednesday)
        object Wednesday : Day(DayOfWeek.WEDNESDAY, listOf(6, 15), Thursday)
        object Thursday : Day(DayOfWeek.THURSDAY, listOf(0, 9, 18), Friday)
        object Friday : Day(DayOfWeek.FRIDAY, listOf(3, 12, 21), Saturday)
        object Saturday : Day(DayOfWeek.SATURDAY, listOf(6, 15), Sunday)
        object Sunday : Day(DayOfWeek.SUNDAY, listOf(0, 9, 18), Monday)
    }

    data class TriggeredSchedule(val day: Day, val hour: Int) {

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as TriggeredSchedule

            if (day != other.day) return false
            if (hour != other.hour) return false

            return true
        }

        override fun hashCode(): Int {
            var result = day.hashCode()
            result = 31 * result + hour
            return result
        }
    }

    interface ScheduleListener {
        fun onSchedule(diffMinutes: Long)
        fun onNextBlackoutUpdated(nextBlackout: java.time.LocalDateTime)
    }

    companion object {
        private const val MINUTES_THRESHOLD = 30
    }
}