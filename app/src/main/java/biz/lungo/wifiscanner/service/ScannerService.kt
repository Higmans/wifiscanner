package biz.lungo.wifiscanner.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import biz.lungo.wifiscanner.R
import biz.lungo.wifiscanner.WiFiScannerApplication
import biz.lungo.wifiscanner.data.ScanMode
import biz.lungo.wifiscanner.data.Status
import biz.lungo.wifiscanner.data.Storage
import biz.lungo.wifiscanner.ui.MainActivity

class ScannerService : Service() {

    private val scanner: Scanner
        get() = WiFiScannerApplication.instance.scanner
    private var powerReceiver: BroadcastReceiver? = null
    private val storage by lazy { Storage(this) }

    private val binder: ScannerServiceBinder by lazy {
        ScannerServiceBinder()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        startForeground(1, getNotification(storage.getScanMode()))
        scanner.startScanning()
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder = binder

    private fun getNotification(scanMode: ScanMode): Notification {
        val title = scanMode.notificationTitle
        val subtitle = scanMode.notificationSubtitle
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)

        builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        return builder.build()
    }

    private val ScanMode.notificationTitle: String
        get() = when (this) {
            ScanMode.Network -> "Network Scanner Service"
            ScanMode.Power -> "Power Scanner Service"
            ScanMode.Hybrid -> "Hybrid Scanner Service"
        }

    @Suppress("RecursivePropertyAccessor")
    private val ScanMode.notificationSubtitle: String
        get() = when (this) {
            ScanMode.Network -> "Scanning for networks"
            ScanMode.Power -> "Observing power state"
            ScanMode.Hybrid -> {
                if (storage.getLastStatus() is Status.Online) {
                    ScanMode.Power.notificationSubtitle
                } else {
                    ScanMode.Network.notificationSubtitle
                }
            }
        }

    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel(
            CHANNEL_ID,
            "WiFi Scanner Service",
            NotificationManager.IMPORTANCE_HIGH
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(serviceChannel)
    }

    override fun onDestroy() {
        powerReceiver?.let { unregisterReceiver(it) }
        scanner.stopScanning()
        super.onDestroy()
    }

    inner class ScannerServiceBinder: Binder() {
        fun getService(): ScannerService = this@ScannerService
    }

    companion object {
        private const val CHANNEL_ID = "WiFi Scanner Service"
    }

}