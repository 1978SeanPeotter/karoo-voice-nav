package com.trektravel.voicenav

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLng(val lat: Double, val lng: Double)

object Geo {
    private const val EARTH_RADIUS_M = 6_371_000.0

    /** Decodes a Google encoded polyline (precision 5 is what Karoo sends). */
    fun decodePolyline(encoded: String, precision: Int = 5): List<LatLng> {
        val factor = Math.pow(10.0, precision.toDouble())
        val out = ArrayList<LatLng>(encoded.length / 4)
        var index = 0
        var lat = 0L
        var lng = 0L
        while (index < encoded.length) {
            var result = 0L
            var shift = 0
            var b: Int
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f).toLong() shl shift)
                shift += 5
            } while (b >= 0x20 && index < encoded.length)
            lat += if (result and 1L != 0L) (result shr 1).inv() else (result shr 1)

            if (index >= encoded.length) break
            result = 0L
            shift = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f).toLong() shl shift)
                shift += 5
            } while (b >= 0x20 && index < encoded.length)
            lng += if (result and 1L != 0L) (result shr 1).inv() else (result shr 1)

            out.add(LatLng(lat / factor, lng / factor))
        }
        return out
    }

    fun distance(a: LatLng, b: LatLng): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(h), sqrt(1 - h))
    }

    /** Compass bearing from a to b, 0..360, 0 = north, 90 = east. */
    fun bearing(a: LatLng, b: LatLng): Double {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val y = sin(dLng) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLng)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** Signed heading change from bearing `from` to bearing `to`: -180..180, positive = right. */
    fun turnAngle(from: Double, to: Double): Double {
        var d = (to - from) % 360.0
        if (d > 180) d -= 360.0
        if (d < -180) d += 360.0
        return d
    }

    fun lerp(a: LatLng, b: LatLng, t: Double) =
        LatLng(a.lat + (b.lat - a.lat) * t, a.lng + (b.lng - a.lng) * t)

    fun absAngle(d: Double) = abs(d)
}

/**
 * A route as a list of points with the cumulative distance (meters) to each point,
 * so we can ask "where is the route 1,250 m from the start?".
 */
class Route(rawPoints: List<LatLng>) {
    val points: List<LatLng>
    val cumulative: DoubleArray
    val length: Double

    init {
        // Drop duplicate consecutive points; they have no bearing and break the math.
        val pts = ArrayList<LatLng>(rawPoints.size)
        for (p in rawPoints) {
            if (pts.isEmpty() || Geo.distance(pts.last(), p) > 0.5) pts.add(p)
        }
        points = pts
        cumulative = DoubleArray(pts.size)
        for (i in 1 until pts.size) {
            cumulative[i] = cumulative[i - 1] + Geo.distance(pts[i - 1], pts[i])
        }
        length = if (pts.isEmpty()) 0.0 else cumulative.last()
    }

    val isUsable get() = points.size >= 2

    /** Point on the route at a given distance from the start (clamped to the ends). */
    fun pointAt(distanceAlong: Double): LatLng {
        if (distanceAlong <= 0) return points.first()
        if (distanceAlong >= length) return points.last()
        val i = segmentIndexAt(distanceAlong)
        val segLen = cumulative[i + 1] - cumulative[i]
        val t = if (segLen <= 0) 0.0 else (distanceAlong - cumulative[i]) / segLen
        return Geo.lerp(points[i], points[i + 1], t)
    }

    /** Index i such that cumulative[i] <= d < cumulative[i+1]. */
    fun segmentIndexAt(d: Double): Int {
        var lo = 0
        var hi = cumulative.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (cumulative[mid] <= d) lo = mid else hi = mid
        }
        return lo.coerceAtMost(points.size - 2)
    }

    /**
     * Every place the route passes within [maxOffsetM] of [p], as distance-along values.
     * One per pass, so an out-and-back returns two candidates and a loop returns one per lap.
     */
    fun projectionCandidates(p: LatLng, maxOffsetM: Double = 60.0): List<Double> {
        // Work in a local flat projection around p; accurate to well under a meter at these ranges.
        val mPerDegLat = 111_320.0
        val mPerDegLng = 111_320.0 * cos(Math.toRadians(p.lat))
        data class Hit(val along: Double, val offset: Double, val seg: Int)

        val hits = ArrayList<Hit>()
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            val ax = (a.lng - p.lng) * mPerDegLng
            val ay = (a.lat - p.lat) * mPerDegLat
            val bx = (b.lng - p.lng) * mPerDegLng
            val by = (b.lat - p.lat) * mPerDegLat
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else ((-ax * dx - ay * dy) / len2).coerceIn(0.0, 1.0)
            val cx = ax + t * dx
            val cy = ay + t * dy
            val off = sqrt(cx * cx + cy * cy)
            if (off <= maxOffsetM) {
                hits.add(Hit(cumulative[i] + t * (cumulative[i + 1] - cumulative[i]), off, i))
            }
        }
        // Hits close together along the route are the same pass; keep the closest point of each.
        // A gap in along-route distance means the route left and came back (a second pass).
        val result = ArrayList<Double>()
        var best: Hit? = null
        var lastAlong = Double.NEGATIVE_INFINITY
        for (h in hits) {
            if (best != null && h.along - lastAlong > 2 * maxOffsetM) {
                result.add(best.along)
                best = null
            }
            if (best == null || h.offset < best.offset) best = h
            lastAlong = h.along
        }
        best?.let { result.add(it.along) }
        return result
    }

    fun reversed(): Route = Route(points.reversed())
}
