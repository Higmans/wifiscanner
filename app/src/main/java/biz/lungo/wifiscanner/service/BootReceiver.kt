package biz.lungo.wifiscanner.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import biz.lungo.wifiscanner.data.Storage

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val storage = Storage(context)
            if (storage.getServiceRunning()) {
                val serviceIntent = Intent(context, ScannerService::class.java)
                context.startForegroundService(serviceIntent)
            }
        }
    }
}
