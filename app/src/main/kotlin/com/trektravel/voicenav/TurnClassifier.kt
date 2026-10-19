package com.trektravel.voicenav

import kotlin.math.abs

/** Spoken maneuver. `key` matches the recorded clip names in res/raw. */
enum class Maneuver(val key: String, val phrase: String) {
    LEFT("left", "turn left"),
    RIGHT("right", "turn right"),
    SLIGHT_LEFT("slight_left", "bear left"),
    SLIGHT_RIGHT("slight_right", "bear right"),
    SHARP_LEFT("sharp_left", "make a sharp left"),
    SHARP_RIGHT("sharp_right", "make a sharp right"),
    KEEP_LEFT("keep_left", "keep left"),
    KEEP_RIGHT("keep_right", "keep right"),
    UTURN("uturn", "make a U-turn"),
    STRAIGHT("straight", "continue straight"),
    UNKNOWN("unknown", "turn ahead"),
}

/**
 * The Karoo tells extensions HOW FAR the next turn is, but not WHICH WAY.
 * So we work it out from the route line itself: compare the direction of travel
 * just before the turn point with the direction just after it.
 */
object TurnClassifier {
    /** How far before/after the turn we sample the route to measure direction. */
    private const val ARM_M = 25.0

    /** How far either side of Karoo's turn distance we look for the sharpest bend. */
    private const val SEARCH_M = 30.0

    data class Result(val maneuver: Maneuver, val angle: Double, val turnAlong: Double)

    fun classify(route: Route, turnAlong: Double): Result {
        if (!route.isUsable) return Result(Maneuver.UNKNOWN, 0.0, turnAlong)

        // Snap to the route vertex with the biggest heading change near the reported turn.
        var bestAlong = turnAlong
        var bestAngle = angleAt(route, turnAlong)
        for (i in route.points.indices) {
            val d = route.cumulative[i]
            if (d < turnAlong - SEARCH_M) continue
            if (d > turnAlong + SEARCH_M) break
            val a = angleAt(route, d)
            if (abs(a) > abs(bestAngle)) {
                bestAngle = a
                bestAlong = d
            }
        }
        return Result(fromAngle(bestAngle), bestAngle, bestAlong)
    }

    private fun angleAt(route: Route, along: Double): Double {
        if (along - ARM_M < 0 || along + ARM_M > route.length) {
            // Too close to the start/end of the route to measure both sides properly.
            val before = route.pointAt((along - ARM_M).coerceAtLeast(0.0))
            val at = route.pointAt(along)
            val after = route.pointAt((along + ARM_M).coerceAtMost(route.length))
            if (Geo.distance(before, at) < 3 || Geo.distance(at, after) < 3) return 0.0
            return Geo.turnAngle(Geo.bearing(before, at), Geo.bearing(at, after))
        }
        val before = route.pointAt(along - ARM_M)
        val at = route.pointAt(along)
        val after = route.pointAt(along + ARM_M)
        return Geo.turnAngle(Geo.bearing(before, at), Geo.bearing(at, after))
    }

    /** Positive angle = right turn, negative = left. Degrees. */
    fun fromAngle(angle: Double): Maneuver {
        val a = abs(angle)
        val right = angle > 0
        return when {
            a < 12 -> Maneuver.STRAIGHT
            a < 35 -> if (right) Maneuver.KEEP_RIGHT else Maneuver.KEEP_LEFT
            a < 60 -> if (right) Maneuver.SLIGHT_RIGHT else Maneuver.SLIGHT_LEFT
            a < 125 -> if (right) Maneuver.RIGHT else Maneuver.LEFT
            a < 165 -> if (right) Maneuver.SHARP_RIGHT else Maneuver.SHARP_LEFT
            else -> Maneuver.UTURN
        }
    }
}
