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
import nl.mirrajabi.humanize.duration.DurationHumanizer
import retrofit2.Retrofit
import retrofit2.converter.scalars.ScalarsConverterFactory
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit

class MainActivity : AppCompatActivity(),
    Scanner.OnStateChangedListener,
    WiFiAdapter.CheckboxListener,
    View.OnClickListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var storage: Storage
    private lateinit var webhookApiService: WebhookApi
    private lateinit var scanService: Scanner
    private lateinit var availableAdapter: WiFiAdapter
    private lateinit var trackedAdapter: WiFiTrackedAdapter
    private val bot = Bot(WiFiScannerApplication.instance?.botApiKey, WiFiScannerApplication.instance?.botChatId)
    private val humanizer = DurationHumanizer()
    private val languages = mapOf("ukr" to UkrainianDictionary())
    private val humanizerOptions = DurationHumanizer.Options(language = "Ukrainian", delimiter = "", languages = languages, fallbacks = listOf("ukr"))
    private val retrofit = Retrofit.Builder()
        .baseUrl("https://maker.ifttt.com/trigger/HasWifi/json/with/key/")
        .addConverterFactory(ScalarsConverterFactory.create())
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        storage = Storage(this)
        webhookApiService = retrofit.create(WebhookApi::class.java)
        scanService = Scanner(applicationContext)
        scanService.subscribe(this)
        if (hasLocationPermission()) {
            scanService.scanOnce()
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
            tvAvailableNetworksListTitle.isSelected = true
            val dividerItemDecoration = DividerItemDecoration(this@MainActivity, DividerItemDecoration.VERTICAL)
            rvNetworks.addItemDecoration(dividerItemDecoration)
            rvNetworks.adapter = availableAdapter
            tvAvailableNetworksListTitle.setOnClickListener(this@MainActivity)
            tvTrackingNetworksListTitle.setOnClickListener(this@MainActivity)
            slider.setOnSeekBarChangeListener(object : OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, value: Int, fromUser: Boolean) {
                    if (fromUser) {
                        storage.setDelayValue(value)
                        tvValue.text = "$value"
                    }
                }
                override fun onStartTrackingTouch(p0: SeekBar?) {}
                override fun onStopTrackingTouch(p0: SeekBar?) {}
            })
            btnScan.setOnClickListener {
                if (!scanService.isScanning()) {
                    btnScan.text = getString(R.string.stop_scan)
                    slider.isEnabled = false
                    scanService.startScanning(storage.getDelayValue())
                } else {
                    stopScanning()
                }
                btnScanOnce.isEnabled = !scanService.isScanning()
            }
            btnScanOnce.setOnClickListener {
                it.isEnabled = false
                binding.btnScan.isEnabled = false
                scanService.scanOnce()
            }
            cbSendRequest.setOnCheckedChangeListener { _, isChecked ->
                storage.setChecked(isChecked)
            }
        }
    }

    private fun ActivityMainBinding.stopScanning() {
        slider.isEnabled = true
        btnScan.text = getString(R.string.start_scan)
        scanService.stopScanning()
        btnScanOnce.isEnabled = true
    }

    private fun setLastStatusText(textView: TextView, status: Status?) {
        textView.text = status?.let {
            "${it.state.uppercase()} since ${it.since?.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)) ?: ""}"
        } ?: "---"
    }

    override fun onStateChanged(status: Status, lastStatusTime: LocalDateTime?) {
        setLastStatusText(binding.tvLastStatus, status)
        println(formatMessage(status, lastStatusTime))
        if (binding.cbSendRequest.isChecked) {
            if (bot.sendMessage(formatMessage(status, lastStatusTime))) {
                Snackbar.make(binding.root, "Message sent", Snackbar.LENGTH_SHORT).show()
            } else {
                Snackbar.make(binding.root, "Message not sent. Please specify bot.api.key and chat.id in local.properties file", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun formatMessage(status: Status, lastStatusTime: LocalDateTime?): String {
        return when (status) {
            is Status.Online -> {
                val humanDuration = formatHumanDuration(status.since, lastStatusTime)
                val readableSince = humanDuration?.let { "${br}Його не було $it" } ?: ""
                "<b>Світло ввімкнули!</b> \uD83D\uDCA1$readableSince"
            }
            is Status.Offline -> {
                "Світло вимкнули... ❌"
            }
        }
    }

    private fun formatHumanDuration(
        current: LocalDateTime?,
        lastStatusTime: LocalDateTime?
    ): String? {
        return if (current != null && lastStatusTime != null) {
            val zdtCurrent: ZonedDateTime = current.truncatedTo(ChronoUnit.MINUTES).atZone(ZoneId.systemDefault())
            val zdtLast: ZonedDateTime = lastStatusTime.truncatedTo(ChronoUnit.MINUTES).atZone(ZoneId.systemDefault())
            val diff = zdtCurrent.toInstant().toEpochMilli() - zdtLast.toInstant().toEpochMilli()
            humanizer.humanize(diff, humanizerOptions)
        } else {
            null
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onNetworksReceived(networks: List<WiFi>) {
        val filtered = networks.map { WiFi(it.name, it.level) }.toSet()
        binding.btnScan.isEnabled = storage.getNetworks().isNotEmpty()
        binding.btnScanOnce.isEnabled = !scanService.isScanning()
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
                btnScanOnce.isEnabled = !scanService.isScanning()
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
        scanService.unsubscribe(this)
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
                    scanService.scanOnce()
                }
            }
            else -> super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    companion object {
        private const val SLIDER_MIN = 1
        private const val SLIDER_MAX = 15
        private const val REQUEST_CODE_LOCATION = 101
        private val br = System.lineSeparator()
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
            R.id.tvAvailableNetworksListTitle -> {
                view.isSelected = true
                binding.tvTrackingNetworksListTitle.isSelected = false
                binding.rvNetworks.adapter = availableAdapter.apply { notifyDataSetChanged() }
            }
            R.id.tvTrackingNetworksListTitle -> {
                binding.tvAvailableNetworksListTitle.isSelected = false
                view.isSelected = true
                binding.rvNetworks.adapter = trackedAdapter.apply {
                    items = storage.getNetworks().toMutableList()
                    notifyDataSetChanged()
                }
            }
        }
    }
}