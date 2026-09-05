package com.aix.origin.app.map

import android.content.Context
import com.aix.origin.app.engine.GeoPoint
import com.amap.api.services.core.LatLonPoint
import com.amap.api.services.route.BusRouteResult
import com.amap.api.services.route.DriveRouteResult
import com.amap.api.services.route.RideRouteResult
import com.amap.api.services.route.RouteSearch
import com.amap.api.services.route.WalkRouteResult
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 高德步行路径规划（RouteSearch）。
 *
 * 与栅格 A* 的区别：走的是真实道路/小路，不会穿楼穿墙；
 * 结果转成引擎坐标 [GeoPoint] 列表，供地图绘制与 LLM 作为基线路线参考。
 */
class AmapRoutePlanner(context: Context) {

    private val routeSearch = RouteSearch(context.applicationContext)

    /** 协程挂起式查询步行路线；无结果/失败返回 null（调用方回退栅格 A*） */
    suspend fun walk(from: GeoPoint, to: GeoPoint): List<GeoPoint>? =
        suspendCancellableCoroutine { cont ->
            routeSearch.setRouteSearchListener(object : RouteSearch.OnRouteSearchListener {
                override fun onWalkRouteSearched(r: WalkRouteResult?, code: Int) {
                    // 算路失败时 AMap 会回调 null，这里必须按可空处理
                    val pts = if (code == 1000 && r != null) extractPoints(r) else null
                    if (cont.isActive) cont.resume(pts)
                }
                override fun onDriveRouteSearched(r: DriveRouteResult?, code: Int) {}
                override fun onBusRouteSearched(r: BusRouteResult?, code: Int) {}
                override fun onRideRouteSearched(r: RideRouteResult?, code: Int) {}
            })
            val query = RouteSearch.WalkRouteQuery(
                RouteSearch.FromAndTo(
                    LatLonPoint(from.lat, from.lng),
                    LatLonPoint(to.lat, to.lng),
                )
            )
            routeSearch.calculateWalkRouteAsyn(query)
        }

    /** 按途经点分段算路并拼接（pos → wp1 → wp2 → … → 终点），任一段失败返回 null */
    suspend fun walkThrough(points: List<GeoPoint>): List<GeoPoint>? {
        if (points.size < 2) return null
        val result = ArrayList<GeoPoint>()
        for (i in 0 until points.size - 1) {
            val seg = walk(points[i], points[i + 1]) ?: return null
            if (result.isEmpty()) result.addAll(seg)
            else result.addAll(seg.drop(1)) // 去掉重复的连接点
        }
        return if (result.size >= 2) result else null
    }

    private fun extractPoints(r: WalkRouteResult): List<GeoPoint>? {
        val path = r.paths.firstOrNull() ?: return null
        val pts = ArrayList<GeoPoint>()
        for (step in path.steps) {
            step.polyline?.forEach { pts.add(GeoPoint(it.latitude, it.longitude)) }
        }
        return if (pts.size >= 2) pts else null
    }
}
