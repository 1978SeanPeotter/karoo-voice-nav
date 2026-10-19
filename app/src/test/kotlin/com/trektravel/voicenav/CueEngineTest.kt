package com.trektravel.voicenav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CueEngineTest {
    private fun ride(engine: CueEngine, distances: List<Double>, onRoute: Boolean? = true, speed: Double? = 8.0): List<Cue> {
        var t = 0L
        return distances.flatMap { d -> t += 1000; engine.onReading(d, onRoute, speed, t) }
    }

    @Test fun threeCuesPerTurnImperial() {
        val e = CueEngine().apply { units = Units.IMPERIAL }
        val cues = ride(e, (600 downTo 0 step 10).map { it.toDouble() })
        assertEquals(listOf(Cue.Turn(Tier.FAR), Cue.Turn(Tier.NEAR), Cue.Turn(Tier.NOW)), cues)
    }

    @Test fun nextTurnStartsFresh() {
        val e = CueEngine()
        val first = (600 downTo 0 step 10).map { it.toDouble() }
        val second = (700 downTo 0 step 10).map { it.toDouble() }
        assertEquals(6, ride(e, first + second).size)
    }

    @Test fun skipsTierWhenAlreadyTooClose() {
        // Route starts 200 m before a turn: "in a quarter mile" would be wrong, so it waits.
        val e = CueEngine()
        val cues = ride(e, (200 downTo 0 step 5).map { it.toDouble() })
        assertEquals(listOf(Cue.Turn(Tier.NEAR), Cue.Turn(Tier.NOW)), cues)
    }

    @Test fun nowCallComesEarlierAtSpeed() {
        val e = CueEngine()
        assertEquals(30.0, e.nowThreshold(5.0), 0.01)   // 18 km/h
        assertEquals(45.0, e.nowThreshold(15.0), 0.01)  // 54 km/h descent
        assertEquals(60.0, e.nowThreshold(30.0), 0.01)  // capped
    }

    @Test fun offRouteIsDebouncedAndSilencesTurns() {
        val e = CueEngine()
        var t = 0L
        fun step(d: Double, on: Boolean) = e.onReading(d, on, 8.0, t.also { t += 1000 })
        val out = ArrayList<Cue>()
        repeat(5) { out += step(300.0, false) }          // 5 s off: still quiet
        assertTrue(out.isEmpty())
        repeat(5) { out += step(250.0, false) }          // past 8 s: announce once
        assertEquals(listOf<Cue>(Cue.OffRoute), out)
        out.clear()
        out += step(90.0, true)                          // rejoin
        assertEquals(Cue.BackOnRoute, out.first())
    }

    @Test fun disabledTiersStaySilent() {
        val e = CueEngine().apply { farEnabled = false; nearEnabled = false }
        val cues = ride(e, (600 downTo 0 step 10).map { it.toDouble() })
        assertEquals(listOf<Cue>(Cue.Turn(Tier.NOW)), cues)
    }
}
