package com.aix.origin.app

import android.app.Application
import android.util.Log
import com.amap.api.location.AMapLocationClient
import com.amap.api.maps.MapsInitializer

/**
 * 应用入口：高德 SDK 无需额外初始化（Key 已在 Manifest meta-data），
 * 这里统一打开 SDK 调试日志便于联调，并保留全局配置位。
 */
class AixApp : Application() {

    companion object {
        const val DEBUG = true
    }

    override fun onCreate() {
        super.onCreate()
        // 高德 SDK 隐私合规：必须在任何地图/定位接口调用前设置，否则报 555570
        AMapLocationClient.updatePrivacyShow(this, true, true)
        AMapLocationClient.updatePrivacyAgree(this, true)
        MapsInitializer.updatePrivacyShow(this, true, true)
        MapsInitializer.updatePrivacyAgree(this, true)
        if (DEBUG) {
            Log.d("AixApp", "AIxOrigin 应急避险启动")
        }
    }
}
