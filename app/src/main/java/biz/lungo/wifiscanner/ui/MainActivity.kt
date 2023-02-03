package biz.lungo.wifiscanner.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.SeekBar
import android.widget.SeekBar.OnSeekBarChangeListener
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import biz.lungo.wifiscanner.*
import biz.lungo.wifiscanner.data.Status
import biz.lungo.wifiscanner.data.Storage
import biz.lungo.wifiscanner.data.WiFi
import biz.lungo.wifiscanner.databinding.ActivityMainBinding
import biz.lungo.wifiscanner.service.Bot
import biz.lungo.wifiscanner.service.Scanner
import biz.lungo.wifiscanner.service.Scheduler
import biz.lungo.wifiscanner.util.formatLocalized
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import kotlinx.datetime.toJavaLocalDateTime
import java.time.LocalDateTime
import java.util.*

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
            slider.min = SLIDER_MIN
            slider.max = SLIDER_MAX
            slider.progress = storage.getDelayValue()
            tvValue.text = "${storage.getDelayValue()}"
            btnScan.isEnabled = false
            setLastStatusText(tvLastStatus, storage.getLastStatus())
            tvNetworksCount.text = "~~~"
            cbSendRequest.isChecked = storage.getSendMessageChecked()
            cbSendReminder.isChecked = storage.getSendReminderChecked()
            bot.shouldSendMessage = storage.getSendMessageChecked()
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
        }
        if (hasLocationPermission()) {
            binding.btnScanOnce.performClick()
        }
    }

    private fun ActivityMainBinding.stopScanning() {
        slider.isEnabled = true
        btnScan.text = getString(R.string.start_scan)
        scanner.stopScanning()
        btnScanOnce.isEnabled = true
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

    override fun onStateChanged(status: Status, lastStatusTime: LocalDateTime?) {
        setLastStatusText(binding.tvLastStatus, status)
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onNetworksReceived(networks: List<WiFi>) {
        val filtered = networks.map { WiFi(it.name, it.level) }.toSet()
        binding.btnScan.isEnabled = storage.getNetworks().isNotEmpty()
        binding.btnScanOnce.isEnabled = !scanner.isScanning()
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
                btnScanOnce.isEnabled = !scanner.isScanning()
            }, 2000)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!hasLocationPermission()) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQUEST_CODE_LOCATION)
        }
    }

    private fun hasLocationPermission() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

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
                if (grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "This won't work without permissions", Toast.LENGTH_LONG).show()
                } else {
                    scanner.scanOnce()
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
            }
            binding.btnScan.id -> {
                if (!scanner.isScanning()) {
                    binding.btnScan.text = getString(R.string.stop_scan)
                    binding.slider.isEnabled = false
                    scanner.startScanning(storage.getDelayValue())
                } else {
                    binding.stopScanning()
                }
                binding.btnScanOnce.isEnabled = !scanner.isScanning()
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
                    bot.shouldSendMessage = isChecked
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