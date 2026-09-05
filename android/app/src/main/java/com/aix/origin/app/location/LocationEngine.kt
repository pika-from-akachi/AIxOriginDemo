package com.aix.origin.app.location

import android.content.Context
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
) {
    val valid: Boolean get() = point.lat != 0.0 || point.lng != 0.0
}

/**
 * 高德定位封装：GPS + 北斗/网络混合高精度模式。
 * - interval 1.5s，开启定位缓存：弱网/离线时自动回退「最后已知位置」，保证逃生可用。
 * - 回调错误码（如 12=网络问题）时把旧定位标记为 fromCache 继续下发。
 */
class LocationEngine(context: Context) {

    var onFix: (LocationFix) -> Unit = {}
    var onStatus: (String) -> Unit = {}

    private val client: AMapLocationClient = AMapLocationClient(context.applicationContext)

    @Volatile
    private var lastFix: LocationFix? = null

    private val amapListener = AMapLocationListener { location ->
        handleLocation(location)
    }

    init {
        val option = AMapLocationClientOption().apply {
            locationMode = AMapLocationMode.High_Accuracy // GPS 优先 + 网络补偿
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
            // 如 errorCode=12 网络异常：降级用缓存的最后已知位置
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
            onFix(cache.copy(fromCache = true))
        } else {
            Log.d(TAG, "无缓存定位可用: $reason")
        }
    }

    fun start() {
        client.startLocation()
    }

    fun stop() {
        client.stopLocation()
    }

    fun destroy() {
        client.onDestroy()
    }

    companion object {
        private const val TAG = "LocationEngine"
    }
}
