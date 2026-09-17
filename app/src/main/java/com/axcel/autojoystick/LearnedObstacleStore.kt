package com.axcel.autojoystick

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.hypot
import kotlin.math.roundToInt

data class LearnedObstacle(
    val x: Int,
    val y: Int,
    val escapeX: Float,
    val escapeY: Float,
    val hits: Int,
    val lastSeenAt: Long
)

/**
 * Small persistent memory of map positions where the character was unable to
 * make progress. It is intentionally a heuristic map, not a pretend ML model:
 * a future route uses the recorded escape direction as a detour around the
 * learned position.
 */
class LearnedObstacleStore(ctx: Context) {
    private val prefs = ctx.applicationContext
        .getSharedPreferences("aj_learned_obstacles", Context.MODE_PRIVATE)

    @Synchronized
    fun all(): List<LearnedObstacle> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val json = JSONArray(raw)
            buildList(json.length()) {
                for (i in 0 until json.length()) {
                    val item = json.optJSONObject(i) ?: continue
                    add(
                        LearnedObstacle(
                            x = item.optInt("x"),
                            y = item.optInt("y"),
                            escapeX = item.optDouble("escapeX", 0.0).toFloat(),
                            escapeY = item.optDouble("escapeY", -1.0).toFloat(),
                            hits = item.optInt("hits", 1).coerceAtLeast(1),
                            lastSeenAt = item.optLong("lastSeenAt", 0L)
                        )
                    )
                }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    @Synchronized
    fun record(x: Int, y: Int, escapeX: Float, escapeY: Float) {
        if (x !in 0..400 || y !in 0..400) return
        val safeLength = hypot(escapeX.toDouble(), escapeY.toDouble()).coerceAtLeast(0.01)
        val nextEscapeX = (escapeX / safeLength).toFloat()
        val nextEscapeY = (escapeY / safeLength).toFloat()
        val saved = all().toMutableList()
        val existingIndex = saved.indexOfFirst {
            hypot((it.x - x).toDouble(), (it.y - y).toDouble()) <= MERGE_RADIUS
        }
        val now = System.currentTimeMillis()
        if (existingIndex >= 0) {
            val old = saved[existingIndex]
            saved[existingIndex] = old.copy(
                escapeX = nextEscapeX,
                escapeY = nextEscapeY,
                hits = old.hits + 1,
                lastSeenAt = now
            )
        } else {
            saved += LearnedObstacle(x, y, nextEscapeX, nextEscapeY, 1, now)
        }
        while (saved.size > MAX_ENTRIES) saved.removeAt(0)
        save(saved)
    }

    /**
     * Return a short map-space waypoint when a learned obstacle is on the
     * direct route. The waypoint is deliberately outside the obstacle area so
     * the next OCR update can take over quickly.
     */
    @Synchronized
    fun detourTarget(
        current: Pair<Int, Int>,
        target: Pair<Int, Int>,
        arrivalRadius: Float
    ): Pair<Int, Int>? {
        val toTarget = hypot(
            (target.first - current.first).toDouble(),
            (target.second - current.second).toDouble()
        )
        if (toTarget <= arrivalRadius) return null

        val vx = (target.first - current.first).toDouble()
        val vy = (target.second - current.second).toDouble()
        val lineLengthSquared = vx * vx + vy * vy
        if (lineLengthSquared < 0.01) return null

        val candidate = all()
            .asSequence()
            .filter {
                hypot(
                    (it.x - target.first).toDouble(),
                    (it.y - target.second).toDouble()
                ) > arrivalRadius
            }
            .map { obstacle ->
                val projection = (
                    (obstacle.x - current.first) * vx +
                        (obstacle.y - current.second) * vy
                    ) / lineLengthSquared
                val t = projection.coerceIn(0.0, 1.0)
                val closestX = current.first + t * vx
                val closestY = current.second + t * vy
                val routeDistance = hypot(
                    obstacle.x - closestX,
                    obstacle.y - closestY
                )
                val currentDistance = hypot(
                    (obstacle.x - current.first).toDouble(),
                    (obstacle.y - current.second).toDouble()
                )
                obstacle to (routeDistance to currentDistance)
            }
            .filter { (_, distances) ->
                distances.first <= ROUTE_CORRIDOR || distances.second <= NEARBY_RADIUS
            }
            .minByOrNull { it.second.first }
            ?: return null

        val obstacle = candidate.first
        val distanceFromObstacle = candidate.second.second
        val escapeLength = hypot(obstacle.escapeX.toDouble(), obstacle.escapeY.toDouble())
            .coerceAtLeast(0.01)
        val detourDistance = if (distanceFromObstacle <= NEARBY_RADIUS) 8.0 else 6.0
        return (
            obstacle.x + obstacle.escapeX / escapeLength * detourDistance
            ).roundToInt() to (
            obstacle.y + obstacle.escapeY / escapeLength * detourDistance
            ).roundToInt()
    }

    @Synchronized
    fun count(): Int = all().size

    @Synchronized
    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    private fun save(items: List<LearnedObstacle>) {
        val json = JSONArray()
        items.forEach { item ->
            json.put(
                JSONObject()
                    .put("x", item.x)
                    .put("y", item.y)
                    .put("escapeX", item.escapeX.toDouble())
                    .put("escapeY", item.escapeY.toDouble())
                    .put("hits", item.hits)
                    .put("lastSeenAt", item.lastSeenAt)
            )
        }
        prefs.edit().putString(KEY, json.toString()).apply()
    }

    private companion object {
        const val KEY = "obstacles"
        const val MAX_ENTRIES = 80
        const val MERGE_RADIUS = 2.5
        const val NEARBY_RADIUS = 4.5
        const val ROUTE_CORRIDOR = 4.0
    }
}