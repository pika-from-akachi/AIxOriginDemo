package com.aix.origin.app.llm

import com.aix.origin.app.BuildConfig
import com.aix.origin.app.engine.Geo
import com.aix.origin.app.engine.GeoPoint
import com.aix.origin.app.model.EvacRoute
import com.aix.origin.app.model.HazardZone
import com.aix.origin.app.model.LlmPlan
import com.aix.origin.app.model.Shelter
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

/**
 * DeepSeek 逃生规划顾问（LLM 增强层）。
 *
 * 定位：A*（[com.aix.origin.app.engine.EvacRouter]）负责离线即时的确定性空间寻路，
 * 本类在联网时把「当前位置 + 附近灾情 + 避难所 + A* 基线路线」交给 DeepSeek，
 * 让模型做高层判断（选哪个目标、哪个方向更安全、有哪些警告），返回结构化建议。
 * 断网 / 未配 Key / 解析失败时由调用方回退到 A* 路线，不影响逃生闭环。
 *
 * 只依赖 JDK 的 HttpURLConnection + Android 内置 org.json，不引入额外依赖。
 */
object DeepSeekClient {

    private const val BASE_URL = "https://api.deepseek.com/chat/completions"

    /** 是否已配置可用 Key（未填 / 占位符均视为未配置） */
    fun isConfigured(): Boolean {
        val k = BuildConfig.DEEPSEEK_API_KEY
        return k.isNotBlank() && !k.startsWith("REPLACE")
    }

    /**
     * 请求 LLM 规划。为阻塞网络调用，务必在 IO 线程执行。
     * 网络/HTTP/解析异常会抛出，由调用方捕获并回退。
     */
    fun plan(
        pos: GeoPoint,
        hazards: List<HazardZone>,
        shelters: List<Shelter>,
        baseline: EvacRoute?,
    ): LlmPlan {
        if (!isConfigured()) throw IllegalStateException("DeepSeek API key not configured")

        // 先采集沿线地形高程 + 候选安全点（失败为 null，不影响主流程）
        val terrain = buildTerrain(pos, hazards, shelters, baseline)
        val candidates = buildCandidates(pos, hazards)

        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
            .put(JSONObject().put("role", "user").put("content", buildContext(pos, hazards, shelters, baseline, terrain, candidates)))

        val body = JSONObject()
            .put("model", BuildConfig.DEEPSEEK_MODEL)
            .put("messages", messages)
            .put("temperature", 0.2)
            .put("max_tokens", 1024)
            .put("stream", false)
            // 关闭思考模式：V4 默认开启思考(reasoning_content)，会拉高延迟且白烧 token；
            // 逃生规划只要快速高层判断，用 disabled 直接产出 JSON 内容。
            .put("thinking", JSONObject().put("type", "disabled"))
            .put("response_format", JSONObject().put("type", "json_object"))

        val conn = URL(BASE_URL).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 5_000
            conn.readTimeout = 20_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer ${BuildConfig.DEEPSEEK_API_KEY}")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                throw IllegalStateException("DeepSeek HTTP $code: $err")
            }
            val resp = conn.inputStream.bufferedReader().use { it.readText() }
            return parse(resp)
        } finally {
            conn.disconnect()
        }
    }

    // ---------------- 请求上下文 ----------------

    /** 把空间态势编码成一段 JSON 文本，作为 user 消息喂给模型 */
    private fun buildContext(
        pos: GeoPoint,
        hazards: List<HazardZone>,
        shelters: List<Shelter>,
        baseline: EvacRoute?,
        terrain: JSONObject?,
        candidates: JSONArray?,
    ): String {
        val hArr = JSONArray()
        for (z in hazards) {
            if (z.polygon.size < 3) continue
            val c = Geo.centroid(z.polygon)
            hArr.put(
                JSONObject()
                    .put("kind", z.kind.cn)
                    .put("level", z.level.level)
                    .put("center", latLng(c))
                    .put("radius_m", Geo.boundingRadiusM(z.polygon).round1())
                    .put("distance_m", Geo.distanceM(pos, c).round1())
                    .put("bearing_deg", Geo.bearing(pos, c).round1())
            )
        }

        val sArr = JSONArray()
        for (s in shelters) {
            sArr.put(
                JSONObject()
                    .put("name", s.name)
                    .put("position", latLng(s.position))
                    .put("distance_m", Geo.distanceM(pos, s.position).round1())
                    .put("bearing_deg", Geo.bearing(pos, s.position).round1())
            )
        }

        val base = if (baseline == null) null else JSONObject()
            .put("length_m", baseline.lengthM.round1())
            .put("target", baseline.targetName)
            .put("danger_penalty", baseline.dangerPenalty.round1())

        return JSONObject()
            .put("position", latLng(pos))
            .put("hazards", hArr)
            .put("shelters", sArr)
            .put("baseline_route", base ?: JSONObject.NULL)
            .put("terrain", terrain ?: JSONObject.NULL)
            .put("candidate_safe_points", candidates ?: JSONObject.NULL)
            .toString()
    }

    private fun latLng(p: GeoPoint) = JSONObject().put("lat", p.lat).put("lng", p.lng)

    // ---------------- 地形高程（Open-Meteo，免 Key） ----------------

    /** 批量查询高程；失败返回 null（不影响主流程） */
    private fun fetchElevations(points: List<GeoPoint>): Map<GeoPoint, Double>? {
        if (points.isEmpty()) return null
        val lat = points.joinToString(",") { it.lat.toString() }
        val lng = points.joinToString(",") { it.lng.toString() }
        val url = "https://api.open-meteo.com/v1/elevation?latitude=$lat&longitude=$lng"
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 5_000
            conn.readTimeout = 10_000
            if (conn.responseCode !in 200..299) return null
            val resp = conn.inputStream.bufferedReader().use { it.readText() }
            val arr = JSONObject(resp).optJSONArray("elevation") ?: return null
            val map = HashMap<GeoPoint, Double>()
            for (i in 0 until minOf(arr.length(), points.size)) {
                val e = arr.optDouble(i, Double.NaN)
                if (!e.isNaN()) map[points[i]] = e
            }
            return map.ifEmpty { null }
        } catch (e: Exception) {
            return null
        } finally {
            conn.disconnect()
        }
    }

    /** 采集用户/避难所/灾情点/路线的相对高程，供 AI 判断走高不走低洼 */
    private fun buildTerrain(
        pos: GeoPoint,
        hazards: List<HazardZone>,
        shelters: List<Shelter>,
        baseline: EvacRoute?,
    ): JSONObject? {
        val points = ArrayList<GeoPoint>()
        points.add(pos)
        shelters.forEach { points.add(it.position) }
        hazards.forEach { if (it.polygon.size >= 3) points.add(Geo.centroid(it.polygon)) }
        val wp = baseline?.waypoints
        if (wp != null && wp.size >= 2) {
            val stride = maxOf(1, (wp.size - 1) / 5)
            for (i in wp.indices step stride) points.add(wp[i])
        }

        val elev = fetchElevations(points) ?: return null
        val userElev = elev[pos] ?: return null
        val terrain = JSONObject().put("user_elevation_m", userElev.round1())

        val sArr = JSONArray()
        for (s in shelters) {
            val e = elev[s.position] ?: continue
            sArr.put(JSONObject()
                .put("name", s.name)
                .put("elevation_m", e.round1())
                .put("relative_to_user_m", (e - userElev).round1()))
        }
        terrain.put("shelters", sArr)

        val hArr = JSONArray()
        for (z in hazards) {
            if (z.polygon.size < 3) continue
            val c = Geo.centroid(z.polygon)
            val e = elev[c] ?: continue
            hArr.put(JSONObject()
                .put("kind", z.kind.cn)
                .put("elevation_m", e.round1())
                .put("relative_to_user_m", (e - userElev).round1()))
        }
        terrain.put("hazards", hArr)

        if (wp != null) {
            val routeElev = ArrayList<Double>()
            for (p in wp) elev[p]?.let { routeElev.add(it) }
            if (routeElev.size >= 2) {
                val minE = routeElev.minOrNull()!!
                val maxE = routeElev.maxOrNull()!!
                terrain.put("route", JSONObject()
                    .put("start_m", routeElev.first().round1())
                    .put("end_m", routeElev.last().round1())
                    .put("min_m", minE.round1())
                    .put("max_m", maxE.round1())
                    .put("climb_m", (maxE - minE).round1()))
            }
        }
        return terrain
    }

    /** 生成候选安全点（8 方向 × 300m/600m），带海拔与离灾情距离，供 AI 在无避难所时选点 */
    private fun buildCandidates(pos: GeoPoint, hazards: List<HazardZone>): JSONArray? {
        val dirs = intArrayOf(0, 45, 90, 135, 180, 225, 270, 315)
        val dists = doubleArrayOf(300.0, 600.0)
        val points = ArrayList<GeoPoint>()
        for (d in dists) for (dir in dirs) points.add(Geo.pointAt(pos, d, dir.toDouble()))

        val elev = fetchElevations(points)
        val arr = JSONArray()
        for (p in points) {
            var hazardDist = Double.MAX_VALUE
            for (z in hazards) {
                if (z.polygon.size < 3) continue
                val dd = Geo.distToPolygonM(z.polygon, p)
                if (dd < hazardDist) hazardDist = dd
            }
            val o = JSONObject()
                .put("lat", p.lat).put("lng", p.lng)
                .put("distance_from_user_m", Geo.distanceM(pos, p).round1())
                .put("bearing_deg", Geo.bearing(pos, p).round1())
            elev?.get(p)?.let { o.put("elevation_m", it.round1()) }
            if (hazardDist < Double.MAX_VALUE) o.put("dist_to_hazard_m", hazardDist.round1())
            arr.put(o)
        }
        return if (arr.length() > 0) arr else null
    }

    // ---------------- 响应解析 ----------------

    private fun parse(resp: String): LlmPlan {
        val root = JSONObject(resp)
        val content = root.getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
        val o = JSONObject(content)

        val wps = ArrayList<GeoPoint>()
        o.optJSONArray("route_waypoints")?.let { arr ->
            for (i in 0 until arr.length()) {
                val pt = arr.optJSONArray(i) ?: continue
                if (pt.length() >= 2) {
                    val lat = pt.getDouble(0)
                    val lng = pt.getDouble(1)
                    if (lat in -90.0..90.0 && lng in -180.0..180.0) wps.add(GeoPoint(lat, lng))
                }
            }
        }

        val warns = ArrayList<String>()
        o.optJSONArray("warnings")?.let { arr ->
            for (i in 0 until arr.length()) arr.optString(i).trim().takeIf { it.isNotEmpty() }?.let(warns::add)
        }

        val target = o.optJSONObject("target")?.let { t ->
            val lat = t.optDouble("lat", Double.NaN)
            val lng = t.optDouble("lng", Double.NaN)
            if (lat in -90.0..90.0 && lng in -180.0..180.0) GeoPoint(lat, lng) else null
        }
        val targetName = o.optJSONObject("target")?.optString("name", "")?.trim()
            ?.ifBlank { null }
            ?: o.optString("recommended_target", "").trim()

        return LlmPlan(
            survivalEstimate = o.optInt("survival_estimate", -1),
            analysis = o.optString("analysis", "").trim(),
            recommendedTarget = targetName,
            target = target,
            waypoints = wps,
            warnings = warns,
        )
    }

    private fun Double.round1(): Double = (this * 10).roundToInt() / 10.0

    // ---------------- 系统提示词 ----------------

    private const val SYSTEM_PROMPT =
        """你是一名自然灾害应急逃生规划专家。请根据用户提供的 JSON（包含当前位置、附近灾情、避难所、地形高程 terrain、以及本地 A* 算出的基线路线），综合判断并给出"存活率最高"的逃生路线建议。

判定要点：
1. 远离低洼积水与滑坡坡脚，尽量走高处的避难所，绕开 L2 红色危险区。
2. 若提供了 terrain 地形高程：优先选爬升小、沿高地/山脊的路线，避开相对高程明显更低的低洼点；不要凭空编造地形数据。
3. 在"路程最短"与"危险最小"之间优先保证存活率。
4. 可参考基线路线，但若其穿越危险区或低洼处，应给出更安全的修正。

输出要求（必须只输出一个合法 JSON 对象，不要输出任何解释文字或 markdown 代码块）：
{
  "survival_estimate": 78,
  "analysis": "一句话中文分析，说明为什么选这个集合点",
  "target": {"name": "集合点名称", "lat": 30.123, "lng": 120.456},
  "route_waypoints": [[纬度, 经度], [纬度, 经度]],
  "warnings": ["注意事项1", "注意事项2"]
}
其中：
- target 是综合分析后推荐的"存活率最高"的安全点，name 为名称、lat/lng 为该点的 WGS84 经纬度。必须始终给出一个点：优先从 shelters 里选；若 shelters 为空或都不安全，从 candidate_safe_points 里选海拔较高、离灾情较远的点。
- analysis 里必须说明选择依据（例如"该点海拔 95m、远离积水区、方向背离泥石流"）。
- route_waypoints 是可选的避灾途经点（3~5 个、按行进顺序、WGS84 经纬度，用来绕开灾情区），不含终点；若认为无需绕行可返回空数组 []。
- survival_estimate 为 0~100 的整数。"""
}
