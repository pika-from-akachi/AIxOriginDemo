package com.aix.origin.app.location

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import com.aix.origin.app.engine.GeoPoint
import com.amap.api.location.AMapLocation
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.amap.api.location.AMapLocationClientOption.AMapLocationMode
import com.amap.api.location.AMapLocationListener

/** 一次定位结果（统一为引擎坐标） */
data class LocationFix(
    val point: GeoPoint,
    val accuracyM: Float,
    val fromCache: Boolean,
    val time: Long,
    val bearing: Float = 0f,   // 航向角 0-360（正北=0，顺时针）
) {
    val valid: Boolean get() = point.lat != 0.0 || point.lng != 0.0
}

/**
 * 高德定位 + 系统传感器航向封装。
 * - 定位：GPS + 北斗/网络混合高精度，弱网回退缓存。
 * - 航向：直接用系统 SensorManager（磁力计+加速度计）算手机朝向，
 *   与定位解耦——定位失败时航向也能持续更新，供导航箭头指向手机所指方向。
 */
class LocationEngine(context: Context) {

    var onFix: (LocationFix) -> Unit = {}
    var onStatus: (String) -> Unit = {}
    var onHeading: (Float) -> Unit = {}

    private val client: AMapLocationClient = AMapLocationClient(context.applicationContext)

    @Volatile
    private var lastFix: LocationFix? = null

    // ---- 系统传感器航向 ----
    private val sensorManager: SensorManager =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    private val gravity = FloatArray(3)
    private val geomagnetic = FloatArray(3)
    private var hasGravity = false
    private var hasGeomagnetic = false
    @Volatile
    private var heading = 0f

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    System.arraycopy(event.values, 0, gravity, 0, gravity.size)
                    hasGravity = true
                }
                Sensor.TYPE_MAGNETIC_FIELD -> {
                    System.arraycopy(event.values, 0, geomagnetic, 0, geomagnetic.size)
                    hasGeomagnetic = true
                }
            }
            if (!hasGravity || !hasGeomagnetic) return
            val r = FloatArray(9)
            val i = FloatArray(9)
            if (SensorManager.getRotationMatrix(r, i, gravity, geomagnetic)) {
                val orientation = FloatArray(3)
                SensorManager.getOrientation(r, orientation)
                // orientation[0] 为方位角(弧度)，0=北，逆时针为正；转成 0=北、顺时针为正(0-360)
                val azimuthDeg = Math.toDegrees(orientation[0].toDouble()).toFloat()
                val clockwise = ((360f - azimuthDeg) % 360f + 360f) % 360f
                if (Math.abs(clockwise - heading) > 1f) {
                    heading = clockwise
                    onHeading(heading)
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private val amapListener = AMapLocationListener { location ->
        handleLocation(location)
    }

    init {
        val option = AMapLocationClientOption().apply {
            locationMode = AMapLocationMode.Hight_Accuracy
            isOnceLocation = false
            isOnceLocationLatest = false
            interval = 1500
            isNeedAddress = false
            isLocationCacheEnable = true // 离线/弱网回退缓存
            isMockEnable = false
        }
        client.setLocationOption(option)
        client.setLocationListener(amapListener)
    }

    private fun handleLocation(loc: AMapLocation?) {
        if (loc == null) {
            emitCached("定位失败(空)")
            return
        }
        if (loc.errorCode != 0) {
            Log.w(TAG, "AMap 定位错误 ${loc.errorCode}: ${loc.errorInfo}")
            onStatus("信号弱，使用缓存定位")
            emitCached("弱网回退")
            return
        }
        val fix = LocationFix(
            point = GeoPoint(loc.latitude, loc.longitude),
            accuracyM = loc.accuracy,
            fromCache = false,
            time = loc.time,
            bearing = if (heading > 0f) heading else loc.bearing,
        )
        if (!fix.valid) return
        lastFix = fix
        onStatus("定位正常(精度 ${fix.accuracyM} m)")
        onFix(fix)
    }

    private fun emitCached(reason: String) {
        val cache = lastFix ?: client.lastKnownLocation?.let {
            if (it.errorCode == 0) {
                LocationFix(
                    point = GeoPoint(it.latitude, it.longitude),
                    accuracyM = it.accuracy,
                    fromCache = true,
                    time = it.time,
                )
            } else null
        }
        if (cache != null && cache.valid) {
            onFix(cache.copy(fromCache = true, bearing = heading))
        } else {
            Log.d(TAG, "无缓存定位可用: $reason")
        }
    }

    fun start() {
        client.startLocation()
        accelerometer?.let { sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI) }
        magnetometer?.let { sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI) }
    }

    fun stop() {
        client.stopLocation()
        sensorManager.unregisterListener(sensorListener)
    }

    fun destroy() {
        client.onDestroy()
        sensorManager.unregisterListener(sensorListener)
    }

    companion object {
        private const val TAG = "LocationEngine"
    }
}
