package com.aix.origin.app.engine

import com.aix.origin.app.model.AlertLevel
import com.aix.origin.app.model.EvacRoute
import com.aix.origin.app.model.HazardKind
import com.aix.origin.app.model.HazardZone
import com.aix.origin.app.model.Shelter
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * A* 逃生路线规划（纯逻辑，可单测）。
 *
 * 设计要点：
 * 1. 以用户位置为原点建立局部米制栅格（约 20m/格），8 方向移动。
 * 2. 目标候选 = 不在红色区内的避难所；没有则取“背离最近灾情中心”的逃逸点。
 * 3. 危险区按级别对“穿行”加权（L2 红区约 300 / 格、L1 黄区 60 / 格，低洼额外加权），
 *    而**不是硬封锁** —— 这样用户若已在红色区内仍能寻路逃出，只是路线尽量绕开。
 */
object EvacRouter {

    const val STEP_M = 20.0
    private const val COST_L1 = 60.0
    private const val COST_L2 = 300.0
    private const val COST_FLOOD_EXTRA = 40.0
    private const val DIAG = 1.4142135623730951
    private const val MAX_HALF_M = 3000.0
    private const val MIN_HALF_M = 200.0

    data class Target(val position: GeoPoint, val name: String)

    /**
     * 规划逃生路线。start 必须为当前位置。
     * 没有任何灾情/目标时返回 null（表示无需逃生）。
     */
    fun route(
        start: GeoPoint,
        zones: List<HazardZone>,
        shelters: List<Shelter>,
    ): EvacRoute? {
        val validZones = zones.filter { it.polygon.size >= 3 }
        if (validZones.isEmpty()) return null
        val target = pickTarget(start, validZones, shelters) ?: return null
        val points = aStar(start, target.position, validZones) ?: return null

        var length = 0.0
        var penalty = 0.0
        for (i in 1 until points.size) {
            length += Geo.distanceM(points[i - 1], points[i])
        }
        // 统计穿过灾情区的“惩罚”总量（不含起终点格子本身）
        for (i in 1 until points.size - 1) {
            penalty += cellPenalty(points[i], validZones)
        }
        return EvacRoute(
            waypoints = points,
            lengthM = length,
            dangerPenalty = penalty,
            target = target.position,
            targetName = target.name,
        )
    }

    // ---------------- 目标选择 ----------------

    fun pickTarget(
        start: GeoPoint,
        zones: List<HazardZone>,
        shelters: List<Shelter>,
    ): Target? {
        val redZones = zones.filter { it.level == AlertLevel.LEVEL_2 }

        // 优先：既不在红区也不在黄区的避难所；其次：至少不在红区的避难所
        val usable = shelters.filter { s ->
            redZones.none { Geo.contains(it.polygon, s.position) }
        }.sortedBy { Geo.distanceM(start, it.position) }
        val clean = usable.filter { s ->
            zones.none { Geo.contains(it.polygon, s.position) }
        }
        (if (clean.isNotEmpty()) clean else usable).firstOrNull()?.let {
            return Target(it.position, it.name)
        }

        // 没有避难所可用：先处理“已在灾情区内”
        val selfZone = redZones.firstOrNull { Geo.contains(it.polygon, start) }
            ?: zones.firstOrNull { Geo.contains(it.polygon, start) }
        if (selfZone != null) {
            val c = Geo.centroid(selfZone.polygon)
            val r = Geo.boundingRadiusM(selfZone.polygon)
            val brg = Geo.bearing(c, start) // 背离区域中心
            val esc = Geo.pointAt(start, maxOf(r * 1.3, 250.0), brg)
            return Target(esc, "撤离${selfZone.kind.cn}区")
        }

        // 尚未入区但被邻近灾情威胁：背离最近的区域中心
        val nearest = zones.minByOrNull { Geo.distToPolygonM(it.polygon, start) }
        if (nearest != null) {
            val c = Geo.centroid(nearest.polygon)
            val d = Geo.distanceM(start, c)
            val brg = Geo.bearing(c, start)
            val esc = Geo.pointAt(start, maxOf(d * 0.6 + 100.0, 200.0), brg)
            return Target(esc, "远离${nearest.kind.cn}区")
        }
        return null
    }

    // ---------------- A* 栅格寻路 ----------------

    private fun aStar(
        start: GeoPoint,
        goal: GeoPoint,
        zones: List<HazardZone>,
    ): List<GeoPoint>? {
        val origin = start
        val distToGoal = Geo.distanceM(start, goal)
        val halfM = maxOf(MIN_HALF_M, minOf(MAX_HALF_M, distToGoal + 250.0))
        val n = (2.0 * ceil(halfM / STEP_M)).toInt() + 1 // 奇数，中心在原点
        val off = n / 2

        fun toCell(p: GeoPoint): Pair<Int, Int> {
            val (x, y) = Geo.projectLocal(origin, p)
            return (x / STEP_M).roundToInt() + off to (y / STEP_M).roundToInt() + off
        }

        fun cellToPoint(cx: Int, cy: Int): GeoPoint {
            val x = (cx - off) * STEP_M
            val y = (cy - off) * STEP_M
            return Geo.unprojectLocal(origin, x, y)
        }

        val sc = toCell(start)
        val gc = toCell(goal)
        if (sc.first !in 0 until n || sc.second !in 0 until n ||
            gc.first !in 0 until n || gc.second !in 0 until n
        ) {
            return null // 目标太远，超出搜索范围
        }

        val total = n * n
        val g = DoubleArray(total) { Double.POSITIVE_INFINITY }
        val came = IntArray(total) { -1 }

        fun idx(x: Int, y: Int) = y * n + x

        val startId = idx(sc.first, sc.second)
        val goalId = idx(gc.first, gc.second)
        g[startId] = 0.0
        val open = BinaryHeap(total)
        open.push(startId, 0.0)

        // 8 方向：(dx,dy) 依次为 E,N,W,S,SE,NE,SW,NW
        val dirs = arrayOf(
            1 to 0, -1 to 0, 0 to 1, 0 to -1,
            1 to 1, 1 to -1, -1 to 1, -1 to -1,
        )

        var found = false
        while (open.isNotEmpty) {
            val cur = open.pop()
            if (cur == goalId) { found = true; break }
            val cx = cur % n
            val cy = cur / n
            val gCur = g[cur]
            for ((dx, dy) in dirs) {
                val nx = cx + dx
                val ny = cy + dy
                if (nx < 0 || ny < 0 || nx >= n || ny >= n) continue
                val nid = idx(nx, ny)
                val geometric = if (dx != 0 && dy != 0) STEP_M * DIAG else STEP_M
                val p = cellToPoint(nx, ny)
                val penalty = cellPenalty(p, zones)
                val newG = gCur + geometric + penalty
                if (newG < g[nid]) {
                    g[nid] = newG
                    came[nid] = cur
                    val h = Geo.distanceM(p, goal) // 到目标欧氏距离（米）
                    open.push(nid, newG + h)
                }
            }
        }
        if (!found) return null

        // 回溯路径
        val path = ArrayList<GeoPoint>()
        var c = goalId
        while (c != -1) {
            path.add(cellToPoint(c % n, c / n))
            c = came[c]
        }
        path.reverse()
        return path
    }

    /** 该格点位于灾情区时累计的穿行惩罚（无灾区为 0） */
    private fun cellPenalty(p: GeoPoint, zones: List<HazardZone>): Double {
        var cost = 0.0
        for (z in zones) {
            if (!Geo.contains(z.polygon, p)) continue
            cost += if (z.level == AlertLevel.LEVEL_2) COST_L2 else COST_L1
            if (z.kind == HazardKind.FLOOD) cost += COST_FLOOD_EXTRA
        }
        return cost
    }

    /** 简易二叉最小堆（f = g + h 越小越先出队） */
    private class BinaryHeap(capacity: Int) {
        private var size = 0

        // 用两个数组更直观：id + 优先级
        private val ids = IntArray(capacity)
        private val keys = DoubleArray(capacity)

        val isNotEmpty: Boolean get() = size > 0

        fun push(id: Int, key: Double) {
            var i = size++
            ids[i] = id
            keys[i] = key
            while (i > 0) {
                val parent = (i - 1) / 2
                if (keys[parent] <= keys[i]) break
                swap(i, parent)
                i = parent
            }
        }

        fun pop(): Int {
            val top = ids[0]
            size--
            if (size > 0) {
                ids[0] = ids[size]
                keys[0] = keys[size]
                var i = 0
                while (true) {
                    val l = 2 * i + 1
                    val r = l + 1
                    var best = i
                    if (l < size && keys[l] < keys[best]) best = l
                    if (r < size && keys[r] < keys[best]) best = r
                    if (best == i) break
                    swap(i, best)
                    i = best
                }
            }
            return top
        }

        private fun swap(a: Int, b: Int) {
            val ti = ids[a]; ids[a] = ids[b]; ids[b] = ti
            val tk = keys[a]; keys[a] = keys[b]; keys[b] = tk
        }
    }
}
