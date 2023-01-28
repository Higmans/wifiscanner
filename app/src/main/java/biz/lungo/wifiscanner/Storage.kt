package biz.lungo.wifiscanner

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

class Storage(context: Context) {

    private val sharedPreferences: SharedPreferences

    init {
        sharedPreferences = context.getSharedPreferences(context.getString(R.string.app_name), Context.MODE_PRIVATE)
    }

    fun setDelayValue(value: Int) {
        sharedPreferences.edit {
            putInt(PREFS_NAME_DELAY_VALUE, value)
        }
    }

    fun getDelayValue() = sharedPreferences.getInt(PREFS_NAME_DELAY_VALUE, DELAY_VALUE_DEFAULT)

    fun setChecked(checked: Boolean) {
        sharedPreferences.edit {
            putBoolean(PREFS_NAME_CHECKED, checked)
        }

    }

    fun getChecked() = sharedPreferences.getBoolean(PREFS_NAME_CHECKED, false)

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

    companion object {
        private const val PREFS_NAME_DELAY_VALUE = "PREFS_NAME_DELAY_VALUE"
        private const val PREFS_NAME_CHECKED = "PREFS_NAME_CHECKED"
        private const val PREFS_NAME_LAST_STATUS = "PREFS_NAME_LAST_STATUS"
        private const val PREFS_NAME_LAST_STATUS_SINCE = "PREFS_NAME_LAST_STATUS_SINCE"
        private const val PREFS_NAME_NETWORKS = "PREFS_NAME_NETWORKS"
        private const val DELAY_VALUE_DEFAULT = 1
    }
}