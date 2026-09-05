package com.aix.origin.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.aix.origin.app.comm.BleMeshClient
import com.aix.origin.app.comm.GatewayCodec
import com.aix.origin.app.comm.GatewayParser
import com.aix.origin.app.comm.WifiUdpBridge
import com.aix.origin.app.engine.EvacRouter
import com.aix.origin.app.engine.Geo
import com.aix.origin.app.engine.GeoPoint
import com.aix.origin.app.engine.RiskEngine
import com.aix.origin.app.location.LocationEngine
import com.aix.origin.app.location.LocationFix
import com.aix.origin.app.map.MapController
import com.aix.origin.app.model.AlertLevel
import com.aix.origin.app.model.EvacRoute
import com.aix.origin.app.model.HazardKind
import com.aix.origin.app.model.HazardSource
import com.aix.origin.app.model.HazardZone
import com.aix.origin.app.model.MeshNode
import com.aix.origin.app.model.RiskReport
import com.aix.origin.app.model.Shelter
import com.amap.api.maps.MapView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 主界面：地图 + 顶部状态/存活率 + 底部简报与逃生指令 + SOS。
 *
 * 数据流：
 *   GPS(BLE/网关) → 灾情多边形 → RiskEngine → 存活率/警报级别
 *   RiskEngine 判定威胁 → EvacRouter(A*) 规划绕开红色区的逃生路线 → 地图动态箭头 + 语音播报
 */
class MainActivity : AppCompatActivity() {

    // ---- 视图 ----
    private lateinit var mapView: MapView
    private lateinit var topStatus: TextView
    private lateinit var topSurvival: TextView
    private lateinit var txtSummary: TextView
    private lateinit var txtGuidance: TextView
    private lateinit var fabSos: FloatingActionButton
    private lateinit var alertOverlay: FrameLayout
    private lateinit var alertText: TextView

    // ---- 服务 ----
    private var controller: MapController? = null
    private val locationEngine by lazy { LocationEngine(this) }
    private var ble: BleMeshClient? = null
    private var udp: WifiUdpBridge? = null
    private var tts: TextToSpeech? = null
    private var blinkJob: Job? = null
    private var routeJob: Job? = null

    // ---- 状态（仅主线程读写） ----
    private val hazards = ArrayList<HazardZone>()
    private val nodes = HashMap<String, MeshNode>()
    private val shelters = ArrayList<Shelter>()
    private var lastFix: LocationFix? = null
    private var currentRoute: EvacRoute? = null
    private var lastPlanPos: GeoPoint? = null
    private var bleConnected = false
    private var wifiUp = false
    private var started = false
    private var lastGpsSentAt = 0L
    private var alarmVisible = false
    private var cameraMovedToUser = false

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val anyGranted = grants.values.any { it }
            if (anyGranted) {
                ensureStarted()
            } else {
                topStatus.text = "缺少权限，无法定位/连接网关"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mapView = findViewById(R.id.map_view)
        topStatus = findViewById(R.id.top_status)
        topSurvival = findViewById(R.id.top_survival)
        txtSummary = findViewById(R.id.txt_summary)
        txtGuidance = findViewById(R.id.txt_guidance)
        fabSos = findViewById(R.id.fab_sos)
        alertOverlay = findViewById(R.id.alert_overlay)
        alertText = findViewById(R.id.alert_text)
        val btnDemo = findViewById<Button>(R.id.btn_demo_hazard)
        val btnAck = findViewById<Button>(R.id.btn_alert_ack)

        mapView.onCreate(savedInstanceState)

        topSurvival.text = getString(R.string.survival_label, 100)

        // ---- 交互 ----
        btnDemo.setOnClickListener { toggleDemoHazard() }
        fabSos.setOnClickListener { sendSos() }
        btnAck.setOnClickListener { dismissAlarm() }

        initTts()
        requestPermissionsIfNeeded()
    }

    // ================= 生命周期 =================

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        ensureMapController()
        if (hasLocationPermission()) {
            ensureStarted()
        }
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        super.onDestroy()
        routeJob?.cancel()
        blinkJob?.cancel()
        ble?.stop()
        udp?.stop()
        locationEngine.stop()
        locationEngine.destroy()
        tts?.stop()
        tts?.shutdown()
        mapView.onDestroy()
    }

    private fun ensureMapController() {
        if (controller != null) return
        val map = mapView.map ?: return
        val c = MapController(map).also { controller = it }
        c.setOnMapLongClick { p -> setShelterAt(p) }
        // 初次进图，先落在中国区便于演示（等定位后再跟随用户）
        c.focusOn(DEFAULT_POS, 14f)
    }

    // ================= 权限 =================

    private fun requestPermissionsIfNeeded() {
        val needed = buildList {
            if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= 31) {
                if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                    add(Manifest.permission.BLUETOOTH_CONNECT)
                }
                if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
                    add(Manifest.permission.BLUETOOTH_SCAN)
                }
            }
        }
        if (needed.isNotEmpty()) {
            permLauncher.launch(needed.toTypedArray())
        } else {
            ensureStarted()
        }
    }

    private fun hasLocationPermission(): Boolean =
        hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
            hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun hasBluetoothPermission(): Boolean = if (Build.VERSION.SDK_INT >= 31) {
        hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    private fun hasPermission(p: String): Boolean =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    // ================= 启动 =================

    private fun ensureStarted() {
        if (started) return
        started = true

        // ---- 定位 ----
        if (hasLocationPermission()) {
            locationEngine.onFix = { fix -> runOnUiThread { onLocationFix(fix) } }
            locationEngine.onStatus = { s -> runOnUiThread { logStatus(s) } }
            locationEngine.start()
        }

        // ---- BLE 网桥 ----
        ble = BleMeshClient(this).apply {
            onEvent = { ev ->
                runOnUiThread { handleCommEvent(ev) }
            }
        }
        if (hasBluetoothPermission()) {
            ble?.start()
            topStatus.text = getString(R.string.status_scanning)
        } else {
            logStatus("无蓝牙权限，仅 WiFi 网桥")
        }

        // ---- WiFi UDP 网桥 ----
        udp = WifiUdpBridge(this).apply {
            onEvent = { ev ->
                runOnUiThread { handleCommEvent(ev) }
            }
        }
        udp?.start()
    }

    // ================= 定位 -> 风险 -> 路线 =================

    private fun onLocationFix(fix: LocationFix) {
        lastFix = fix
        ensureMapController()?.let { c ->
            c.setSelf(fix.point)
            if (!cameraMovedToUser) {
                cameraMovedToUser = true
                c.focusOn(fix.point, 16f)
            } else {
                c.updateRouteArrow(fix.point)
            }
        }
        if (!fix.fromCache) {
            logStatus("定位 ${fix.accuracyM.roundToInt()}m")
        }
        evaluateRisk()
        maybeUplinkGps()
    }

    private fun evaluateRisk() {
        val fix = lastFix ?: return
        val report = RiskEngine.evaluate(fix.point, hazards)
        val c = controller ?: return

        // 顶栏存活率
        topSurvival.text = getString(R.string.survival_label, report.survival)
        topSurvival.setTextColor(
            when {
                report.alertLevel == AlertLevel.LEVEL_2 -> ContextCompat.getColor(this, R.color.aix_red)
                report.alertLevel == AlertLevel.LEVEL_1 -> ContextCompat.getColor(this, R.color.aix_yellow)
                else -> ContextCompat.getColor(this, R.color.aix_green)
            }
        )

        // 底部简报
        val summary = describeReport(report)
        txtSummary.text = summary

        // 逃生判定
        val needRoute = report.alertLevel != AlertLevel.LEVEL_0
        if (!needRoute) {
            clearRouteUi()
            hideAlarm()
            txtGuidance.text = "区域正常 · 请保持警惕"
            return
        }

        if (report.alertLevel == AlertLevel.LEVEL_2) {
            showAlarm(report)
        } else if (alarmVisible) {
            hideAlarm()
        }

        // 规划路线：仅在移动超过 30m 或灾情变更时重算
        val movedFar = lastPlanPos == null ||
            Geo.distanceM(lastPlanPos!!, fix.point) > 30.0
        if (currentRoute == null || movedFar) {
            planRoute(fix.point, report)
        } else {
            txtGuidance.text = guidanceText(report, currentRoute)
        }
    }

    private fun planRoute(pos: GeoPoint, report: RiskReport) {
        routeJob?.cancel()
        lastPlanPos = pos
        routeJob = lifecycleScope.launch {
            val route = withContext(Dispatchers.Default) {
                try {
                    EvacRouter.route(pos, hazards.toList(), shelters.toList())
                } catch (e: Exception) {
                    null
                }
            }
            if (route == null || route.waypoints.size < 2) {
                currentRoute = null
                controller?.clearRoute()
                txtGuidance.text = "正在计算逃生方向…"
                return@launch
            }
            currentRoute = route
            val c = controller ?: return@launch
            c.showRoute(route.waypoints)
            c.fitPoints(listOf(pos) + route.waypoints)
            txtGuidance.text = guidanceText(report, route)
            speak(guidanceText(report, route))
        }
    }

    private fun describeReport(r: RiskReport): String = buildString {
        when {
            r.alertLevel == AlertLevel.LEVEL_2 && r.insideZone != null ->
                append("红色警戒：身处${r.insideZone.kind.cn}区！立即撤离")
            r.alertLevel == AlertLevel.LEVEL_2 ->
                append("红色警戒：${r.nearestZone?.kind?.cn ?: "危险"}区仅 ${r.nearestDistM.roundToInt()}m")
            r.alertLevel == AlertLevel.LEVEL_1 ->
                append("注意：附近 ${r.nearestZone?.kind?.cn ?: "灾情"}区 ${r.nearestDistM.roundToInt()}m，请勿靠近")
            else -> append("区域正常 · 请保持警惕")
        }
    }

    private fun guidanceText(report: RiskReport, route: EvacRoute?): String {
        if (route == null) return "已进入威胁区，请向远离灾情的方向转移"
        val dist = route.lengthM.roundToInt()
        val target = route.targetName
        return "逃生路线约 ${dist}m，前往「$target」，沿蓝色箭头行进"
    }

    // ================= 灾情数据 =================

    private fun handleCommEvent(ev: Any) {
        when (ev) {
            is BleMeshClient.Event.Status -> {
                if (ev.text.contains("已接入") || ev.text.contains("已连接")) {
                    bleConnected = true
                } else if (ev.text.contains("断开") || ev.text.contains("未找到")) {
                    bleConnected = false
                }
                logStatus("Mesh:" + ev.text)
            }
            is BleMeshClient.Event.Frame -> onGatewayMessage(ev.msg)
            is WifiUdpBridge.Event.Status -> {
                if (ev.text.contains("已监听")) wifiUp = true
                if (ev.text.contains("已停止")) wifiUp = false
                logStatus(ev.text)
            }
            is WifiUdpBridge.Event.Frame -> onGatewayMessage(ev.msg)
        }
        refreshTopStatus()
    }

    private fun onGatewayMessage(msg: GatewayParser.Message) {
        when (msg) {
            is GatewayParser.Message.Hazard -> {
                val hz = msg.zone
                val idx = hazards.indexOfFirst { it.id == hz.id }
                if (idx >= 0) hazards[idx] = hz else hazards.add(hz)
                pruneExpired()
                renderHazards()
                logStatus("收到灾情: ${hz.kind.cn} L${hz.level.level}")
            }
            is GatewayParser.Message.Heartbeat -> {
                val n = msg.node
                nodes[n.id] = n
                controller?.updateNodes(nodes.values.toList())
                logStatus("节点 ${n.id} 在线")
            }
            is GatewayParser.Message.Status -> logStatus("网关:${msg.text}")
            is GatewayParser.Message.Unsupported -> Unit
        }
        evaluateRisk()
    }

    private fun pruneExpired() {
        val cutoff = System.currentTimeMillis() - HAZARD_TTL_MS
        hazards.removeAll { it.reportedAt < cutoff }
    }

    private fun renderHazards() {
        controller?.updateHazards(hazards)
        // 灾情变化时若已无威胁则清路线，否则标记重算
        val fix = lastFix ?: return
        val report = RiskEngine.evaluate(fix.point, hazards)
        if (report.alertLevel == AlertLevel.LEVEL_0) {
            clearRouteUi()
        } else {
            lastPlanPos = null // 强制重算
            currentRoute = null
            controller?.clearRoute()
        }
    }

    private fun toggleDemoHazard() {
        val existing = hazards.firstOrNull { it.source == HazardSource.DEMO }
        if (existing != null) {
            hazards.remove(existing)
            controller?.updateHazards(hazards)
            logStatus("已移除模拟灾情")
            toast("已关闭模拟灾情")
            evaluateRisk()
            return
        }
        val center = lastFix?.point ?: DEFAULT_POS
        // 在用户东侧 120m 放一个 Lv2 落石/塌方区
        val origin = Geo.pointAt(center, 120.0, 90.0)
        val polygon = ArrayList<GeoPoint>()
        repeat(8) { k -> polygon.add(Geo.pointAt(origin, 70.0, k * 45.0)) }
        val zone = HazardZone(
            id = "HZ-DEMO",
            kind = HazardKind.COLLAPSE,
            level = AlertLevel.LEVEL_2,
            polygon = polygon,
            source = HazardSource.DEMO,
        )
        hazards.add(zone)
        renderHazards()
        logStatus("模拟灾情: 前方道路塌陷 Lv2")
        toast("已在前方模拟 Lv2 塌方")
        evaluateRisk()
        speak("前方道路塌陷，请立即撤离")
    }

    private fun setShelterAt(p: GeoPoint) {
        val id = "SHELTER-${shelters.size + 1}"
        shelters.add(Shelter(id = id, name = "集合点${shelters.size + 1}", position = p, capacity = 0))
        controller?.updateShelters(shelters)
        toast("集合点已设定，将优先绕开高危区导航")
        lastPlanPos = null
        currentRoute = null
        evaluateRisk()
    }

    // ================= 告警 / SOS / 语音 / 上行 =================

    private fun showAlarm(report: RiskReport) {
        if (!alarmVisible) {
            alarmVisible = true
            alertOverlay.visibility = View.VISIBLE
            val t = report.insideZone ?: report.nearestZone
            alertText.text = "${t?.kind?.cn ?: "危险区域"} · 存活率 ${report.survival}%\n请立刻沿逃生路线转移"
            blink(alertOverlay)
            vibrateAlarm()
        }
    }

    private fun dismissAlarm() {
        alarmVisible = false
        blinkJob?.cancel()
        alertOverlay.alpha = 1f
        alertOverlay.visibility = View.GONE
    }

    private fun clearRouteUi() {
        currentRoute = null
        lastPlanPos = null
        controller?.clearRoute()
    }

    private fun blink(v: View) {
        blinkJob?.cancel()
        blinkJob = lifecycleScope.launch {
            while (true) {
                v.alpha = 0.25f
                v.animate().alpha(1f).setDuration(550)
                    .setInterpolator(LinearInterpolator()).start()
                kotlinx.coroutines.delay(1100)
            }
        }
    }

    private fun vibrateAlarm() {
        val vib = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            vib.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 400, 250, 400, 250, 800), 0)
            )
        } else {
            @Suppress("DEPRECATION")
            vib.vibrate(longArrayOf(0, 400, 250, 400, 250, 800), 0)
        }
    }

    private fun sendSos() {
        vibrateAlarm()
        val fix = lastFix
        if (fix == null) {
            toast("尚未定位，无法发送 SOS")
            speak("尚未定位，无法发送 SOS")
            return
        }
        val payload = GatewayCodec.encodeSos(fix.point.lat, fix.point.lng)
        ble?.send(payload)
        udp?.sendBroadcast(payload)
        txtGuidance.text = "SOS 已发出（${fix.point.lat.toFixed(5)}, ${fix.point.lng.toFixed(5)}）"
        logStatus("SOS 已通过 Mesh/WiFi 发出")
        speak("SOS 已发出，救援人员将收到您的坐标")
    }

    private fun maybeUplinkGps() {
        val fix = lastFix ?: return
        val now = System.currentTimeMillis()
        if (now - lastGpsSentAt < GPS_UPLINK_MS) return
        lastGpsSentAt = now
        val report = RiskEngine.evaluate(fix.point, hazards)
        val payload = GatewayCodec.encodeGps(
            fix.point.lat, fix.point.lng,
            fix.accuracyM, report.survival, report.alertLevel,
        )
        // 无网卡/网关断开时静默失败即可，不影响本地
        ble?.send(payload)
        udp?.sendBroadcast(payload)
    }

    private fun logStatus(s: String) {
        // 简短日志：可扩展为列表
        if (BuildConfig.DEBUG) {
            android.util.Log.d(TAG, s)
        }
    }

    private fun refreshTopStatus() {
        val parts = ArrayList<String>()
        parts += if (bleConnected) "Mesh:已连接" else "Mesh:未连接"
        parts += if (wifiUp) "WiFi:在线" else "WiFi:离线"
        topStatus.text = parts.joinToString(" · ")
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.SIMPLIFIED_CHINESE
            }
        }
    }

    private fun speak(text: String) {
        try {
            if (tts?.language?.language != Locale.SIMPLIFIED_CHINESE.language) {
                tts?.language = Locale.SIMPLIFIED_CHINESE
            }
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "aix_tts")
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val GPS_UPLINK_MS = 5000L
        private const val HAZARD_TTL_MS = 30 * 60 * 1000L // 30min 未更新视为失效
        private val DEFAULT_POS = GeoPoint(30.2590, 120.1303) // 演示默认位（杭州）
    }
}

/** 简易坐标格式化 */
private fun Double.toFixed(digits: Int): String = String.format(Locale.US, "%.${digits}f", this)
