package com.aix.origin.app.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.aix.origin.app.engine.Geo
import com.aix.origin.app.engine.GeoPoint
import com.aix.origin.app.model.AlertLevel
import com.aix.origin.app.model.HazardZone
import com.aix.origin.app.model.MeshNode
import com.aix.origin.app.model.Shelter
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.model.BitmapDescriptor
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.LatLngBounds
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.Polygon
import com.amap.api.maps.model.PolygonOptions
import com.amap.api.maps.model.Polyline
import com.amap.api.maps.model.PolylineOptions

/**
 * 高德地图渲染控制器。
 * 职责：灾情多边形（红/黄半透明）、节点/避难所标记、动态逃生路线 + 移动方向箭头。
 * 坐标统一用 WGS84（引擎 GeoPoint <-> AMap LatLng 仅在此转换）。
 */
class MapController(private val map: AMap) {

    private val hazardOverlays = ArrayList<Polygon>()
    private val markerOverlays = ArrayList<Marker>()
    private var routePolyline: Polyline? = null
    private var routeArrow: Marker? = null
    private var selfMarker: Marker? = null

    private var currentRoute: List<GeoPoint> = emptyList()
    private var satellite = false

    // 颜色
    private val fillL1 = 0x66F9A825.toInt()  // 黄 ~40%
    private val strokeL1 = 0xFFF9A825.toInt()
    private val fillL2 = 0x66C62828.toInt()  // 红 ~40%
    private val strokeL2 = 0xFFC62828.toInt()
    private val routeColor = 0xFF1B8A4C.toInt()
    private val routeWidth = 14f

    init {
        map.uiSettings.isZoomControlsEnabled = false
        map.uiSettings.isCompassEnabled = true
        map.uiSettings.isScaleControlsEnabled = true
        map.mapType = AMap.MAP_TYPE_NIGHT
    }

    fun setSatellite(enabled: Boolean) {
        satellite = enabled
        map.mapType = if (enabled) AMap.MAP_TYPE_SATELLITE else AMap.MAP_TYPE_NIGHT
    }

    /** 长按地图 —— 用于设定“集合点/避难点” */
    fun setOnMapLongClick(handler: (GeoPoint) -> Unit) {
        map.setOnMapLongClickListener { latLng ->
            handler(GeoPoint(latLng.latitude, latLng.longitude))
        }
    }

    /** 点击避难点标记 —— 用于移除（回调传入 shelter id） */
    fun setOnShelterClick(handler: (String) -> Unit) {
        map.setOnMarkerClickListener { marker ->
            val tag = marker.getObject() as? String
            if (tag != null && tag.startsWith("shelter:")) {
                handler(tag.removePrefix("shelter:"))
                true
            } else {
                false
            }
        }
    }

    // ---------------- 灾情多边形 ----------------

    fun updateHazards(zones: List<HazardZone>) {
        hazardOverlays.forEach { it.remove() }
        hazardOverlays.clear()
        for (z in zones) {
            if (z.polygon.size < 3) continue
            val pts = z.polygon.map { it.toLatLng() }
            val isRed = z.level == AlertLevel.LEVEL_2
            val opt = PolygonOptions().addAll(pts).apply {
                fillColor(if (isRed) fillL2 else fillL1)
                strokeColor(if (isRed) strokeL2 else strokeL1)
                strokeWidth(if (isRed) 6f else 4f)
            }
            hazardOverlays.add(map.addPolygon(opt))
        }
    }

    // ---------------- 节点 / 避难所 / 自身 ----------------

    fun updateNodes(nodes: List<MeshNode>) {
        // 移除旧的节点标记（保留 self / arrow / shelter）
        val it = markerOverlays.iterator()
        while (it.hasNext()) {
            val m = it.next()
            if ((m.getObject() as? String)?.startsWith("node:") == true) {
                m.remove()
                it.remove()
            }
        }
        for (n in nodes) {
            val p = n.position ?: continue
            val mk = map.addMarker(
                MarkerOptions()
                    .position(p.toLatLng())
                    .title("节点 ${n.id}")
                    .snippet("角色${n.role} · 电量${if (n.battery >= 0) "${n.battery}%" else "未知"}")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
                    .anchor(0.5f, 0.5f)
            )
            mk.setObject("node:${n.id}")
            markerOverlays.add(mk)
        }
    }

    fun updateShelters(shelters: List<Shelter>) {
        val it = markerOverlays.iterator()
        while (it.hasNext()) {
            val m = it.next()
            if ((m.getObject() as? String)?.startsWith("shelter:") == true) {
                m.remove()
                it.remove()
            }
        }
        for (s in shelters) {
            val mk = map.addMarker(
                MarkerOptions()
                    .position(s.position.toLatLng())
                    .title(s.name)
                    .snippet(if (s.capacity > 0) "可容纳 ${s.capacity} 人" else "安全集合点")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
                    .anchor(0.5f, 0.5f)
            )
            mk.setObject("shelter:${s.id}")
            markerOverlays.add(mk)
        }
    }

    /** 自身位置蓝点（也可放大显示） */
    fun setSelf(point: GeoPoint) {
        if (selfMarker == null) {
            selfMarker = map.addMarker(
                MarkerOptions()
                    .position(point.toLatLng())
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE))
                    .anchor(0.5f, 0.5f)
                    .title("我")
            )
        } else {
            selfMarker?.position = point.toLatLng()
        }
    }

    // ---------------- 逃生路线 + 动态箭头 ----------------

    fun showRoute(route: List<GeoPoint>) {
        currentRoute = route
        routePolyline?.remove()
        routeArrow?.remove()
        if (route.size < 2) return
        val opt = PolylineOptions()
            .color(routeColor)
            .width(routeWidth)
            .addAll(route.map { it.toLatLng() })
        routePolyline = map.addPolyline(opt)

        // 移动方向箭头（沿路线在“用户前方”的采样点，随用户位置刷新）
        val arrow = arrowBitmap()
        routeArrow = map.addMarker(
            MarkerOptions()
                .position(route.first().toLatLng())
                .icon(arrow)
                .anchor(0.5f, 0.5f)
                .title("逃生方向")
        )
        routeArrow?.setObject("route_arrow")
        updateRouteArrow(route.first())
    }

    fun updateRouteArrow(self: GeoPoint) {
        if (currentRoute.size < 2) return
        val mk = routeArrow ?: return
        val sample = sampleAhead(currentRoute, self, LOOKAHEAD_M)
        val p = sample.first
        mk.position = p.toLatLng()
        // AMap 用 setRotateAngle（地图平面内角度）
        mk.setRotateAngle((-sample.second).toFloat())
    }

    fun clearRoute() {
        currentRoute = emptyList()
        routePolyline?.remove()
        routePolyline = null
        routeArrow?.remove()
        routeArrow = null
    }

    /** 沿路线取 user 前方 lookahead 处的点与方位角 */
    private fun sampleAhead(
        route: List<GeoPoint>,
        user: GeoPoint,
        lookahead: Double,
    ): Pair<GeoPoint, Double> {
        // 找到离 user 最近的路段并计算已行弧长，然后向前推 lookahead
        var bestSeg = 0
        var bestT = 0.0
        var bestD = Double.MAX_VALUE
        for (i in 0 until route.size - 1) {
            val a = route[i]
            val b = route[i + 1]
            val segLen = Geo.distanceM(a, b)
            if (segLen <= 0) continue
            val dA = Geo.distanceM(user, a)
            val dB = Geo.distanceM(user, b)
            // 粗略判断投影是否在段内
            val t = ((dA * dA + segLen * segLen - dB * dB) / (2 * segLen * segLen)).coerceIn(0.0, 1.0)
            val projD = dA * (1 - t) + dB * t
            if (projD < bestD) {
                bestD = projD
                bestSeg = i
                bestT = t
            }
        }
        // 累加弧长到投影点
        var traveled = 0.0
        for (i in 0 until bestSeg) traveled += Geo.distanceM(route[i], route[i + 1])
        traveled += Geo.distanceM(route[bestSeg], route[bestSeg + 1]) * bestT
        var target = traveled + lookahead

        // 从投影点（位于 bestSeg 段上）开始累进
        val from = Geo.pointAt(
            route[bestSeg],
            Geo.distanceM(route[bestSeg], route[bestSeg + 1]) * bestT,
            Geo.bearing(route[bestSeg], route[bestSeg + 1]),
        )
        var i = bestSeg
        while (i < route.size - 1) {
            val a = if (i == bestSeg) from else route[i]
            val b = route[i + 1]
            val segLen = Geo.distanceM(a, b)
            if (target <= segLen) {
                val brg = Geo.bearing(a, b)
                val pt = Geo.pointAt(a, target, brg)
                return pt to brg
            }
            target -= segLen
            i++
        }
        return route.last() to Geo.bearing(route[route.size - 2], route.last())
    }

    // ---------------- 相机 ----------------

    fun focusOn(point: GeoPoint, zoom: Float = 16f) {
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(point.toLatLng(), zoom), 400, null)
    }

    /** 框住一组兴趣点（自身+路线终点），缩放至全览 */
    fun fitPoints(points: List<GeoPoint>) {
        if (points.isEmpty()) return
        if (points.size == 1) {
            focusOn(points[0])
            return
        }
        val builder = LatLngBounds.Builder()
        for (p in points) builder.include(p.toLatLng())
        map.animateCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), 90), 600, null)
    }

    // ---------------- 内部工具 ----------------

    private fun arrowBitmap(): BitmapDescriptor {
        val s = 64
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0x1B, 0x8A, 0x4C)
            style = Paint.Style.STROKE
            strokeWidth = 6f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val path = Path().apply {
            moveTo(s / 2f, s * 0.08f)
            lineTo(s * 0.84f, s * 0.70f)
            lineTo(s / 2f, s * 0.52f)
            lineTo(s * 0.16f, s * 0.70f)
            close()
        }
        c.drawPath(path, fill)
        c.drawPath(path, stroke)
        return BitmapDescriptorFactory.fromBitmap(bmp)
    }
}

private fun GeoPoint.toLatLng() = LatLng(lat, lng)

private const val LOOKAHEAD_M = 60.0
