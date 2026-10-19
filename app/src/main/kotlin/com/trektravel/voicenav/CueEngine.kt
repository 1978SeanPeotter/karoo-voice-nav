package com.trektravel.voicenav

/** Which announcement to make for an upcoming turn. */
enum class Tier { FAR, NEAR, NOW }

enum class Units { METRIC, IMPERIAL }

sealed class Cue {
    data class Turn(val tier: Tier) : Cue()
    data object OffRoute : Cue()
    data object BackOnRoute : Cue()
}

/**
 * Decides WHEN to speak. Pure logic (no Android), so it is unit-tested.
 *
 * Feed it every "distance to next turn" reading from the Karoo. Each turn gets up to
 * three announcements: far ("In a quarter mile, ..."), near ("In 300 feet, ...") and now.
 * A tier is skipped if we're already well past its distance, so the number spoken is
 * never a lie by more than ~25%.
 */
class CueEngine {
    data class Thresholds(val far: Double, val near: Double)

    companion object {
        val METRIC = Thresholds(far = 400.0, near = 100.0)
        val IMPERIAL = Thresholds(far = 402.3, near = 91.4) // quarter mile, 300 ft

        /** "Now" triggers at max(this, ~3 seconds of riding) before the turn. */
        const val NOW_MIN_M = 30.0
        const val NOW_LEAD_S = 3.0
        const val NOW_MAX_M = 60.0

        /** Off route must persist this long before we say so (GPS wobble filter). */
        const val OFF_ROUTE_DEBOUNCE_MS = 8_000L

        /** A jump up in distance bigger than this means the last turn is behind us. */
        const val NEW_TURN_JUMP_M = 30.0
    }

    var units: Units = Units.IMPERIAL
    var farEnabled = true
    var nearEnabled = true
    var nowEnabled = true
    var offRouteEnabled = true

    private var lastDistance: Double? = null
    private val fired = HashSet<Tier>()

    private var onRoute = true
    private var offRouteSince: Long? = null
    private var announcedOffRoute = false

    fun reset() {
        lastDistance = null
        fired.clear()
        onRoute = true
        offRouteSince = null
        announcedOffRoute = false
    }

    fun thresholds() = if (units == Units.METRIC) METRIC else IMPERIAL

    fun nowThreshold(speedMps: Double?): Double =
        ((speedMps ?: 0.0) * NOW_LEAD_S).coerceIn(NOW_MIN_M, NOW_MAX_M)

    /**
     * @param distance meters to the next turn, from the Karoo
     * @param onRouteNow Karoo's on-route flag (null if unknown)
     * @param speedMps current speed, for timing the "now" call (null if unknown)
     * @param nowMs clock, injected for tests
     * @return the cues to speak right now, in order (usually empty)
     */
    fun onReading(distance: Double, onRouteNow: Boolean?, speedMps: Double?, nowMs: Long): List<Cue> {
        val out = ArrayList<Cue>(2)

        // ---- off route / back on route ----
        if (onRouteNow != null) {
            if (!onRouteNow) {
                if (offRouteSince == null) offRouteSince = nowMs
                if (!announcedOffRoute && nowMs - offRouteSince!! >= OFF_ROUTE_DEBOUNCE_MS) {
                    announcedOffRoute = true
                    if (offRouteEnabled) out.add(Cue.OffRoute)
                }
            } else {
                offRouteSince = null
                if (announcedOffRoute) {
                    announcedOffRoute = false
                    if (offRouteEnabled) out.add(Cue.BackOnRoute)
                    // Fresh start for the turn we've just rejoined toward.
                    fired.clear()
                }
            }
            onRoute = onRouteNow
        }
        // Turn directions from the route line are meaningless while we're off it.
        if (announcedOffRoute) {
            lastDistance = distance
            return out
        }

        // ---- new turn? ----
        val last = lastDistance
        if (last != null && distance > last + NEW_TURN_JUMP_M) fired.clear()
        lastDistance = distance

        // ---- which tier is due? Only the closest one that applies. ----
        val t = thresholds()
        val nowM = nowThreshold(speedMps)
        when {
            distance <= nowM -> {
                if (Tier.NOW !in fired) {
                    fired.addAll(Tier.entries)
                    if (nowEnabled) out.add(Cue.Turn(Tier.NOW))
                }
            }
            distance <= t.near -> {
                if (Tier.NEAR !in fired) {
                    fired.add(Tier.NEAR); fired.add(Tier.FAR)
                    // Too close for "In 300 feet" to be honest? Wait for "now" instead.
                    if (nearEnabled && distance >= t.near * 0.6) out.add(Cue.Turn(Tier.NEAR))
                }
            }
            distance <= t.far -> {
                if (Tier.FAR !in fired) {
                    fired.add(Tier.FAR)
                    if (farEnabled && distance >= t.far * 0.75) out.add(Cue.Turn(Tier.FAR))
                }
            }
        }
        return out
    }
}
