package com.aix.origin.app

import android.app.Application
import android.util.Log

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
        if (DEBUG) {
            // 高德定位/地图日志开关（按需）
            Log.d("AixApp", "AIxOrigin 应急避险启动")
        }
    }
}
