package com.aix.origin.app.model

import com.aix.origin.app.engine.GeoPoint

/**
 * 灾情级别 —— 与 ESP32-S3 节点上报的 L0/L1/L2 对齐。
 * - LEVEL_1：黄色（注意/风险）
 * - LEVEL_2：红色（危险，触发全屏告警）
 */
enum class AlertLevel(val level: Int) {
    LEVEL_0(0), // 安全
    LEVEL_1(1), // 注意（黄）
    LEVEL_2(2); // 危险（红）

    val isDanger: Boolean get() = this == LEVEL_2

    companion object {
        fun fromInt(v: Int): AlertLevel = when {
            v >= 2 -> LEVEL_2
            v >= 1 -> LEVEL_1
            else -> LEVEL_0
        }
    }
}

/** 灾情种类（尽量与固件模拟场景对齐，用于图标/文案） */
enum class HazardKind(val cn: String) {
    ROCKFALL("落石"),
    LANDSLIDE("滑坡"),
    FLOOD("低洼积水"),
    COLLAPSE("路面塌陷"),
    FIRE("火情"),
    OTHER("未知灾情");

    companion object {
        fun fromString(s: String?): HazardKind = when (s?.trim()?.lowercase()) {
            "rockfall", "rock_fall", "落石" -> ROCKFALL
            "landslide", "滑坡" -> LANDSLIDE
            "flood", "积水", "低洼" -> FLOOD
            "collapse", "塌陷" -> COLLAPSE
            "fire", "火情" -> FIRE
            else -> OTHER
        }
    }
}

/** 灾情来源 */
enum class HazardSource { BLE, WIFI, DEMO, MANUAL }

/** 一个灾情多边形区域（一个节点可能上报一个范围；本机也可注入/演示） */
data class HazardZone(
    val id: String,
    val kind: HazardKind,
    val level: AlertLevel,          // 区域严重度（决定红色/黄色渲染）
    val polygon: List<GeoPoint>,    // 至少 3 个点，顺序为闭环外圈
    val reportedAt: Long = System.currentTimeMillis(),
    val source: HazardSource = HazardSource.BLE,
) {
    init {
        require(polygon.size >= 3) { "hazard polygon needs >= 3 points" }
    }
}

/** mesh 内节点（传感器 / 网关） */
data class MeshNode(
    val id: String,          // 例如 "A01"
    val role: String = "C",  // 角色 A/B/C
    val battery: Int = -1,   // 0..100，-1 未知
    val lastSeenAt: Long = System.currentTimeMillis(),
    val position: GeoPoint? = null,
)

/** 避难所 / 安全集合点 */
data class Shelter(
    val id: String,
    val name: String,
    val position: GeoPoint,
    val capacity: Int = 0,
)

/** 实时风险简报（刷新于每次定位/灾情更新） */
data class RiskReport(
    val survival: Int,              // 0..100
    val alertLevel: AlertLevel,     // 当前用户的警报级别
    val nearestZone: HazardZone?,   // 距用户最近的多边形（可为 null）
    val nearestDistM: Double,       // 距多边形边界距离（在多边形内为 0）
    val insideZone: HazardZone?,    // 用户当前所在的灾情区
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val inDanger: Boolean get() = alertLevel == AlertLevel.LEVEL_2 || insideZone != null
}

/** 逃生路线 */
data class EvacRoute(
    val waypoints: List<GeoPoint>,
    val lengthM: Double,
    val dangerPenalty: Double,   // 0 = 全程避开所有灾情区
    val target: GeoPoint,
    val targetName: String,
    val plannedAt: Long = System.currentTimeMillis(),
)

/** LLM（DeepSeek）逃生规划建议 —— AI 综合分析出安全集合点，路线交给高德 */
data class LlmPlan(
    val survivalEstimate: Int,       // 0..100；-1 表示模型未给出
    val analysis: String,            // 一句话中文分析
    val recommendedTarget: String,   // 推荐前往的目标名
    val target: GeoPoint?,           // 推荐的安全集合点坐标（AI 综合分析得出）
    val waypoints: List<GeoPoint>,   // 避灾途经点（AI 给出，用于绕开灾情区）
    val warnings: List<String>,      // 注意事项
) {
    val hasTarget: Boolean get() = target != null
}
