# AIx Water BLE 广播协议 v1

节点使用 BLE Legacy Advertising 广播实时水位，不接受连接。广播间隔 250ms，
传感器数据每秒更新一次。设备完整名称为 `AIxWtr`。

## 接收端最小约定

接收端只需扫描 BLE 广播，不需要连接、配对、发现 GATT 服务或订阅 Characteristic。

1. 扫描 Service Data UUID `0000fff0-0000-1000-8000-00805f9b34fb`。
2. Android 等高层 BLE API 通常已经去掉 AD 头和 UUID，交给应用的是后面的 **16字节业务载荷**。
3. 检查载荷长度、CRC、`magic=AW` 和 `version=1` 后再读取数据。
4. 使用 `node_id + sequence` 去重；同一节点相同序号的多次广播只处理一次。
5. 序号从 `65535` 回到 `0`，比较时需要考虑16位回绕。
6. `percent=255`、ADC饱和或 CRC 错误时，不更新百分比显示；原始值仍可写入诊断日志。
7. RSSI 由手机或接收器扫描 API 提供，不属于本协议载荷，不能当作水位。

## Advertising Data

整包固定 31 字节：

| AD 结构 | 长度 | 内容 |
|---|---:|---|
| Flags | 3 | `02 01 06` |
| Complete Local Name | 8 | `07 09 41 49 78 57 74 72`（`AIxWtr`） |
| Service Data 16-bit UUID | 20 | AD type `0x16`，UUID `0xFFF0`（小端 `F0 FF`），后接16字节载荷 |

`0xFFF0` 是本 Demo 的开发期自定义 UUID，未向 Bluetooth SIG 注册。多个产品共存或正式发布时，
应迁移到自有 128-bit UUID；接收端应同时检查 UUID、magic、版本和 CRC，不能只凭设备名识别。

## 16 字节载荷

所有多字节整数均为小端序：

| 偏移 | 长度 | 字段 | 含义 |
|---:|---:|---|---|
| 0 | 2 | `magic` | ASCII `AW`，即 `41 57` |
| 2 | 1 | `version` | 当前为 `1` |
| 3 | 1 | `flags` | 状态位，见下表 |
| 4 | 1 | `node_id` | 节点编号，当前为 `1` |
| 5 | 2 | `sequence` | 每次数据更新加1，`65535`后回到0 |
| 7 | 2 | `raw16` | GPIO5 滤波值，范围0～65535 |
| 9 | 2 | `millivolts` | ESP32 ADC 引脚测得电压，mV；不自动反推分压前电压 |
| 11 | 1 | `percent` | 0～100；`255`表示无有效百分比 |
| 12 | 1 | `reference_depth_mm` | 当前百分比100%对应的参考深度；固件配置为30mm |
| 13 | 2 | `noise` | 当前采样块的 P90-P10，16位原始刻度 |
| 15 | 1 | `crc8` | 对偏移0～14计算 CRC-8/ATM |

`flags`：

| 位 | 掩码 | 含义 |
|---:|---:|---|
| 0 | `0x01` | 已加载有效干湿标定 |
| 1 | `0x02` | ADC 读数接近饱和，百分比无效 |
| 2 | `0x04` | OLED 本次启动时被检测到 |
| 3 | `0x08` | 有效百分比大于5%，判定检测到水 |
| 4 | `0x10` | ADC 为直接输入；没有在软件中应用分压倍率 |
| 5 | `0x20` | 已启用多点深度曲线，`percent` 已按实际深度线性化 |
| 6 | `0x40` | 蜂鸣器报警模式已启用（旋律或高水位长音） |
| 7 | — | 保留，接收端忽略 |

CRC 参数：多项式 `0x07`、初值 `0x00`、不反射、`xorout=0x00`。

## 示例

示例载荷代表节点1、序号4660、原始值15659、806mV、100%、30mm参考深度、噪声1191：

```text
41 57 01 11 01 34 12 2B 3D 26 03 64 1E A7 04 1C
```

接收流程：扫描 AD type `0x16` → 检查 UUID `0xFFF0` → 检查16字节长度 → 校验 CRC →
检查 `magic=AW` 和 `version=1` → 解析字段。Python 编解码参考在
`micropython/ble_protocol.py`，可在 MicroPython 与普通 Python 中使用。

## Android/Kotlin 解析参考

传入参数 `data` 必须是 Android `ScanRecord.serviceData` 中 UUID `0xFFF0` 对应的16字节值，
不能包含前面的 `F0 FF` UUID，也不能传完整31字节广播包。

```kotlin
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
            crc = if ((crc and 0x80) != 0) {
                ((crc shl 1) xor 0x07) and 0xff
            } else {
                (crc shl 1) and 0xff
            }
        }
    }
    return crc
}

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
```

Android 扫描过滤使用完整 UUID：

```text
0000fff0-0000-1000-8000-00805f9b34fb
```

解析成功后仍要检查：`percent` 必须为 `null` 或0～100；未知 flags 位忽略；未来收到未知
协议版本时记录并丢弃，不应尝试按 v1 字段强行解析。

百分比是相对标定结果。固件配置 **3cm=100%**。仅有干燥/3cm两点标定时，百分比是原始
传感器信号的线性比例；`flags & 0x20 != 0` 时，百分比已经过多点曲线校正，近似表示深度的
线性比例。接收端可以依据 `reference_depth_mm` 明确显示基准，不能把该百分比解释成水箱
绝对容量。
