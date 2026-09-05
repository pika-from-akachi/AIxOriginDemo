package com.aix.origin.app.engine

import kotlin.math.*

/**
 * 纯几何工具，不依赖任何 Android / 高德类型，便于单测。
 * 坐标一律为 WGS84（与定位、地图一致）。
 */
data class GeoPoint(val lat: Double, val lng: Double)

object Geo {
    private const val EARTH_R = 6371000.0 // 地球平均半径（米）

    fun toRad(d: Double): Double = d * PI / 180.0
    fun toDeg(r: Double): Double = r * 180.0 / PI

    /** Haversine 距离（米） */
    fun distanceM(a: GeoPoint, b: GeoPoint): Double {
        val dLat = toRad(b.lat - a.lat)
        val dLng = toRad(b.lng - a.lng)
        val la1 = toRad(a.lat)
        val la2 = toRad(b.lat)
        val h = sin(dLat / 2).pow(2) + cos(la1) * cos(la2) * sin(dLng / 2).pow(2)
        return 2 * EARTH_R * asin(sqrt(h))
    }

    /** 初始方位角（度，0=正北，顺时针） */
    fun bearing(a: GeoPoint, b: GeoPoint): Double {
        val la1 = toRad(a.lat)
        val la2 = toRad(b.lat)
        val dLng = toRad(b.lng - a.lng)
        val y = sin(dLng) * cos(la2)
        val x = cos(la1) * sin(la2) - sin(la1) * cos(la2) * cos(dLng)
        return (toDeg(atan2(y, x)) + 360.0) % 360.0
    }

    /** 由起点 + 距离（米）+ 方位角推算终点 */
    fun pointAt(start: GeoPoint, distanceM: Double, bearingDeg: Double): GeoPoint {
        val d = distanceM / EARTH_R
        val brg = toRad(bearingDeg)
        val la1 = toRad(start.lat)
        val lo1 = toRad(start.lng)
        val la2 = asin(sin(la1) * cos(d) + cos(la1) * sin(d) * cos(brg))
        val lo2 = lo1 + atan2(sin(brg) * sin(d) * cos(la1), cos(d) - sin(la1) * sin(la2))
        return GeoPoint(toDeg(la2), toDeg(lo2))
    }

    /**
     * 点到线段的最短距离（米）。
     * 处理点在端点外侧的情况。
     */
    fun distanceToSegmentM(p: GeoPoint, a: GeoPoint, b: GeoPoint): Double {
        val (ax, ay) = projectLocal(a, a)
        val (bx, by) = projectLocal(b, a)
        val (px, py) = projectLocal(p, a)
        val dx = bx - ax
        val dy = by - ay
        if (dx == 0.0 && dy == 0.0) return distanceM(p, a)
        val t = (((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
        val cx = ax + t * dx
        val cy = ay + t * dy
        return hypot(px - cx, py - cy)
    }

    /**
     * 点到多边形边界的最小距离（米）。点在多边形内部时返回 0。
     */
    fun distToPolygonM(polygon: List<GeoPoint>, p: GeoPoint): Double {
        if (polygon.isEmpty()) return Double.MAX_VALUE
        if (contains(polygon, p)) return 0.0
        var minD = Double.MAX_VALUE
        for (i in polygon.indices) {
            val a = polygon[i]
            val b = polygon[(i + 1) % polygon.size]
            minD = min(minD, distanceToSegmentM(p, a, b))
        }
        return minD
    }

    /** 射线法判断点是否在多边形内部（含边界） */
    fun contains(polygon: List<GeoPoint>, p: GeoPoint): Boolean {
        var inside = false
        var j = polygon.size - 1
        for (i in polygon.indices) {
            val pi = polygon[i]
            val pj = polygon[j]
            // 用 lng 作 x、lat 作 y 做经典跨立测试，对小范围多边形足够
            if ((pi.lat > p.lat) != (pj.lat > p.lat) &&
                p.lng < (pj.lng - pi.lng) * (p.lat - pi.lat) / (pj.lat - pi.lat) + pi.lng
            ) {
                inside = !inside
            }
            j = i
        }
        return inside
    }

    /** 多边形几何中心（顶点平均，用于“远离灾情”的方向计算） */
    fun centroid(polygon: List<GeoPoint>): GeoPoint {
        var lat = 0.0
        var lng = 0.0
        if (polygon.isEmpty()) return GeoPoint(0.0, 0.0)
        for (p in polygon) {
            lat += p.lat
            lng += p.lng
        }
        val n = polygon.size.toDouble()
        return GeoPoint(lat / n, lng / n)
    }

    /** 多边形包络盒的近似半径（中心到最远顶点的距离） */
    fun boundingRadiusM(polygon: List<GeoPoint>): Double {
        val c = centroid(polygon)
        var maxD = 0.0
        for (p in polygon) maxD = max(maxD, distanceM(c, p))
        return maxD
    }

    // ---------------- 局部平面投影（用于网格寻路） ----------------

    /** 相对原点把经纬度投影为“东向(x)/北向(y)”米制坐标 */
    fun projectLocal(origin: GeoPoint, p: GeoPoint): Pair<Double, Double> {
        val x = toRad(p.lng - origin.lng) * EARTH_R * cos(toRad(origin.lat))
        val y = toRad(p.lat - origin.lat) * EARTH_R
        return x to y
    }

    /** 相对原点把“东向(x)/北向(y)”米制坐标反投影回经纬度 */
    fun unprojectLocal(origin: GeoPoint, x: Double, y: Double): GeoPoint {
        val dLng = x / (EARTH_R * cos(toRad(origin.lat)))
        val dLat = y / EARTH_R
        return GeoPoint(origin.lat + toDeg(dLat), origin.lng + toDeg(dLng))
    }
}
