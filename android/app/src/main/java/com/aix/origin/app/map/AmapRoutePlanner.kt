package com.aix.origin.app.map

import com.aix.origin.app.BuildConfig
import com.aix.origin.app.engine.GeoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 高德步行路径规划（Web 服务 REST 接口）。
 *
 * 用 `restapi.amap.com/v3/direction/walking` 的 HTTP 接口沿真实道路算步行路线，
 * 返回 polyline 坐标串。走 Web 服务 Key，避开 search SDK 的「MD5安全码」鉴权问题。
 */
class AmapRoutePlanner {

    /** 单段步行路线（阻塞网络，须在 IO 线程调用） */
    suspend fun walk(from: GeoPoint, to: GeoPoint): List<GeoPoint>? =
        withContext(Dispatchers.IO) {
            try {
                val url = "https://restapi.amap.com/v3/direction/walking" +
                    "?origin=${from.lng},${from.lat}" +
                    "&destination=${to.lng},${to.lat}" +
                    "&key=${BuildConfig.AMAP_WEB_KEY}"
                val conn = URL(url).openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = 5_000
                    conn.readTimeout = 10_000
                    if (conn.responseCode != 200) return@withContext null
                    val resp = conn.inputStream.bufferedReader().use { it.readText() }
                    parseWalk(resp)
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                null
            }
        }

    /** 按途经点分段算路并拼接（pos → wp1 → wp2 → … → 终点） */
    suspend fun walkThrough(points: List<GeoPoint>): List<GeoPoint>? {
        if (points.size < 2) return null
        val result = ArrayList<GeoPoint>()
        for (i in 0 until points.size - 1) {
            val seg = walk(points[i], points[i + 1]) ?: return null
            if (result.isEmpty()) result.addAll(seg)
            else result.addAll(seg.drop(1))
        }
        return if (result.size >= 2) result else null
    }

    private fun parseWalk(resp: String): List<GeoPoint>? {
        val root = JSONObject(resp)
        if (root.optString("status") != "1") return null
        val paths = root.optJSONObject("route")?.optJSONArray("paths") ?: return null
        if (paths.length() == 0) return null
        val steps = paths.optJSONObject(0)?.optJSONArray("steps") ?: return null
        val pts = ArrayList<GeoPoint>()
        for (i in 0 until steps.length()) {
            val step = steps.optJSONObject(i) ?: continue
            val polyline = step.optString("polyline", "")
            for (pair in polyline.split(";")) {
                val c = pair.split(",")
                if (c.size >= 2) {
                    val lng = c[0].trim().toDoubleOrNull() ?: continue
                    val lat = c[1].trim().toDoubleOrNull() ?: continue
                    pts.add(GeoPoint(lat, lng))
                }
            }
        }
        return if (pts.size >= 2) pts else null
    }
}
