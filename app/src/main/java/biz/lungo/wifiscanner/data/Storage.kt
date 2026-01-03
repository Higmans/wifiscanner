package biz.lungo.wifiscanner.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import biz.lungo.wifiscanner.R
import biz.lungo.wifiscanner.service.Scheduler
import kotlinx.datetime.DayOfWeek

class Storage(context: Context) {

    private val sharedPreferences: SharedPreferences =
        context.getSharedPreferences(context.getString(R.string.app_name), Context.MODE_PRIVATE)

    fun setDelayValue(value: Int) {
        sharedPreferences.edit {
            putInt(PREFS_NAME_DELAY_VALUE, value)
        }
    }

    fun getDelayValue() = sharedPreferences.getInt(PREFS_NAME_DELAY_VALUE, DELAY_VALUE_DEFAULT)

    fun setSendMessageChecked(checked: Boolean) {
        sharedPreferences.edit {
            putBoolean(PREFS_NAME_SEND_MESSAGE_CHECKED, checked)
        }

    }

    fun getSendMessageChecked() = sharedPreferences.getBoolean(PREFS_NAME_SEND_MESSAGE_CHECKED, false)

    fun setSendReminderChecked(checked: Boolean) {
        sharedPreferences.edit {
            putBoolean(PREFS_NAME_SEND_REMINDER_CHECKED, checked)
        }
    }

    fun getSendReminderChecked() = sharedPreferences.getBoolean(PREFS_NAME_SEND_REMINDER_CHECKED, false)

    fun setLastStatus(status: Status) {
        sharedPreferences.edit {
            putString(PREFS_NAME_LAST_STATUS, status.state)
            putString(PREFS_NAME_LAST_STATUS_SINCE, status.since.toString())
        }
    }

    fun getLastStatus() = sharedPreferences.getString(PREFS_NAME_LAST_STATUS, null)?.toStatus(getLastStatusSince()?.toSince())

    private fun getLastStatusSince() = sharedPreferences.getString(PREFS_NAME_LAST_STATUS_SINCE, null)

    fun addNetwork(network: WiFi) {
        val currentNetworks = getNetworks().toMutableList()
        sharedPreferences.edit {
            currentNetworks.add(network)
            putStringSet(PREFS_NAME_NETWORKS, currentNetworks.map { it.name }.toSet())
        }
    }

    fun removeNetwork(network: WiFi) {
        val currentNetworks = getNetworks().toMutableList()
        sharedPreferences.edit {
            currentNetworks.remove(network)
            putStringSet(PREFS_NAME_NETWORKS, currentNetworks.map { it.name }.toSet())
        }
    }

    fun getNetworks() = sharedPreferences.getStringSet(PREFS_NAME_NETWORKS, null)?.map { it.toWiFi() } ?: listOf()

    private fun String.toWiFi() = WiFi(this, 0)

    fun isChecked(wiFi: WiFi): Boolean = getNetworks().any { it.name == wiFi.name }

    fun setLastTriggeredSchedule(triggeredSchedule: Scheduler.TriggeredSchedule) {
        sharedPreferences.edit {
            putString(PREFS_NAME_LAST_TRIGGERED_SCHEDULE_DAY, triggeredSchedule.day.dow.name)
            putInt(PREFS_NAME_LAST_TRIGGERED_SCHEDULE_HOUR, triggeredSchedule.hour)
        }
    }

    fun getLastTriggeredSchedule(): Scheduler.TriggeredSchedule? {
        val day = sharedPreferences.getString(PREFS_NAME_LAST_TRIGGERED_SCHEDULE_DAY, null)
        val hour = sharedPreferences.getInt(PREFS_NAME_LAST_TRIGGERED_SCHEDULE_HOUR, 0)
        return day?.let { Scheduler.TriggeredSchedule(it.toDay(), hour) }
    }

    fun setKeepAwake(keepAwake: Boolean) {
        sharedPreferences.edit {
            putBoolean(PREFS_NAME_KEEP_AWAKE, keepAwake)
        }
    }

    fun getKeepAwake() = sharedPreferences.getBoolean(PREFS_NAME_KEEP_AWAKE, false)

    fun setScanMode(scanMode: ScanMode) {
        sharedPreferences.edit {
            putString(PREFS_NAME_SCAN_MODE, scanMode.name)
        }
    }

    fun getScanMode() = ScanMode.fromString(sharedPreferences.getString(PREFS_NAME_SCAN_MODE, null))

    fun getNetworksThreshold() =  sharedPreferences.getInt(PREFS_NAME_NETWORKS_THRESHOLD, 10)

    fun setNetworksThreshold(threshold: Int) {
        sharedPreferences.edit {
            putInt(PREFS_NAME_NETWORKS_THRESHOLD, threshold)
        }
    }

    private fun String.toDay() = when (this) {
        DayOfWeek.MONDAY.name -> Scheduler.Day.Monday
        DayOfWeek.TUESDAY.name -> Scheduler.Day.Tuesday
        DayOfWeek.WEDNESDAY.name -> Scheduler.Day.Wednesday
        DayOfWeek.THURSDAY.name -> Scheduler.Day.Thursday
        DayOfWeek.FRIDAY.name -> Scheduler.Day.Friday
        DayOfWeek.SATURDAY.name -> Scheduler.Day.Saturday
        DayOfWeek.SUNDAY.name -> Scheduler.Day.Sunday
        else -> throw IllegalArgumentException("Unknown day: $this")
    }

    companion object {
        private const val PREFS_NAME_DELAY_VALUE = "PREFS_NAME_DELAY_VALUE"
        private const val PREFS_NAME_SEND_MESSAGE_CHECKED = "PREFS_NAME_CHECKED"
        private const val PREFS_NAME_SEND_REMINDER_CHECKED = "PREFS_NAME_SEND_REMINDER_CHECKED"
        private const val PREFS_NAME_LAST_STATUS = "PREFS_NAME_LAST_STATUS"
        private const val PREFS_NAME_LAST_STATUS_SINCE = "PREFS_NAME_LAST_STATUS_SINCE"
        private const val PREFS_NAME_NETWORKS = "PREFS_NAME_NETWORKS"
        private const val PREFS_NAME_LAST_TRIGGERED_SCHEDULE_DAY = "PREFS_NAME_LAST_TRIGGERED_SCHEDULE_DAY"
        private const val PREFS_NAME_LAST_TRIGGERED_SCHEDULE_HOUR = "PREFS_NAME_LAST_TRIGGERED_SCHEDULE_HOUR"
        private const val PREFS_NAME_KEEP_AWAKE = "PREFS_NAME_KEEP_AWAKE"
        private const val PREFS_NAME_NETWORKS_THRESHOLD = "PREFS_NAME_NETWORKS_THRESHOLD"
        private const val PREFS_NAME_SCAN_MODE = "PREFS_NAME_SCAN_MODE"
        private const val DELAY_VALUE_DEFAULT = 1
    }
}