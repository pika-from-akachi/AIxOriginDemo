package com.aix.origin.app.comm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.aix.origin.app.MainActivity
import com.aix.origin.app.R

/**
 * 前台服务 —— 后台持续扫描水位节点 BLE 广播。
 * 锁屏 / 其它 App 前台 / 本 App 退到后台时仍能收到水位广播；
 * 检测到积水(≥20%)时发全屏通知，自动拉起 App。
 */
class WaterAlertService : Service() {

    private lateinit var scanner: WaterBleScanner
    private var lastAlerted = false // 边沿触发，避免重复弹通知

    override fun onCreate() {
        super.onCreate()
        createChannels()
        val monitor = NotificationCompat.Builder(this, CHANNEL_MONITOR)
            .setSmallIcon(R.drawable.ic_sos)
            .setContentTitle("AIxOrigin 水位监测中")
            .setContentText("后台持续扫描水位节点广播")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        startForeground(MONITOR_NOTIF_ID, monitor)

        scanner = WaterBleScanner(this)
        scanner.onWater = { r -> onWater(r) }
        scanner.start()
    }

    private fun onWater(r: WaterBleScanner.WaterReading) {
        val percent = r.percent ?: return // 无有效百分比，忽略
        val alerted = percent >= 20
        if (alerted && !lastAlerted) {
            lastAlerted = true
            postAlert(percent)
        } else if (!alerted) {
            lastAlerted = false
        }
    }

    private fun postAlert(percent: Int) {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pending = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(this, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_sos)
            .setContentTitle("⚠ 检测到积水")
            .setContentText("水位 $percent%，请立即避险")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(pending, true)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(this).notify(ALERT_NOTIF_ID, notif)
        } catch (_: SecurityException) {
            // 未授予通知权限时静默
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scanner.stop()
        super.onDestroy()
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MONITOR, "水位监测", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERT, "积水警报", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    companion object {
        private const val CHANNEL_MONITOR = "water_monitor"
        private const val CHANNEL_ALERT = "water_alert"
        private const val MONITOR_NOTIF_ID = 1001
        private const val ALERT_NOTIF_ID = 1002
    }
}
