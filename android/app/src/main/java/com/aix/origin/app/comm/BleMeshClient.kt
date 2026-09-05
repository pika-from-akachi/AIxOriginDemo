package com.aix.origin.app.comm

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.aix.origin.app.comm.GatewayParser.Message
import java.util.UUID

/**
 * BLE 客户端 —— 通过 Nordic UART Service(NUS) 桥接 ESP32-S3 网关：
 *   RX 6E400002-B5A3-F393-E0A9-E50E24DCCA9E (手机写 -> 网关收)
 *   TX 6E400003-B5A3-F393-E0A9-E50E24DCCA9E (网关发 -> 手机收)
 *
 * 网关侧把 ESP-NOW mesh 汇聚的灾情帧经 UART 逐条下发，本类按行还原为字符串，
 * 交给 [GatewayParser] 解析。
 */
class BleMeshClient(private val context: Context) {

    sealed class Event {
        data class Status(val text: String) : Event()
        data class Frame(val msg: Message) : Event()
    }

    var onEvent: (Event) -> Unit = {}

    @Volatile
    private var running = false

    private val handler = Handler(Looper.getMainLooper())
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        mgr?.adapter
    }
    private val scanner by lazy { bluetoothAdapter?.bluetoothLeScanner }

    private var gatt: BluetoothGatt? = null
    private var device: BluetoothDevice? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device ?: return
            val name = dev.name ?: return
            if (name.startsWith(nodeNamePrefix)) {
                connect(dev)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            status("BLE 扫描失败(code=$errorCode)")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, statusCode: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    status("网关已断开")
                    closeGatt()
                    if (running) scheduleRescan()
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, statusCode: Int) {
            if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                status("服务发现失败($statusCode)")
                g.disconnect()
                return
            }
            val service = g.getService(NUS_SERVICE_UUID) ?: run {
                status("未找到 NUS 服务")
                g.disconnect()
                return
            }
            val rx = service.getCharacteristic(NUS_CHAR_RX)
            val tx = service.getCharacteristic(NUS_CHAR_TX) ?: run {
                status("未找到 TX 特征")
                g.disconnect()
                return
            }
            txCharacteristic = rx
            // 订阅网关->手机的通知
            g.setCharacteristicNotification(tx, true)
            val cccd = tx.getDescriptor(CCCD_UUID)
            if (cccd != null) {
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(cccd)
                }
            }
            status("网关已接入 (${device?.name})")
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            // 按行还原 UTF-8 文本
            onBytes(value)
        }

        // Android 12 及以下走旧回调
        @Deprecated("Deprecated in Java")
        @SuppressLint("MissingPermission")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val value = try {
                characteristic.value
            } catch (e: Exception) {
                null
            }
            if (value != null) onBytes(value)
        }
    }

    private val pending = StringBuilder()
    private fun onBytes(bytes: ByteArray) {
        pending.append(String(bytes, Charsets.UTF_8))
        while (true) {
            val nl = pending.indexOf("\n")
            if (nl < 0) break
            val line = pending.substring(0, nl).trim()
            pending.delete(0, nl + 1)
            if (line.isEmpty()) continue
            handleLine(line)
        }
        // 容错：缓冲过大时丢弃
        if (pending.length > 4096) pending.setLength(0)
    }

    private fun handleLine(line: String) {
        val msg = GatewayParser.parse(line)
        if (msg is Message.Unsupported) {
            Log.d(TAG, "未识别下行帧: $line")
        }
        onEvent(Event.Frame(msg))
    }

    /** 开始扫描并连接名称为 AIx* 的网关节点 */
    fun start(namePrefix: String = NODE_NAME_PREFIX) {
        if (running) return
        running = true
        nodeNamePrefix = namePrefix
        status("正在扫描蓝牙网关…")
        startScan()
    }

    @Volatile
    private var nodeNamePrefix: String = NODE_NAME_PREFIX

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (!running) return
        if (!hasScanPermission()) {
            status("缺少蓝牙扫描权限")
            return
        }
        if (scanner == null) {
            status("设备不支持 BLE")
            return
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner?.startScan(null, settings, scanCallback)
        // 8 秒后停止扫描（找不到则进入重试周期）
        handler.postDelayed({ scanner?.stopScan(scanCallback) }, SCAN_TIMEOUT_MS)
    }

    @SuppressLint("MissingPermission")
    private fun connect(dev: BluetoothDevice) {
        if (!running) return
        scanner?.stopScan(scanCallback)
        device = dev
        status("连接 ${dev.name}…")
        try {
            gatt = dev.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: Exception) {
            status("连接失败: ${e.message}")
            scheduleRescan()
        }
    }

    private fun scheduleRescan() {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            if (running) {
                status("正在重连蓝牙网关…")
                startScan()
            }
        }, RETRY_DELAY_MS)
    }

    /** 通过 BLE 上行（手机 GPS / SOS） */
    @SuppressLint("MissingPermission")
    fun send(data: String): Boolean {
        val ch = txCharacteristic ?: return false
        val bytes = (data + "\n").toByteArray(Charsets.UTF_8)
        return try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                // API 33+ 的 writeCharacteristic 返回 int 状态码（0=成功），旧 API 返回 boolean
                gatt?.writeCharacteristic(ch, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                    BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                ch.value = bytes
                gatt?.writeCharacteristic(ch) == true
            }
        } catch (e: Exception) {
            Log.w(TAG, "BLE 发送失败: ${e.message}")
            false
        }
    }

    fun isConnected(): Boolean {
        val g = gatt ?: return false
        return try {
            bluetoothAdapter?.getProfileConnectionState(BluetoothProfile.GATT) == BluetoothProfile.STATE_CONNECTED
        } catch (e: Exception) {
            false
        }
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        scanner?.stopScan(scanCallback)
        closeGatt()
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        txCharacteristic = null
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: Exception) {
        }
        gatt = null
    }

    private fun hasScanPermission(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= 31) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    private fun status(text: String) {
        onEvent(Event.Status(text))
    }

    companion object {
        private const val TAG = "BleMeshClient"
        const val NODE_NAME_PREFIX = "AIx"
        val NUS_SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val NUS_CHAR_RX: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        val NUS_CHAR_TX: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val SCAN_TIMEOUT_MS = 8000L
        private const val RETRY_DELAY_MS = 5000L
    }
}
