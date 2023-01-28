package biz.lungo.wifiscanner

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.widget.Toast
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
@Suppress("DEPRECATION")
class Scanner(private val context: Context) {

    private var scansPerTwoMinutes = ConcurrentHashMap.newKeySet<Long>()
    private val subscribers = mutableSetOf<OnStateChangedListener>()
    private var job: Job? = null
    private var retryCount = 0
    private var shouldRetry = false
    private val storage = Storage(context)
    private val wifiScanReceiver: BroadcastReceiver
    private val wifiManager: WifiManager
        get() = context.getSystemService(Context.WIFI_SERVICE) as WifiManager

    init {
        wifiScanReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)) {
                    scanSuccess()
                } else {
                    Toast.makeText(context, "Error scanning for networks", Toast.LENGTH_LONG).show()
                }
            }
        }

        tickerFlow(1.seconds).onEach {
            val outdatedScans = mutableSetOf<Long>()
            scansPerTwoMinutes.forEach { scan ->
                if (scan < LocalDateTime.now().minusMinutes(2).atZone(ZoneId.systemDefault()).toEpochSecond()) {
                    outdatedScans.add(scan)
                }
            }
            scansPerTwoMinutes.removeAll(outdatedScans)
        }.launchIn(CoroutineScope(Dispatchers.Default))
    }

    @SuppressLint("MissingPermission")
    private fun scanSuccess() {
        val results = wifiManager.scanResults
        val trackedNetworks = storage.getNetworks()
        val hasTargetWifi = trackedNetworks.isEmpty() || results.any { it.toWiFi() in trackedNetworks }
        val lastStatus = storage.getLastStatus()
        val currentStatus = hasTargetWifi.toStatus()
        subscribers.forEach { it.onScanComplete() }
        if (shouldRetry && currentStatus is Status.Offline && lastStatus !is Status.Offline && retryCount++ < RETRY_COUNT_MAX) {
            if (!isThrottled()) {
                startScan()
            }
            return
        }
        retryCount = 0
        if (lastStatus?.state != currentStatus.state) {
            storage.setLastStatus(currentStatus)
            subscribers.forEach {
                it.onStateChanged(currentStatus, lastStatus?.since)
            }
        }
        subscribers.forEach {
            it.onNetworksReceived(
                results.filter { result -> result.SSID.isNotBlank() }
                    .map { result -> result.toWiFi() }
                    .sortedByDescending { result ->  result.level }
            )
        }
    }

    private fun ScanResult.toWiFi() = WiFi(SSID, level.validate())

    private fun Boolean.toStatus() =
        if (this) {
            Status.Online(LocalDateTime.now())
        } else {
            Status.Offline(LocalDateTime.now())
        }

    private fun Int.validate() = if (this == 0) -100 else this

    private fun isAndroidLowerThanR() =
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R

    fun startScanning(scanPeriodMinutes: Int) {
        registerReceiver()
        shouldRetry = true
        job = tickerFlow(5.seconds)
            .map { LocalDateTime.now() }
            .distinctUntilChanged { old, new ->
                new.minute - old.minute < scanPeriodMinutes && new.hour == old.hour
            }
            .onEach {
                if (isThrottled()) {
                    subscribers.forEach { it.onScanThrottled() }
                    return@onEach
                }
                if (!startScan()) {
                    withContext(Dispatchers.Main) {
                        subscribers.forEach { it.onNetworksReceived(listOf()) }
                        Toast.makeText(context, "Unable to start Wi-Fi scanning", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .launchIn(CoroutineScope(Dispatchers.Default))
    }

    fun scanOnce() {
        registerReceiver()
        shouldRetry = false
        if (isThrottled()) {
            subscribers.forEach { it.onScanThrottled() }
            return
        }
        if (!startScan()) {
            subscribers.forEach { it.onNetworksReceived(listOf()) }
            Toast.makeText(context, "Unable to scan Wi-Fi", Toast.LENGTH_LONG).show()
        }
    }

    private fun startScan(): Boolean {
        val now = LocalDateTime.now().atZone(ZoneId.systemDefault()).toEpochSecond()
        scansPerTwoMinutes.add(now)
        return wifiManager.startScan()
    }

    private fun registerReceiver() {
        try {
            context.registerReceiver(wifiScanReceiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
        } catch (e: Exception) {
            Toast.makeText(context, "Unable to register Wi-Fi receiver", Toast.LENGTH_SHORT).show()
        }
    }

    private fun isThrottled() = scansPerTwoMinutes.size >= 4 && (isAndroidLowerThanR() || wifiManager.isScanThrottleEnabled)

    fun stopScanning() {
        try {
            context.unregisterReceiver(wifiScanReceiver)
        } catch (e: Exception) {
            Toast.makeText(context, "Unable to unregister Wi-Fi receiver", Toast.LENGTH_SHORT).show()
        }
        job?.cancel()
        job = null
    }

    fun isScanning() = job != null

    private fun tickerFlow(period: Duration, initialDelay: Duration = Duration.ZERO) = flow {
        delay(initialDelay)
        while (true) {
            emit(Unit)
            delay(period)
        }
    }

    fun subscribe(listener: OnStateChangedListener) {
        subscribers.add(listener)
    }

    fun unsubscribe(listener: OnStateChangedListener) {
        subscribers.remove(listener)
        if (subscribers.isEmpty()) {
            stopScanning()
        }
    }

    companion object {
        private const val RETRY_COUNT_MAX = 3
    }

    interface OnStateChangedListener {
        fun onScanThrottled()
        fun onScanComplete()
        fun onStateChanged(status: Status, lastStatusTime: LocalDateTime?)
        fun onNetworksReceived(networks: List<WiFi>)
    }

}