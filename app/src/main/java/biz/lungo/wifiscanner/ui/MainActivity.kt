package biz.lungo.wifiscanner.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.AdapterView
import android.widget.AdapterView.OnItemSelectedListener
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.SeekBar
import android.widget.SeekBar.OnSeekBarChangeListener
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doOnTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import biz.lungo.wifiscanner.*
import biz.lungo.wifiscanner.data.ScanMode
import biz.lungo.wifiscanner.data.Status
import biz.lungo.wifiscanner.data.Storage
import biz.lungo.wifiscanner.data.WiFi
import biz.lungo.wifiscanner.databinding.ActivityMainBinding
import biz.lungo.wifiscanner.service.Bot
import biz.lungo.wifiscanner.service.Scanner
import biz.lungo.wifiscanner.service.ScannerService
import biz.lungo.wifiscanner.service.Scheduler
import biz.lungo.wifiscanner.util.formatLocalized
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import kotlinx.datetime.toJavaLocalDateTime
import java.time.LocalDateTime

class MainActivity : AppCompatActivity(),
    Scanner.OnStateChangedListener,
    WiFiAdapter.CheckboxListener,
    View.OnClickListener, Scheduler.ScheduleListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var availableAdapter: WiFiAdapter
    private lateinit var trackedAdapter: WiFiTrackedAdapter
    private val storage by lazy { Storage(this) }
    private val scheduler: Scheduler
        get() = WiFiScannerApplication.instance.scheduler
    private val scanner: Scanner
        get() = WiFiScannerApplication.instance.scanner
    private val bot: Bot
        get() = WiFiScannerApplication.instance.bot
    private val scanModeSpinner: Spinner?
        get() = binding.toolbar.menu.findItem(R.id.spinnerScanMode).actionView as? Spinner

    private val scannerServiceIntent: Intent by lazy {
        Intent(
            this,
            ScannerService::class.java
        )
    }

    private val pushNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        scanner.subscribe(this)
        scheduler.subscribe(this)
        if (storage.getKeepAwake()) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        availableAdapter = WiFiAdapter(storage, this)

        trackedAdapter = WiFiTrackedAdapter(object : WiFiTrackedAdapter.OnRemoveListener {
            override fun onRemove(wifi: WiFi) {
                availableAdapter.notifyItemChanged(wifi)
                trackedAdapter.removeNetwork(wifi)
                storage.removeNetwork(wifi)
                if (storage.getNetworks().isEmpty()) {
                    binding.stopScanning()
                    binding.btnScan.isEnabled = false
                }
            }
        })

        with(binding) {
            setContentView(root)
            toolbar.inflateMenu(R.menu.menu_main)
            (toolbar.menu.findItem(R.id.cbKeepAwake).actionView as? CheckBox)?.let { checkbox ->
                checkbox.layoutDirection = View.LAYOUT_DIRECTION_RTL
                checkbox.setText(R.string.keep_awake)
                checkbox.setTextColor(getColor(R.color.white))
                checkbox.isChecked = storage.getKeepAwake()
                checkbox.setOnCheckedChangeListener { _, isChecked ->
                    storage.setKeepAwake(isChecked)
                    if (isChecked) {
                        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
            }
            scanModeSpinner?.let { spinner ->
                spinner.adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, ScanMode.values())
                spinner.setSelection(ScanMode.values().indexOf(storage.getScanMode()))
                spinner.onItemSelectedListener = object : OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                        val selectedMode = ScanMode.values()[position]
                        storage.setScanMode(selectedMode)
                        binding.btnScan.isEnabled = storage.getNetworks().isNotEmpty() || selectedMode == ScanMode.Power
                    }

                    override fun onNothingSelected(p0: AdapterView<*>?) {
                    }

                }
                spinner.isEnabled = !scanner.isScanning
            }
            slider.min = SLIDER_MIN
            slider.max = SLIDER_MAX
            slider.progress = storage.getDelayValue()
            tvValue.text = "${storage.getDelayValue()}"
            btnScan.isEnabled = false
            setLastStatusText(tvLastStatus, storage.getLastStatus())
            tvNetworksCount.text = "~~~"
            val sendMessageChecked = storage.getSendMessageChecked()
            val sendReminderChecked = storage.getSendReminderChecked()
            cbSendRequest.isChecked = sendMessageChecked
            cbSendReminder.isChecked = sendReminderChecked
            bot.shouldSendMessage = sendMessageChecked && scanner.isScanning
            bot.shouldSendReminder = sendReminderChecked
            tvNextBlackout.text = scheduler.nextScheduledBlackout.toJavaLocalDateTime().formatLocalized()
            tvAvailableNetworksListTitle.isSelected = true
            val dividerItemDecoration = DividerItemDecoration(this@MainActivity, DividerItemDecoration.VERTICAL)
            rvNetworks.addItemDecoration(dividerItemDecoration)
            rvNetworks.adapter = availableAdapter
            tvAvailableNetworksListTitle.setOnClickListener(this@MainActivity)
            tvTrackingNetworksListTitle.setOnClickListener(this@MainActivity)
            btnScan.setOnClickListener(this@MainActivity)
            btnScanOnce.setOnClickListener(this@MainActivity)
            slider.setOnSeekBarChangeListener(SeekBarListener(tvValue))
            val botCheckboxListener = BotCheckboxListener()
            cbSendRequest.setOnCheckedChangeListener(botCheckboxListener)
            cbSendReminder.setOnCheckedChangeListener(botCheckboxListener)
            etNetworksThreshold.text = Editable.Factory.getInstance().newEditable(storage.getNetworksThreshold().toString())
            etNetworksThreshold.doOnTextChanged { text, _, _, _ ->
                storage.setNetworksThreshold(text.toString().toIntOrNull() ?: 10)
            }
        }
        requestBatteryOptimizationExemption()

        // Restore UI or restart service if it was running
        if (storage.getServiceRunning()) {
            if (!scanner.isScanning) {
                // Process was killed -- restart the service
                startService(scannerServiceIntent)
            }
            // Either way, reflect the active scanning state in the UI
            with(binding) {
                btnScan.text = getString(R.string.stop_scan)
                slider.isEnabled = false
                btnScanOnce.isEnabled = false
                scanModeSpinner?.isEnabled = false
            }
            bot.shouldSendMessage = storage.getSendMessageChecked()
        } else if (hasLocationPermission()) {
            binding.btnScanOnce.performClick()
        }
    }

    private fun ActivityMainBinding.stopScanning() {
        storage.setServiceRunning(false)
        stopService(scannerServiceIntent)
        slider.isEnabled = true
        btnScan.text = getString(R.string.start_scan)
        btnScanOnce.isEnabled = true
        scanModeSpinner?.isEnabled = true
        bot.shouldSendMessage = false
    }

    private fun setLastStatusText(textView: TextView, status: Status?) {
        textView.text = status?.let {
            val state = it.state.uppercase()
            val rawText = "${it.since?.formatLocalized() ?: ""} ($state)"
            SpannableString(rawText).apply {
                setSpan(
                    ForegroundColorSpan(getColor(status.textColor)),
                    rawText.indexOf(state),
                    rawText.indexOf(state) + state.length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        } ?: "---"
    }

    private fun checkPowerStatus() {
        scanner.onPowerStateChanged(scanner.hasPower(), true)
    }

    override fun onStateChanged(status: Status, lastStatusTime: LocalDateTime?) {
        setLastStatusText(binding.tvLastStatus, status)
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onNetworksReceived(networks: List<WiFi>) {
        val filtered = networks.map { WiFi(it.name, it.level) }.toSet()
        binding.btnScan.isEnabled = storage.getNetworks().isNotEmpty() || storage.getScanMode() == ScanMode.Power
        binding.btnScanOnce.isEnabled = !scanner.isScanning
        binding.tvNetworksCount.text = "${filtered.size}"
        availableAdapter.setNetworks(filtered)
    }

    override fun onScanComplete() {
        binding.tvLastScan.text = LocalDateTime.now().formatLocalized()
    }

    override fun onScanThrottled() {
        Toast.makeText(this, "Scan throttled", Toast.LENGTH_SHORT).show()
        with (binding) {
            root.postDelayed({
                btnScan.isEnabled = true
                btnScanOnce.isEnabled = !scanner.isScanning
            }, 2000)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!hasLocationPermission()) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQUEST_CODE_LOCATION)
        } else {
            pushNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun hasLocationPermission() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("BatteryLife")
    private fun requestBatteryOptimizationExemption() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scanner.unsubscribe(this)
        scheduler.unsubscribe(this)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        when (requestCode) {
            REQUEST_CODE_LOCATION -> {
                if (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "This won't work without permissions", Toast.LENGTH_LONG).show()
                } else {
                    pushNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    binding.btnScanOnce.performClick()
                }
            }
            else -> super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    override fun onCheckedChange(checked: Boolean, item: WiFi) {
        if (checked) {
            storage.addNetwork(item)
            binding.btnScan.isEnabled = true
        } else {
            storage.removeNetwork(item)
            if (storage.getNetworks().isEmpty()) {
                binding.stopScanning()
                binding.btnScan.isEnabled = false
            }
        }
    }

    override fun onSchedule(diffMinutes: Long) {
        Snackbar.make(binding.root, "Next blackout in $diffMinutes minutes", Snackbar.LENGTH_LONG).show()
    }

    override fun onNextBlackoutUpdated(nextBlackout: LocalDateTime) {
        lifecycleScope.launch {
            binding.tvNextBlackout.text = nextBlackout.formatLocalized()
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onClick(view: View) {
        when (view.id) {
            binding.btnScanOnce.id -> {
                view.isEnabled = false
                binding.btnScan.isEnabled = false
                scanner.scanOnce()
                checkPowerStatus()
            }
            binding.btnScan.id -> {
                if (!scanner.isScanning) {
                    binding.btnScan.text = getString(R.string.stop_scan)
                    binding.slider.isEnabled = false
                    binding.btnScanOnce.isEnabled = false
                    startService(scannerServiceIntent)
                    scanModeSpinner?.isEnabled = false
                    bot.shouldSendMessage = storage.getSendMessageChecked()
                } else {
                    binding.stopScanning()
                }
            }
            binding.tvAvailableNetworksListTitle.id -> {
                view.isSelected = true
                binding.tvTrackingNetworksListTitle.isSelected = false
                binding.rvNetworks.adapter = availableAdapter.apply { notifyDataSetChanged() }
            }
            binding.tvTrackingNetworksListTitle.id -> {
                binding.tvAvailableNetworksListTitle.isSelected = false
                view.isSelected = true
                binding.rvNetworks.adapter = trackedAdapter.apply {
                    items = storage.getNetworks().toMutableList()
                    notifyDataSetChanged()
                }
            }
        }
    }

    companion object {
        private const val SLIDER_MIN = 1
        private const val SLIDER_MAX = 15
        private const val REQUEST_CODE_LOCATION = 101
    }

    inner class SeekBarListener(private val tvValue: TextView) : OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar, value: Int, fromUser: Boolean) {
            if (fromUser) {
                storage.setDelayValue(value)
                tvValue.text = "$value"
            }
        }
        override fun onStartTrackingTouch(p0: SeekBar?) {}
        override fun onStopTrackingTouch(p0: SeekBar?) {}
    }

    inner class BotCheckboxListener : CompoundButton.OnCheckedChangeListener {
        override fun onCheckedChanged(view: CompoundButton, isChecked: Boolean) {
            if (isChecked && !bot.isBotEnabled) {
                Snackbar.make(binding.root, "Unable to enable bot. Please specify bot.api.key and chat.id in local.properties file", Snackbar.LENGTH_LONG).show()
                view.isChecked = false
            }
            when (view.id) {
                binding.cbSendRequest.id -> {
                    bot.shouldSendMessage = isChecked && scanner.isScanning
                    storage.setSendMessageChecked(isChecked && bot.isBotEnabled)
                }

                binding.cbSendReminder.id -> {
                    bot.shouldSendReminder = isChecked
                    storage.setSendReminderChecked(isChecked && bot.isBotEnabled)
                }
            }
        }
    }
}