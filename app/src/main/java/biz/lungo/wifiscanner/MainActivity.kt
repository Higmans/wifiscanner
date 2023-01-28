package biz.lungo.wifiscanner

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.SeekBar
import android.widget.SeekBar.OnSeekBarChangeListener
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DividerItemDecoration
import biz.lungo.wifiscanner.databinding.ActivityMainBinding
import com.google.android.material.snackbar.Snackbar
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

class MainActivity : AppCompatActivity(),
    Scanner.OnStateChangedListener,
    WiFiAdapter.CheckboxListener,
    View.OnClickListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var availableAdapter: WiFiAdapter
    private lateinit var trackedAdapter: WiFiTrackedAdapter
    private val storage by lazy { Storage(this) }
    private val scanner: Scanner
        get() = WiFiScannerApplication.instance.scanner
    private val bot: Bot
        get() = WiFiScannerApplication.instance.bot

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        scanner.subscribe(this)
        if (hasLocationPermission()) {
            scanner.scanOnce()
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
            slider.min = SLIDER_MIN
            slider.max = SLIDER_MAX
            slider.progress = storage.getDelayValue()
            tvValue.text = "${storage.getDelayValue()}"
            btnScan.isEnabled = false
            setLastStatusText(tvLastStatus, storage.getLastStatus())
            tvNetworksCount.text = "~~~"
            cbSendRequest.isChecked = storage.getChecked()
            bot.shouldSendMessage = storage.getChecked()
            tvAvailableNetworksListTitle.isSelected = true
            val dividerItemDecoration = DividerItemDecoration(this@MainActivity, DividerItemDecoration.VERTICAL)
            rvNetworks.addItemDecoration(dividerItemDecoration)
            rvNetworks.adapter = availableAdapter
            tvAvailableNetworksListTitle.setOnClickListener(this@MainActivity)
            tvTrackingNetworksListTitle.setOnClickListener(this@MainActivity)
            btnScan.setOnClickListener(this@MainActivity)
            btnScanOnce.setOnClickListener(this@MainActivity)
            slider.setOnSeekBarChangeListener(SeekBarListener(tvValue))
            cbSendRequest.setOnCheckedChangeListener { view, isChecked ->
                if (isChecked && !bot.isBotEnabled) {
                    Snackbar.make(binding.root, "Unable to enable bot. Please specify bot.api.key and chat.id in local.properties file", Snackbar.LENGTH_LONG).show()
                    view.isChecked = false
                }
                bot.shouldSendMessage = isChecked && bot.isBotEnabled
                storage.setChecked(isChecked && bot.isBotEnabled)
            }
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
            "${it.state.uppercase()} since ${it.since?.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)) ?: ""}"
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
        binding.tvLastScan.text = LocalDateTime.now().format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT))
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
}