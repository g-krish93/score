package uk.co.cricrelay.pcsrelay

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.nio.charset.Charset
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.collections.ArrayDeque

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var statsText: TextView
    private lateinit var packetsText: TextView
    private lateinit var logText: TextView
    private lateinit var deviceList: ListView
    private lateinit var scanBtn: Button
    private lateinit var disconnectBtn: Button

    private val handler = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    private val logLines = ArrayDeque<String>(80)
    private val recentPackets = ArrayDeque<String>(12)
    private val discovered = LinkedHashMap<String, BluetoothDevice>()

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var gatt: BluetoothGatt? = null
    private var scanning = false
    private var packetCount = 0
    private var postedOk = 0
    private var postFail = 0

    private val prefs by lazy { getSharedPreferences("pcs_relay", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        statsText = findViewById(R.id.statsText)
        packetsText = findViewById(R.id.packetsText)
        logText = findViewById(R.id.logText)
        deviceList = findViewById(R.id.deviceList)
        scanBtn = findViewById(R.id.scanBtn)
        disconnectBtn = findViewById(R.id.disconnectBtn)
        findViewById<Button>(R.id.settingsBtn).setOnClickListener { openSettings() }
        scanBtn.setOnClickListener { toggleScan() }
        disconnectBtn.setOnClickListener { disconnect() }

        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = manager.adapter
        if (bluetoothAdapter == null) {
            setStatus("Bluetooth not available")
            return
        }
        updateStats()
        setStatus("Ready — configure settings from CricRelay dashboard")
    }

    override fun onDestroy() {
        stopScan()
        disconnect()
        super.onDestroy()
    }

    private fun openSettings() {
        val ingest = EditText(this).apply {
            setText(prefs.getString("ingest_url", ""))
            hint = "https://cricrelay.co.uk/relay/pcs-ingest?match=slug"
        }
        val token = EditText(this).apply {
            setText(prefs.getString("bearer_token", ""))
            hint = "Bearer token from dashboard"
        }
        val svc = EditText(this).apply {
            setText(prefs.getString("service_uuid", ""))
            hint = "Optional service UUID filter"
        }
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(ingest)
            addView(token)
            addView(svc)
        }
        AlertDialog.Builder(this)
            .setTitle("CricRelay settings")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit()
                    .putString("ingest_url", ingest.text.toString().trim())
                    .putString("bearer_token", token.text.toString().trim())
                    .putString("service_uuid", svc.text.toString().trim())
                    .apply()
                toast("Saved")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toggleScan() {
        if (scanning) {
            stopScan()
        } else {
            if (!ensurePermissions()) return
            if (bluetoothAdapter?.isEnabled != true) {
                toast("Enable Bluetooth first")
                return
            }
            startScan()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        discovered.clear()
        refreshDeviceList()
        scanning = true
        scanBtn.text = "Stop scan"
        setStatus("Scanning…")
        bluetoothAdapter?.bluetoothLeScanner?.startScan(scanCallback)
        handler.postDelayed({ if (scanning) stopScan() }, 20_000)
        log("Scan started")
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!scanning) return
        scanning = false
        scanBtn.text = "Scan BLE"
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        setStatus("Scan stopped")
        log("Scan stopped")
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val key = device.address
            if (!discovered.containsKey(key)) {
                discovered[key] = device
                runOnUiThread { refreshDeviceList() }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun refreshDeviceList() {
        val labels = discovered.map { (addr, dev) ->
            val name = dev.name?.takeIf { it.isNotBlank() } ?: "(unnamed)"
            "$name\n$addr"
        }
        deviceList.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        deviceList.setOnItemClickListener { _, _, pos, _ ->
            val device = discovered.values.elementAtOrNull(pos) ?: return@setOnItemClickListener
            stopScan()
            connect(device)
        }
    }

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice) {
        disconnect()
        setStatus("Connecting to ${device.name ?: device.address}…")
        log("Connecting ${device.address}")
        gatt = device.connectGatt(this, false, gattCallback)
    }

    @SuppressLint("MissingPermission")
    private fun disconnect() {
        gatt?.close()
        gatt = null
        setStatus("Disconnected")
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                runOnUiThread {
                    setStatus("Connected — discovering services")
                    log("GATT connected")
                }
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                runOnUiThread {
                    setStatus("Disconnected")
                    log("GATT disconnected")
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("Service discovery failed: $status")
                return
            }
            val filter = prefs.getString("service_uuid", "")?.trim()?.lowercase()
            var subs = 0
            for (service in gatt.services) {
                val svc = service.uuid.toString().lowercase()
                if (!filter.isNullOrEmpty() && !svc.contains(filter)) continue
                for (char in service.characteristics) {
                    val props = char.properties
                    val notifiable = (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                    val indicatable = (props and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                    if (notifiable || indicatable) {
                        gatt.setCharacteristicNotification(char, true)
                        val cccd = char.getDescriptor(
                            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
                        )
                        if (cccd != null) {
                            cccd.value = if (indicatable) {
                                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                            } else {
                                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            }
                            gatt.writeDescriptor(cccd)
                        }
                        subs++
                        log("Subscribed ${char.uuid}")
                    }
                }
            }
            runOnUiThread {
                setStatus(if (subs > 0) "Relaying ($subs ch)" else "No notify characteristics found")
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            onPacket(value)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: return
            onPacket(value)
        }
    }

    private fun onPacket(data: ByteArray) {
        val line = decodePacket(data) ?: return
        packetCount++
        synchronized(recentPackets) {
            recentPackets.addFirst(line)
            while (recentPackets.size > 12) recentPackets.removeLast()
        }
        runOnUiThread {
            updateStats()
            packetsText.text = recentPackets.joinToString("\n")
        }
        postToServer(line)
    }

    private fun decodePacket(data: ByteArray): String? {
        if (data.isEmpty()) return null
        val utf = try {
            String(data, Charset.forName("UTF-8")).trim()
        } catch (_: Exception) {
            ""
        }
        if (utf.length >= 3 && looksLikePcs(utf)) return utf
        val ascii = data.filter { it in 32..126 }.map { it.toInt().toChar() }.joinToString("").trim()
        return ascii.takeIf { it.length >= 3 && looksLikePcs(it) }
    }

    private fun looksLikePcs(s: String): Boolean {
        return s.length >= 3 && s.substring(0, 3).all { it.isLetter() }
    }

    private fun postToServer(line: String) {
        val url = prefs.getString("ingest_url", "")?.trim().orEmpty()
        if (url.isEmpty()) return
        val token = prefs.getString("bearer_token", "")?.trim().orEmpty()
        val body = JSONObject().put("line", line).toString()
        val builder = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json".toMediaType()))
        if (token.isNotEmpty()) {
            val auth = if (token.startsWith("Bearer ")) token else "Bearer $token"
            builder.header("Authorization", auth)
        }
        http.newCall(builder.build()).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                postFail++
                log("POST failed: ${e.message}")
                runOnUiThread { updateStats() }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    if (it.isSuccessful) postedOk++ else {
                        postFail++
                        log("POST ${it.code}")
                    }
                    runOnUiThread { updateStats() }
                }
            }
        })
    }

    private fun ensurePermissions(): Boolean {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED
            ) needed.add(Manifest.permission.BLUETOOTH_SCAN)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) needed.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 1001)
            return false
        }
        return true
    }

    private fun setStatus(msg: String) {
        statusText.text = "Status: $msg"
    }

    private fun updateStats() {
        statsText.text = "BLE packets: $packetCount  |  Posted OK: $postedOk  |  POST fails: $postFail"
    }

    private fun log(msg: String) {
        val line = "${java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date())} $msg"
        synchronized(logLines) {
            logLines.addFirst(line)
            while (logLines.size > 40) logLines.removeLast()
        }
        runOnUiThread {
            logText.text = logLines.joinToString("\n")
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
