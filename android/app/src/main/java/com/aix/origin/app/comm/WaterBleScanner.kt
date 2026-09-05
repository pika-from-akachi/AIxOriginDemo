package com.aix.origin.app.comm

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelUuid
import androidx.core.content.ContextCompat

/**
 * BLE 广播扫描器 —— 接收水位检测节点（AIxWtr）的 Legacy Advertising 广播。
 * 只扫描 Service Data UUID 0xFFF0 的广播，不连接、不配对、不发现 GATT。
 * 解析 16 字节业务载荷，按 node_id + sequence 去重（16 位回绕）。
 */
class WaterBleScanner(private val context: Context) {

    data class WaterReading(
        val nodeId: Int,
        val percent: Int?,           // 0-100；null=无有效百分比
        val waterDetected: Boolean,  // flags bit3：有效百分比>5%，判定检测到水
        val millivolts: Int,
        val rssi: Int,
    )

    var onWater: (WaterReading) -> Unit = {}
    var onStatus: (String) -> Unit = {}

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }
    private val scanner by lazy { bluetoothAdapter?.bluetoothLeScanner }

    @Volatile
    private var running = false

    // 去重：node_id -> 最近一次 sequence
    private val lastSeq = HashMap<Int, Int>()

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val data = result.scanRecord?.serviceData?.get(SERVICE_DATA_UUID) ?: return
            val adv = parseWaterAdvertisement(data) ?: return
            val last = lastSeq[adv.nodeId]
            if (last == adv.sequence) return  // 同一节点相同序号的重复广播
            lastSeq[adv.nodeId] = adv.sequence
            onWater(
                WaterReading(
                    nodeId = adv.nodeId,
                    percent = adv.percent,
                    waterDetected = (adv.flags and 0x08) != 0,
                    millivolts = adv.millivolts,
                    rssi = result.rssi,
                )
            )
        }

        override fun onScanFailed(errorCode: Int) {
            onStatus("水位扫描失败(code=$errorCode)")
        }
    }

    fun start() {
        if (running) return
        running = true
        if (!hasScanPermission()) {
            onStatus("缺少蓝牙扫描权限")
            return
        }
        if (scanner == null) {
            onStatus("设备不支持 BLE")
            return
        }
        val filter = ScanFilter.Builder()
            .setServiceData(SERVICE_DATA_UUID, null, null)
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner?.startScan(listOf(filter), settings, scanCallback)
        onStatus("正在扫描水位广播…")
    }

    fun stop() {
        running = false
        scanner?.stopScan(scanCallback)
    }

    private fun hasScanPermission(): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }

    companion object {
        val SERVICE_DATA_UUID: ParcelUuid = ParcelUuid.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
    }
}

// ============================================================================
//  AIx Water BLE 协议 v1 —— 16 字节载荷解析
// ============================================================================

data class WaterAdvertisement(
    val version: Int,
    val flags: Int,
    val nodeId: Int,
    val sequence: Int,
    val raw16: Int,
    val millivolts: Int,
    val percent: Int?,
    val referenceDepthMm: Int,
    val noise: Int,
)

private fun u8(data: ByteArray, offset: Int) = data[offset].toInt() and 0xff

private fun u16le(data: ByteArray, offset: Int): Int =
    u8(data, offset) or (u8(data, offset + 1) shl 8)

private fun crc8Atm(data: ByteArray, length: Int): Int {
    var crc = 0
    for (i in 0 until length) {
        crc = crc xor u8(data, i)
        repeat(8) {
            crc = if ((crc and 0x80) != 0) ((crc shl 1) xor 0x07) and 0xff else (crc shl 1) and 0xff
        }
    }
    return crc
}

/** 解析 16 字节水位广播载荷；校验长度、magic、version、CRC。失败返回 null */
fun parseWaterAdvertisement(data: ByteArray): WaterAdvertisement? {
    if (data.size != 16) return null
    if (u8(data, 0) != 0x41 || u8(data, 1) != 0x57) return null // "AW"
    if (u8(data, 2) != 1) return null
    if (crc8Atm(data, 15) != u8(data, 15)) return null

    val percentByte = u8(data, 11)
    return WaterAdvertisement(
        version = u8(data, 2),
        flags = u8(data, 3),
        nodeId = u8(data, 4),
        sequence = u16le(data, 5),
        raw16 = u16le(data, 7),
        millivolts = u16le(data, 9),
        percent = if (percentByte == 0xff) null else percentByte,
        referenceDepthMm = u8(data, 12),
        noise = u16le(data, 13),
    )
}
