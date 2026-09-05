package com.aix.origin.app.comm

import com.aix.origin.app.engine.Geo as EngineGeo
import com.aix.origin.app.engine.GeoPoint
import com.aix.origin.app.model.AlertLevel
import com.aix.origin.app.model.HazardKind
import com.aix.origin.app.model.HazardZone
import com.aix.origin.app.model.MeshNode
import org.json.JSONArray
import org.json.JSONObject

/**
 * 网桥协议解析 —— 网关(ESP32-S3)通过 BLE UART / WiFi UDP 转发 mesh 数据，
 * 下行 JSON（也可能兼容固件的竖线文本），上行 JSON 由 [GatewayCodec.encodeUplink] 生成。
 *
 * 下行消息：
 *  { "type":"hazard",   "nodeId":"A01","hazardId":"HZ-A01-1","kind":"rockfall",
 *    "level":2,"polygon":[[lat,lng],...],"ts":1690000000000 }
 *  { "type":"heartbeat","nodeId":"A01","role":"A","battery":87,"ts":1690000000000 }
 *  { "type":"status",   "text":"节点 A01 已接入" }
 *
 * 上行消息：
 *  { "type":"gps","nodeId":"PHONE","lat":30.123,"lng":120.456,"acc":8.5,
 *    "survival":88,"level":1,"ts":1690000000000 }
 *  { "type":"sos","lat":30.123,"lng":120.456,"ts":1690000000000 }
 */
object GatewayParser {

    sealed class Message {
        data class Hazard(val zone: HazardZone) : Message()
        data class Heartbeat(val node: MeshNode) : Message()
        data class Status(val text: String) : Message()
        data class Unsupported(val raw: String) : Message()
    }

    /** 解析一条下行消息；无法识别时返回 Unsupported（不会崩溃） */
    fun parse(raw: String): Message {
        val text = raw.trim()
        if (text.isEmpty()) return Message.Unsupported(raw)
        return if (text.startsWith("{")) parseJson(text) else parsePipe(text)
    }

    private fun parseJson(text: String): Message = try {
        val o = JSONObject(text)
        when (o.optString("type")) {
            "hazard" -> Message.Hazard(parseHazard(o))
            "heartbeat" -> parseHeartbeat(o) ?: Message.Unsupported(text)
            "status" -> Message.Status(o.optString("text", "节点状态更新"))
            else -> Message.Unsupported(text)
        }
    } catch (e: Exception) {
        Message.Unsupported(text)
    }

    private fun parseHazard(o: JSONObject): HazardZone {
        val polygon = ArrayList<GeoPoint>()
        val arr: JSONArray = o.optJSONArray("polygon") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val pt = arr.optJSONArray(i) ?: continue
            if (pt.length() >= 2) polygon.add(GeoPoint(pt.getDouble(0), pt.getDouble(1)))
        }
        // 兜底：没有多边形时给出一个围绕节点的极小“点圈”，避免 UI 空白
        if (polygon.size < 3) {
            val lat = o.optDouble("lat", 0.0)
            val lng = o.optDouble("lng", 0.0)
            val base = GeoPoint(lat, lng)
            repeat(4) { k ->
                val brg = k * 90.0
                polygon.add(EngineGeo.pointAt(base, 30.0, brg))
            }
        }
        return HazardZone(
            id = o.optString("hazardId", "HZ-${o.optString("nodeId", "?")}"),
            kind = HazardKind.fromString(o.optString("kind")),
            level = AlertLevel.fromInt(o.optInt("level", 2)),
            polygon = polygon,
            reportedAt = o.optLong("ts", System.currentTimeMillis()),
        )
    }

    private fun parseHeartbeat(o: JSONObject): Message.Heartbeat? {
        val nodeId = o.optString("nodeId").ifEmpty { return null }
        var position: GeoPoint? = null
        if (o.has("lat") && o.has("lng")) {
            position = GeoPoint(o.getDouble("lat"), o.getDouble("lng"))
        }
        return Message.Heartbeat(
            MeshNode(
                id = nodeId,
                role = o.optString("role", "C"),
                battery = o.optInt("battery", -1),
                lastSeenAt = o.optLong("ts", System.currentTimeMillis()),
                position = position,
            )
        )
    }

    /**
     * 兼容固件风格竖线文本（网关逐行转发 Serial 输出）：
     *  "HB|A01|A|87" 心跳   "MSG|A01|ALERT|rockfall|2" 简版灾情
     */
    private fun parsePipe(text: String): Message {
        val parts = text.split("|")
        return when {
            parts.size >= 2 && parts[0] == "HB" -> {
                Message.Heartbeat(
                    MeshNode(
                        id = parts[1],
                        role = parts.getOrNull(2) ?: "C",
                        battery = parts.getOrNull(3)?.trim()?.toIntOrNull() ?: -1,
                    )
                )
            }
            parts.size >= 4 && parts[0] == "MSG" && parts[2] == "ALERT" -> {
                // MSG|A01|ALERT|<kind>|<level>
                val lat = parts.getOrNull(5)?.toDoubleOrNull()
                val lng = parts.getOrNull(6)?.toDoubleOrNull()
                val center = if (lat != null && lng != null) GeoPoint(lat, lng) else GeoPoint(0.0, 0.0)
                val polygon = ArrayList<GeoPoint>()
                repeat(4) { k -> polygon.add(EngineGeo.pointAt(center, 25.0, k * 90.0)) }
                Message.Hazard(
                    HazardZone(
                        id = "HZ-${parts[1]}-${System.currentTimeMillis()}",
                        kind = HazardKind.fromString(parts[3]),
                        level = AlertLevel.fromInt(parts[4].trim().toIntOrNull() ?: 1),
                        polygon = polygon,
                    )
                )
            }
            else -> Message.Unsupported(text)
        }
    }
}

/** 上行编码 */
object GatewayCodec {
    fun encodeGps(
        lat: Double, lng: Double,
        accuracyM: Float?, survival: Int, level: AlertLevel,
    ): String {
        return JSONObject().apply {
            put("type", "gps")
            put("nodeId", "PHONE")
            put("lat", lat)
            put("lng", lng)
            if (accuracyM != null) put("acc", accuracyM.toDouble())
            put("survival", survival)
            put("level", level.level)
            put("ts", System.currentTimeMillis())
        }.toString()
    }

    fun encodeSos(lat: Double, lng: Double): String = JSONObject().apply {
        put("type", "sos")
        put("nodeId", "PHONE")
        put("lat", lat)
        put("lng", lng)
        put("ts", System.currentTimeMillis())
    }.toString()
}
