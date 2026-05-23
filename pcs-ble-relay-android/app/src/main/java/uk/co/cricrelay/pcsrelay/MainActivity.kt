package uk.co.cricrelay.pcsrelay

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.Spinner
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
    private lateinit var helpText: TextView
    private lateinit var packetsText: TextView
    private lateinit var logText: TextView
    private lateinit var deviceList: ListView
    private lateinit var scanBtn: Button

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
    private var gattServer: BluetoothGattServer? = null
    private var scanning = false
    private var advertising = false
    private var packetCount = 0
    private var postedOk = 0
    private var postFail = 0

    private val postQueue = ArrayDeque<String>(128)
    private val postRetryQueue = ArrayDeque<String>(64)
    private var postDrainScheduled = false
    private var postInFlight = false

    private val prefs by lazy { getSharedPreferences("pcs_relay", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        statsText = findViewById(R.id.statsText)
        helpText = findViewById(R.id.helpText)
        packetsText = findViewById(R.id.packetsText)
        logText = findViewById(R.id.logText)
        deviceList = findViewById(R.id.deviceList)
        scanBtn = findViewById(R.id.scanBtn)
        findViewById<Button>(R.id.settingsBtn).setOnClickListener { openSettings() }
        findViewById<Button>(R.id.disconnectBtn).setOnClickListener { stopAll() }
        scanBtn.setOnClickListener { onPrimaryAction() }

        updateHelpText()
        scanBtn.text = if (bleMode() == PcsBle.MODE_ADVERTISE_BOARD) "Start relay" else "Scan BLE"

        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = manager.adapter
        if (bluetoothAdapter == null) {
            setStatus("Bluetooth not available")
            return
        }
        updateStats()
        setStatus("Ready — Settings: ingest URL + BLE mode")
    }

    override fun onDestroy() {
        stopAll()
        super.onDestroy()
    }

    private fun bleMode(): String =
        prefs.getString("ble_mode", PcsBle.MODE_ADVERTISE_BOARD) ?: PcsBle.MODE_ADVERTISE_BOARD

    private fun serviceUuidFilter(): String? {
        val custom = prefs.getString("service_uuid", "")?.trim().orEmpty()
        if (custom.isNotEmpty()) return custom
        if (bleMode() == PcsBle.MODE_SCAN_PCS_PRESET) return PcsBle.PRESET_SERVICE_UUID
        return null
    }

    private fun charUuidFilter(): String? {
        val custom = prefs.getString("char_uuid", "")?.trim().orEmpty()
        if (custom.isNotEmpty()) return custom
        if (bleMode() == PcsBle.MODE_SCAN_PCS_PRESET) return PcsBle.PRESET_CHAR_UUID
        return null
    }

    private fun updateHelpText() {
        helpText.text = when (bleMode()) {
            PcsBle.MODE_ADVERTISE_BOARD ->
                "Scoreboard mode (buildyourownscoreboard):\n" +
                    "Tap Scan/Start → phone advertises as ${PcsBle.PRESET_ADVERTISE_NAME}.\n" +
                    "PCS → External Scoreboard → Generic → connect to that name.\n\n" +
                    "Preset service: ${PcsBle.PRESET_SERVICE_UUID}"
            PcsBle.MODE_SCAN_PCS_PRESET ->
                "Scan for PCS preset service UUID (verify with nRF Connect).\n" +
                    "Tap a device, then we subscribe to the preset characteristic if found.\n\n" +
                    "Service: ${PcsBle.PRESET_SERVICE_UUID}\n" +
                    "Char: ${PcsBle.PRESET_CHAR_UUID}"
            else ->
                "Scan all BLE devices — tap one to connect and subscribe to all notify characteristics.\n" +
                    "Use this to discover unknown PCS UUIDs, then switch to PCS preset in Settings."
        }
    }

    private fun openSettings() {
        val ingest = EditText(this).apply {
            setText(prefs.getString("ingest_url", ""))
            hint = "CricRelay ingest URL"
        }
        val token = EditText(this).apply {
            setText(prefs.getString("bearer_token", ""))
            hint = "Bearer token"
        }
        val modeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf(
                    "Scan all devices",
                    "Scan PCS preset UUIDs",
                    "Advertise as scoreboard (PCS connects here)",
                ),
            )
            setSelection(
                when (bleMode()) {
                    PcsBle.MODE_SCAN_PCS_PRESET -> 1
                    PcsBle.MODE_ADVERTISE_BOARD -> 2
                    else -> 0
                },
            )
        }
        val svc = EditText(this).apply {
            setText(prefs.getString("service_uuid", ""))
            hint = "Custom service UUID (optional override)"
        }
        val chr = EditText(this).apply {
            setText(prefs.getString("char_uuid", ""))
            hint = "Custom characteristic UUID (optional)"
        }
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(ingest)
            addView(token)
            addView(modeSpinner)
            addView(svc)
            addView(chr)
        }
        AlertDialog.Builder(this)
            .setTitle("CricRelay settings")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val mode = when (modeSpinner.selectedItemPosition) {
                    1 -> PcsBle.MODE_SCAN_PCS_PRESET
                    2 -> PcsBle.MODE_ADVERTISE_BOARD
                    else -> PcsBle.MODE_SCAN_ALL
                }
                prefs.edit()
                    .putString("ingest_url", ingest.text.toString().trim())
                    .putString("bearer_token", token.text.toString().trim())
                    .putString("ble_mode", mode)
                    .putString("service_uuid", svc.text.toString().trim())
                    .putString("char_uuid", chr.text.toString().trim())
                    .apply()
                updateHelpText()
                scanBtn.text = if (mode == PcsBle.MODE_ADVERTISE_BOARD) "Start relay" else "Scan BLE"
                toast("Saved")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun onPrimaryAction() {
        if (bleMode() == PcsBle.MODE_ADVERTISE_BOARD) {
            if (advertising) stopAdvertiseMode() else startAdvertiseMode()
        } else {
            if (scanning) stopScan() else startScan()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (!ensurePermissions(advertise = false)) return
        if (bluetoothAdapter?.isEnabled != true) {
            toast("Enable Bluetooth first")
            return
        }
        stopAdvertiseMode()
        discovered.clear()
        refreshDeviceList()
        deviceList.visibility = View.VISIBLE
        scanning = true
        scanBtn.text = "Stop scan"
        setStatus("Scanning…")
        val filters = mutableListOf<ScanFilter>()
        val svc = serviceUuidFilter()
        if (!svc.isNullOrEmpty()) {
            try {
                filters.add(
                    ScanFilter.Builder()
                        .setServiceUuid(ParcelUuid(UUID.fromString(svc)))
                        .build(),
                )
                log("Scan filter: $svc")
            } catch (e: Exception) {
                log("Invalid service UUID: $svc")
            }
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (filters.isEmpty()) {
            scanner?.startScan(scanCallback)
        } else {
            scanner?.startScan(filters, settings, scanCallback)
        }
        handler.postDelayed({ if (scanning) stopScan() }, 25_000)
        log("Scan started")
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!scanning) return
        scanning = false
        scanBtn.text = if (bleMode() == PcsBle.MODE_ADVERTISE_BOARD) "Start relay" else "Scan BLE"
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        setStatus("Scan stopped")
        log("Scan stopped")
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            if (!discovered.containsKey(device.address)) {
                discovered[device.address] = device
                runOnUiThread { refreshDeviceList() }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun refreshDeviceList() {
        val labels = discovered.map { (_, dev) ->
            val name = dev.name?.takeIf { it.isNotBlank() } ?: "(unnamed)"
            "$name\n${dev.address}"
        }
        deviceList.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        deviceList.setOnItemClickListener { _, _, pos, _ ->
            val device = discovered.values.elementAtOrNull(pos) ?: return@setOnItemClickListener
            stopScan()
            connectCentral(device)
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectCentral(device: BluetoothDevice) {
        disconnectCentral()
        setStatus("Connecting to ${device.name ?: device.address}…")
        log("Connecting ${device.address}")
        gatt = device.connectGatt(this, false, gattCallback)
    }

    @SuppressLint("MissingPermission")
    private fun disconnectCentral() {
        gatt?.close()
        gatt = null
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
            val svcFilter = serviceUuidFilter()?.lowercase()
            val charFilter = charUuidFilter()?.lowercase()
            var subs = 0
            for (service in gatt.services) {
                val svc = service.uuid.toString().lowercase()
                if (!svcFilter.isNullOrEmpty() && !svc.contains(svcFilter.replace("-", "")) &&
                    !svc.contains(svcFilter)
                ) {
                    continue
                }
                for (char in service.characteristics) {
                    val cu = char.uuid.toString().lowercase()
                    if (!charFilter.isNullOrEmpty() && !cu.contains(charFilter.replace("-", "")) &&
                        !cu.contains(charFilter)
                    ) {
                        continue
                    }
                    val props = char.properties
                    val notify = (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                    val indicate = (props and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                    if (!notify && !indicate) continue
                    gatt.setCharacteristicNotification(char, true)
                    val cccd = char.getDescriptor(PcsBle.cccdUuid)
                    if (cccd != null) {
                        cccd.value = if (indicate) {
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
            runOnUiThread {
                setStatus(
                    if (subs > 0) "Relaying ($subs ch)" else "No matching notify characteristics",
                )
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            onPacket(value)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            @Suppress("DEPRECATION")
            characteristic.value?.let { onPacket(it) }
        }
    }

    // --- Advertise as scoreboard (optional; matches buildyourownscoreboard flow) ---

    @SuppressLint("MissingPermission")
    private fun startAdvertiseMode() {
        if (!ensurePermissions(advertise = true)) return
        if (prefs.getString("ingest_url", "")?.trim().isNullOrEmpty()) {
            toast("Set ingest URL in Settings first")
            return
        }
        stopScan()
        deviceList.visibility = View.GONE
        if (!startGattServer()) {
            toast("Could not start BLE server")
            return
        }
        if (!startAdvertising()) {
            stopGattServer()
            toast("Could not advertise")
            return
        }
        advertising = true
        scanBtn.text = "Stop relay"
        setStatus("Advertising as ${PcsBle.PRESET_ADVERTISE_NAME}")
        log("Scoreboard advertise mode")
    }

    @SuppressLint("MissingPermission")
    private fun stopAdvertiseMode() {
        stopAdvertising()
        stopGattServer()
        advertising = false
        scanBtn.text = "Start relay"
        deviceList.visibility = View.VISIBLE
    }

    @SuppressLint("MissingPermission")
    private fun startGattServer(): Boolean {
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        gattServer?.close()
        gattServer = manager.openGattServer(this, gattServerCallback) ?: return false
        val svcId = try {
            UUID.fromString(serviceUuidFilter() ?: PcsBle.PRESET_SERVICE_UUID)
        } catch (_: Exception) {
            PcsBle.presetServiceUuid
        }
        val charId = try {
            UUID.fromString(charUuidFilter() ?: PcsBle.PRESET_CHAR_UUID)
        } catch (_: Exception) {
            PcsBle.presetCharUuid
        }
        val service = BluetoothGattService(svcId, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val char = BluetoothGattCharacteristic(
            charId,
            BluetoothGattCharacteristic.PROPERTY_READ or
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ or
                BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val cccd = BluetoothGattDescriptor(
            PcsBle.cccdUuid,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
        )
        char.addDescriptor(cccd)
        service.addCharacteristic(char)
        return gattServer?.addService(service) == true
    }

    private fun stopGattServer() {
        gattServer?.close()
        gattServer = null
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val connected = newState == BluetoothProfile.STATE_CONNECTED
            runOnUiThread {
                setStatus(
                    if (connected) "PCS connected: ${device.name ?: device.address}"
                    else if (advertising) "Advertising — waiting for PCS"
                    else "Stopped",
                )
                log(if (connected) "PCS connected" else "PCS disconnected")
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
            val expected = charUuidFilter() ?: PcsBle.PRESET_CHAR_UUID
            if (!characteristic.uuid.toString().equals(expected, ignoreCase = true)) {
                return
            }
            onPacket(value)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising(): Boolean {
        val advertiser = bluetoothAdapter?.bluetoothLeAdvertiser ?: return false
        try {
            bluetoothAdapter?.name = PcsBle.PRESET_ADVERTISE_NAME
        } catch (_: SecurityException) {
        }
        val svc = try {
            ParcelUuid(UUID.fromString(serviceUuidFilter() ?: PcsBle.PRESET_SERVICE_UUID))
        } catch (_: Exception) {
            ParcelUuid(PcsBle.presetServiceUuid)
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(svc)
            .build()
        advertiser.startAdvertising(settings, data, advertiseCallback)
        return true
    }

    @SuppressLint("MissingPermission")
    private fun stopAdvertising() {
        try {
            bluetoothAdapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        } catch (_: Exception) {
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            log("Advertise failed: $errorCode")
            runOnUiThread { toast("Advertise failed ($errorCode)") }
        }
    }

    private fun stopAll() {
        stopScan()
        stopAdvertiseMode()
        disconnectCentral()
        setStatus("Stopped")
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

    private fun looksLikePcs(s: String) = s.length >= 3 && s.substring(0, 3).all { it.isLetter() }

    private fun enqueuePost(line: String) {
        synchronized(postQueue) {
            if (postQueue.size >= 120) postQueue.removeLast()
            postQueue.addFirst(line)
        }
        schedulePostDrain()
    }

    private fun schedulePostDrain() {
        if (postDrainScheduled) return
        postDrainScheduled = true
        handler.post { drainPostQueue() }
    }

    private fun drainPostQueue() {
        if (postInFlight) return
        val line = synchronized(postQueue) { postQueue.pollLast() }
            ?: synchronized(postRetryQueue) { postRetryQueue.pollFirst() }
        if (line == null) {
            postDrainScheduled = false
            return
        }
        postInFlight = true
        val url = prefs.getString("ingest_url", "")?.trim().orEmpty()
        if (url.isEmpty()) {
            postInFlight = false
            postDrainScheduled = false
            return
        }
        val token = prefs.getString("bearer_token", "")?.trim().orEmpty()
        val builder = Request.Builder()
            .url(url)
            .post(JSONObject().put("line", line).toString().toRequestBody("application/json".toMediaType()))
        if (token.isNotEmpty()) {
            builder.header(
                "Authorization",
                if (token.startsWith("Bearer ")) token else "Bearer $token",
            )
        }
        http.newCall(builder.build()).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                postFail++
                log("POST failed: ${e.message}")
                synchronized(postRetryQueue) {
                    if (postRetryQueue.size < 60) postRetryQueue.addLast(line)
                }
                postInFlight = false
                handler.postDelayed({ schedulePostDrain() }, 2500)
                runOnUiThread { updateStats() }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    if (it.isSuccessful) {
                        postedOk++
                    } else {
                        postFail++
                        synchronized(postRetryQueue) {
                            if (postRetryQueue.size < 60) postRetryQueue.addLast(line)
                        }
                        handler.postDelayed({ schedulePostDrain() }, 2500)
                    }
                    postInFlight = false
                    schedulePostDrain()
                    runOnUiThread { updateStats() }
                }
            }
        })
    }

    private fun postToServer(line: String) {
        enqueuePost(line)
    }

    private fun ensurePermissions(advertise: Boolean): Boolean {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED
            ) {
                needed.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) {
                needed.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (advertise && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                needed.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            }
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
        runOnUiThread { logText.text = logLines.joinToString("\n") }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
