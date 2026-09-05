package com.aix.origin.app.engine

import com.aix.origin.app.model.AlertLevel
import com.aix.origin.app.model.HazardZone
import com.aix.origin.app.model.RiskReport
import kotlin.math.roundToInt

/**
 * 生存率 / 险情引擎。
 * 与固件模型对齐的基线：安全 95、L1 注意 70、L2 危险 25。
 * 多边形之外按“距边界距离”在基线与安全值之间平滑插值。
 */
object RiskEngine {

    /** 认为“已脱离威胁”的安全缓冲距离（米） */
    const val SAFE_BUFFER_M = 200.0

    /** 警报阈值：低于该生存率判定为 L2 红色险情 */
    const val DANGER_SURVIVAL = 60

    /** 警报阈值：低于该生存率判定为 L1 黄色注意 */
    const val WATCH_SURVIVAL = 90

    fun evaluate(pos: GeoPoint, zones: List<HazardZone>): RiskReport {
        if (zones.isEmpty()) {
            return RiskReport(
                survival = 100,
                alertLevel = AlertLevel.LEVEL_0,
                nearestZone = null,
                nearestDistM = Double.MAX_VALUE,
                insideZone = null,
            )
        }

        var insideZone: HazardZone? = null
        var nearest: HazardZone? = null
        var nearestD = Double.MAX_VALUE

        for (z in zones) {
            if (z.polygon.size < 3) continue
            val d = Geo.distToPolygonM(z.polygon, pos)
            if (d == 0.0) {
                // 在多边形内：多个重叠时取严重度更高的
                if (insideZone == null || z.level.level > insideZone!!.level.level) {
                    insideZone = z
                }
            }
            if (d < nearestD) {
                nearestD = d
                nearest = z
            }
        }

        val survival: Int
        val level: AlertLevel

        when {
            // 位于 L2 红色区
            insideZone?.level == AlertLevel.LEVEL_2 -> {
                survival = 25
                level = AlertLevel.LEVEL_2
            }
            // 位于 L1 黄色区
            insideZone != null -> {
                survival = 70
                level = AlertLevel.LEVEL_1
            }
            // 位于所有区域之外：按距最近边界距离平滑回升
            nearest != null -> {
                val t = (nearestD / SAFE_BUFFER_M).coerceIn(0.0, 1.0)
                val edgeBase = if (nearest!!.level == AlertLevel.LEVEL_2) 25.0 else 70.0
                val s = edgeBase + (95.0 - edgeBase) * t
                survival = s.roundToInt().coerceIn(0, 100)
                level = levelFor(survival)
            }
            else -> {
                survival = 100
                level = AlertLevel.LEVEL_0
            }
        }

        return RiskReport(
            survival = survival,
            alertLevel = level,
            nearestZone = nearest,
            nearestDistM = nearestD,
            insideZone = insideZone,
        )
    }

    fun levelFor(survival: Int): AlertLevel = when {
        survival >= WATCH_SURVIVAL -> AlertLevel.LEVEL_0
        survival >= DANGER_SURVIVAL -> AlertLevel.LEVEL_1
        else -> AlertLevel.LEVEL_2
    }
}
