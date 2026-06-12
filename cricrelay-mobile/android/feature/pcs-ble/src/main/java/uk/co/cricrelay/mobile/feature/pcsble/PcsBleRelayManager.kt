package uk.co.cricrelay.mobile.feature.pcsble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothDevice
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
import android.content.Context
import android.os.ParcelUuid
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class PcsBleUiState(
    val status: String = "Ready",
    val advertising: Boolean = false,
    val packetCount: Int = 0,
    val postedOk: Int = 0,
    val postFail: Int = 0,
    val recentPackets: List<String> = emptyList(),
    val ingestUrl: String = "",
    val bearerToken: String = "",
)

@Singleton
class PcsBleRelayManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow(PcsBleUiState())
    val state: StateFlow<PcsBleUiState> = _state.asStateFlow()

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var gattServer: BluetoothGattServer? = null
    private var advertising = false
    private val recentPackets = ArrayDeque<String>(12)

    init {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = manager.adapter
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _state.update {
            it.copy(
                ingestUrl = prefs.getString("ingest_url", "").orEmpty(),
                bearerToken = prefs.getString("bearer_token", "").orEmpty(),
            )
        }
    }

    fun updateSettings(ingestUrl: String, bearerToken: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("ingest_url", ingestUrl.trim())
            .putString("bearer_token", bearerToken.trim())
            .apply()
        _state.update { it.copy(ingestUrl = ingestUrl.trim(), bearerToken = bearerToken.trim()) }
    }

    @SuppressLint("MissingPermission")
    fun toggleAdvertise() {
        if (advertising) stopAdvertise() else startAdvertise()
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertise() {
        if (_state.value.ingestUrl.isBlank()) {
            _state.update { it.copy(status = "Set ingest URL first") }
            return
        }
        if (bluetoothAdapter?.isEnabled != true) {
            _state.update { it.copy(status = "Enable Bluetooth") }
            return
        }
        if (!startGattServer()) {
            _state.update { it.copy(status = "Could not start BLE server") }
            return
        }
        if (!startAdvertising()) {
            stopGattServer()
            _state.update { it.copy(status = "Could not advertise") }
            return
        }
        advertising = true
        _state.update { it.copy(advertising = true, status = "Advertising as ${PcsBle.PRESET_ADVERTISE_NAME}") }
    }

    @SuppressLint("MissingPermission")
    fun stopAdvertise() {
        stopAdvertising()
        stopGattServer()
        advertising = false
        _state.update { it.copy(advertising = false, status = "Stopped") }
    }

    @SuppressLint("MissingPermission")
    private fun startGattServer(): Boolean {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        gattServer?.close()
        gattServer = manager.openGattServer(context, gattServerCallback) ?: return false
        val service = BluetoothGattService(
            PcsBle.presetServiceUuid,
            BluetoothGattService.SERVICE_TYPE_PRIMARY,
        )
        val char = BluetoothGattCharacteristic(
            PcsBle.presetCharUuid,
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
            scope.launch {
                _state.update {
                    it.copy(
                        status = if (connected) {
                            "PCS connected: ${device.name ?: device.address}"
                        } else if (advertising) {
                            "Advertising — waiting for PCS"
                        } else {
                            "Stopped"
                        },
                    )
                }
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
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(PcsBle.presetServiceUuid))
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
            scope.launch {
                _state.update { it.copy(status = "Advertise failed ($errorCode)") }
            }
        }
    }

    private fun onPacket(data: ByteArray) {
        val line = decodePacket(data) ?: return
        val recentSnapshot = synchronized(recentPackets) {
            recentPackets.addFirst(line)
            while (recentPackets.size > 12) recentPackets.removeLast()
            recentPackets.toList()
        }
        scope.launch {
            _state.update {
                it.copy(
                    packetCount = it.packetCount + 1,
                    recentPackets = recentSnapshot,
                )
            }
            postToServer(line)
        }
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

    private suspend fun postToServer(line: String) {
        val url = _state.value.ingestUrl
        if (url.isBlank()) return
        val token = _state.value.bearerToken
        val builder = Request.Builder()
            .url(url)
            .post(JSONObject().put("line", line).toString().toRequestBody("application/json".toMediaType()))
        if (token.isNotEmpty()) {
            builder.header(
                "Authorization",
                if (token.startsWith("Bearer ")) token else "Bearer $token",
            )
        }
        try {
            val response = withContext(Dispatchers.IO) {
                http.newCall(builder.build()).execute()
            }
            response.use {
                _state.update {
                    if (response.isSuccessful) {
                        it.copy(postedOk = it.postedOk + 1)
                    } else {
                        it.copy(postFail = it.postFail + 1, status = "POST HTTP ${response.code}")
                    }
                }
            }
        } catch (e: IOException) {
            _state.update { it.copy(postFail = it.postFail + 1, status = "POST failed: ${e.message}") }
        }
    }

    private companion object {
        const val PREFS = "pcs_relay"
    }
}
