package biz.lungo.wifiscanner.service

import android.annotation.SuppressLint
import android.app.Service.RECEIVER_NOT_EXPORTED
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.widget.Toast
import android.util.Log
import biz.lungo.wifiscanner.BuildConfig
import biz.lungo.wifiscanner.data.ScanMode
import biz.lungo.wifiscanner.data.Status
import biz.lungo.wifiscanner.data.Storage
import biz.lungo.wifiscanner.data.WiFi
import biz.lungo.wifiscanner.network.WebhookApi
import biz.lungo.wifiscanner.util.tickerFlow
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import retrofit2.Retrofit
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

// Suppression kept for WifiManager.startScan() which is deprecated but has no replacement for on-demand scanning
@Suppress("DEPRECATION")
class Scanner(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var scansPerTwoMinutes = ConcurrentHashMap.newKeySet<Long>()
    private val subscribers = ConcurrentHashMap.newKeySet<OnStateChangedListener>()
    private var job: Job? = null
    private var webhookJob: Job? = null
    private var isReceiverRegistered = false
    private var retryCount = 0
    private var singleScan = false
    private val storage = Storage(context)
    private val wifiScanReceiver: BroadcastReceiver
    private var powerReceiver: BroadcastReceiver? = null
    private val wifiManager: WifiManager
        get() = context.getSystemService(Context.WIFI_SERVICE) as WifiManager

    var isScanning: Boolean = false
        private set

    // Webhook setup for Home Assistant
    private val webhookRetrofit = Retrofit.Builder()
        .baseUrl(BuildConfig.WEBHOOK_BASE_URL)
        .build()
    private val webhookApi = webhookRetrofit.create(WebhookApi::class.java)

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
        }.launchIn(scope)

        val intentFilter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        powerReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_POWER_DISCONNECTED) {
                    onPowerStateChanged(false)
                } else if (intent.action == Intent.ACTION_POWER_CONNECTED) {
                    onPowerStateChanged(true)
                }
            }
        }
        context.registerReceiver(powerReceiver, intentFilter, RECEIVER_NOT_EXPORTED)
    }

    @SuppressLint("MissingPermission")
    private fun scanSuccess() {
        val results = wifiManager.scanResults
            .filter { result -> result.wifiSsid != null && result.wifiSsid.toString().removeSurrounding("\"").isNotBlank() }
            .map { result -> result.toWiFi() }
            .sortedByDescending { result ->  result.level }
        val trackedNetworks = storage.getNetworks()
        val hasTargetWifi = trackedNetworks.isEmpty() || results.any { it in trackedNetworks }
        val lastStatus = storage.getLastStatus()
        val currentStatus: Status = when (storage.getScanMode()) {
            ScanMode.Network,
            ScanMode.Hybrid -> hasTargetWifi.toStatus()
            ScanMode.Power -> lastStatus ?: Status.Online(LocalDateTime.now())
        }
        subscribers.forEach { it.onScanComplete() }
        if (!singleScan && currentStatus is Status.Offline && lastStatus !is Status.Offline && retryCount++ < RETRY_COUNT_MAX) {
            if (!isThrottled()) {
                startScan()
            }
            return
        }
        retryCount = 0
        if (lastStatus?.state != currentStatus.state) {
            if (storage.getScanMode() == ScanMode.Hybrid && currentStatus is Status.Online && lastStatus is Status.Offline) {
                // Only stop WiFi scanning if phone is actually charging
                Log.d("Scanner", "Offline->Online transition, hasPower=${hasPower()}")
                if (hasPower()) {
                    stopNetworkScanning()
                    stopWebhookTimer()
                } else {
                    // Online but not charging - start webhook timer
                    Log.d("Scanner", "Starting webhook timer (Offline->Online, not charging)")
                    startWebhookTimer()
                }
            }
            if (currentStatus is Status.Offline) {
                stopWebhookTimer()
            }
            storage.setLastStatus(currentStatus)
            subscribers.forEach {
                it.onStateChanged(currentStatus, lastStatus?.since)
            }
        }
        subscribers.forEach {
            it.onNetworksReceived(results)
        }
    }

    private fun ScanResult.toWiFi(): WiFi {
        val ssid = wifiSsid?.toString()?.removeSurrounding("\"") ?: ""
        return WiFi(ssid, level.validate())
    }

    private fun Boolean.toStatus() =
        if (this) {
            Status.Online(LocalDateTime.now())
        } else {
            Status.Offline(LocalDateTime.now())
        }

    private fun Int.validate() = if (this == 0) -100 else this

    fun startScanning() {
        isScanning = true
        when (storage.getScanMode()) {
            ScanMode.Network -> startNetworkScanning()
            ScanMode.Hybrid -> {
                if (storage.getLastStatus() is Status.Offline) {
                    startNetworkScanning()
                } else if (storage.getLastStatus() is Status.Online && !hasPower()) {
                    // Online but not charging - continue WiFi scanning and start webhook timer
                    startNetworkScanning()
                    startWebhookTimer()
                }
            }
            ScanMode.Power -> {
                // no-op
            }
        }
    }

    private fun startNetworkScanning() {
        registerNetworkReceiver()
        singleScan = false
        job = tickerFlow(5.seconds)
            .map { LocalDateTime.now() }
            .distinctUntilChanged { old, new ->
                new.minute - old.minute < storage.getDelayValue() && new.hour == old.hour
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
            .launchIn(scope)
    }

    fun scanOnce() {
        if (!isScanning) registerNetworkReceiver()
        singleScan = true
        if (isThrottled()) {
            subscribers.forEach { it.onScanThrottled() }
            return
        }
        if (!startScan()) {
            subscribers.forEach { it.onNetworksReceived(listOf()) }
            Toast.makeText(context, "Unable to scan Wi-Fi", Toast.LENGTH_LONG).show()
        }
    }

    fun onPowerStateChanged(hasPower: Boolean, singleScan: Boolean = false) {
        if (isScanning || singleScan) {
            val lastStatus = storage.getLastStatus()
            when (storage.getScanMode()) {
                ScanMode.Network -> {
                    if (!hasPower || singleScan) {
                        scanOnce()
                    }
                }
                ScanMode.Power -> {
                    val currentStatus = hasPower.toStatus()
                    if (currentStatus is Status.Offline) {
                        // Verify power state after a delay to avoid false triggers
                        verifyPowerStateForPowerMode(lastStatus)
                    } else if (lastStatus?.state != currentStatus.state) {
                        storage.setLastStatus(currentStatus)
                        subscribers.forEach {
                            it.onStateChanged(currentStatus, lastStatus?.since)
                        }
                    }
                }
                ScanMode.Hybrid -> {
                    if (lastStatus is Status.Online && hasPower.toStatus() is Status.Offline) {
                        val currentStatus = hasPower.toStatus()
                        if (currentStatus is Status.Offline) {
                            verifyPowerState(lastStatus, !singleScan)
                        }
                    } else if (hasPower && lastStatus is Status.Online && job != null) {
                        // Power connected while Online - stop WiFi scanning and webhook timer
                        stopNetworkScanning()
                        stopWebhookTimer()
                    } else if (singleScan) {
                        scanOnce()
                    }
                }
            }
        }
        Toast.makeText(context, "Power ${if (!hasPower) "dis" else ""}connected", Toast.LENGTH_SHORT).show()
    }

    private fun verifyPowerState(lastStatus: Status?, startNetworkScanning: Boolean) {
        scope.launch {
            delay(3.seconds)
            val currentStatus = hasPower().toStatus()
            if (lastStatus?.state != currentStatus.state && currentStatus is Status.Offline) {
                withContext(Dispatchers.Main) {
                    // Power confirmed offline - verify with WiFi scan before transitioning
                    if (startNetworkScanning) {
                        startNetworkScanning()
                    }
                    // Start webhook timer in Hybrid mode (will be stopped if status goes Offline)
                    if (storage.getScanMode() == ScanMode.Hybrid) {
                        startWebhookTimer()
                    }
                    // Perform a WiFi scan to verify - scanSuccess() will handle state transition
                    scanOnce()
                }
            } else if (storage.getScanMode() == ScanMode.Hybrid &&
                       lastStatus is Status.Online &&
                       !hasPower()) {
                withContext(Dispatchers.Main) {
                    // Power still offline while Online - start webhook timer
                    startWebhookTimer()
                    if (startNetworkScanning && job == null) {
                        startNetworkScanning()
                    }
                }
            }
        }
    }

    private fun verifyPowerStateForPowerMode(lastStatus: Status?) {
        scope.launch {
            delay(3.seconds)
            val currentStatus = hasPower().toStatus()
            if (lastStatus?.state != currentStatus.state) {
                storage.setLastStatus(currentStatus)
                withContext(Dispatchers.Main) {
                    subscribers.forEach {
                        it.onStateChanged(currentStatus, lastStatus?.since)
                    }
                }
            }
        }
    }

    fun hasPower(): Boolean {
        val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { iFilter ->
            context.registerReceiver(null, iFilter)
        }
        val status: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging: Boolean = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val chargePlug: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        val usbCharge: Boolean = chargePlug == BatteryManager.BATTERY_PLUGGED_USB
        val acCharge: Boolean = chargePlug == BatteryManager.BATTERY_PLUGGED_AC
        return isCharging || usbCharge || acCharge
    }

    private fun startScan(): Boolean {
        val now = LocalDateTime.now().atZone(ZoneId.systemDefault()).toEpochSecond()
        scansPerTwoMinutes.add(now)
        return wifiManager.startScan()
    }

    private fun registerNetworkReceiver() {
        if (isReceiverRegistered) return
        try {
            context.registerReceiver(wifiScanReceiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
            isReceiverRegistered = true
        } catch (e: Exception) {
            Toast.makeText(context, "Unable to register Wi-Fi receiver", Toast.LENGTH_SHORT).show()
        }
    }

    private fun isThrottled() = scansPerTwoMinutes.size >= 4 && wifiManager.isScanThrottleEnabled

    fun stopScanning() {
        stopNetworkScanning()
        stopWebhookTimer()
        isScanning = false
    }

    fun destroy() {
        stopScanning()
        scope.cancel()
        try {
            powerReceiver?.let { context.unregisterReceiver(it) }
        } catch (_: Exception) { }
    }

    private fun stopNetworkScanning() {
        if (isReceiverRegistered) {
            try {
                context.unregisterReceiver(wifiScanReceiver)
            } catch (e: Exception) {
                Toast.makeText(context, "Unable to unregister Wi-Fi receiver", Toast.LENGTH_SHORT).show()
            }
            isReceiverRegistered = false
        }
        job?.cancel()
        job = null
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

    private fun startWebhookTimer() {
        Log.d("Scanner", "startWebhookTimer called, webhookJob=${webhookJob}")
        if (webhookJob != null) {
            Log.d("Scanner", "Webhook timer already running, skipping")
            return
        }
        webhookJob = tickerFlow(3.minutes)
            .onEach {
                Log.d("Scanner", "Webhook tick: mode=${storage.getScanMode()}, status=${storage.getLastStatus()?.state}, hasPower=${hasPower()}")
                // Only send if still in Hybrid mode, Online, and not charging
                if (storage.getScanMode() == ScanMode.Hybrid &&
                    storage.getLastStatus() is Status.Online &&
                    !hasPower()) {
                    try {
                        Log.d("Scanner", "Sending webhook to ${BuildConfig.WEBHOOK_BASE_URL} with id ${BuildConfig.WEBHOOK_ID}")
                        webhookApi.notifyPowerOnline(BuildConfig.WEBHOOK_ID)
                        Log.d("Scanner", "Webhook sent successfully")
                    } catch (e: Exception) {
                        Log.e("Scanner", "Webhook failed", e)
                    }
                }
            }
            .launchIn(scope)
    }

    private fun stopWebhookTimer() {
        webhookJob?.cancel()
        webhookJob = null
    }

    interface OnStateChangedListener {
        fun onScanThrottled()
        fun onScanComplete()
        fun onStateChanged(status: Status, lastStatusTime: LocalDateTime?)
        fun onNetworksReceived(networks: List<WiFi>)
    }

}