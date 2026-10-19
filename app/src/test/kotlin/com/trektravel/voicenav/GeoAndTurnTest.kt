package com.trektravel.voicenav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoAndTurnTest {
    // ~meters to degrees near 43° N (Madison-ish)
    private val lat0 = 43.07
    private val lng0 = -89.40
    private val mLat = 1 / 111_320.0
    private val mLng = 1 / (111_320.0 * Math.cos(Math.toRadians(lat0)))
    private fun pt(northM: Double, eastM: Double) = LatLng(lat0 + northM * mLat, lng0 + eastM * mLng)

    @Test fun decodesGoogleExamplePolyline() {
        val pts = Geo.decodePolyline("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        assertEquals(3, pts.size)
        assertEquals(38.5, pts[0].lat, 1e-5); assertEquals(-120.2, pts[0].lng, 1e-5)
        assertEquals(40.7, pts[1].lat, 1e-5); assertEquals(-120.95, pts[1].lng, 1e-5)
        assertEquals(43.252, pts[2].lat, 1e-5); assertEquals(-126.453, pts[2].lng, 1e-5)
    }

    @Test fun northThenEastIsRightTurn() {
        val r = Route(listOf(pt(0.0, 0.0), pt(500.0, 0.0), pt(500.0, 500.0)))
        assertEquals(1000.0, r.length, 2.0)
        assertEquals(Maneuver.RIGHT, TurnClassifier.classify(r, 500.0).maneuver)
        // Karoo's turn distance a little off: should still snap to the corner.
        assertEquals(Maneuver.RIGHT, TurnClassifier.classify(r, 520.0).maneuver)
    }

    @Test fun northThenWestIsLeftTurn() {
        val r = Route(listOf(pt(0.0, 0.0), pt(500.0, 0.0), pt(500.0, -500.0)))
        assertEquals(Maneuver.LEFT, TurnClassifier.classify(r, 500.0).maneuver)
    }

    @Test fun reversedRouteFlipsTurn() {
        val r = Route(listOf(pt(0.0, 0.0), pt(500.0, 0.0), pt(500.0, 500.0))).reversed()
        assertEquals(Maneuver.LEFT, TurnClassifier.classify(r, 500.0).maneuver)
    }

    @Test fun gentleBendsAndSharpTurns() {
        val slight = Route(listOf(pt(0.0, 0.0), pt(500.0, 0.0), pt(500.0 + 400 * Math.cos(Math.toRadians(45.0)), -400 * Math.sin(Math.toRadians(45.0)))))
        assertEquals(Maneuver.SLIGHT_LEFT, TurnClassifier.classify(slight, 500.0).maneuver)
        val sharp = Route(listOf(pt(0.0, 0.0), pt(500.0, 0.0), pt(200.0, 150.0)))
        assertEquals(Maneuver.SHARP_RIGHT, TurnClassifier.classify(sharp, 500.0).maneuver)
        val uturn = Route(listOf(pt(0.0, 0.0), pt(500.0, 0.0), pt(0.0, 5.0)))
        assertEquals(Maneuver.UTURN, TurnClassifier.classify(uturn, 500.0).maneuver)
    }

    @Test fun outAndBackGivesTwoProjectionCandidates() {
        val r = Route(listOf(pt(0.0, 0.0), pt(1000.0, 0.0), pt(1000.0, 10.0), pt(0.0, 10.0)))
        val c = r.projectionCandidates(pt(300.0, 5.0))
        assertEquals(2, c.size)
        assertEquals(300.0, c[0], 3.0)
        assertTrue(c[1] > 1600 && c[1] < 1800)
    }
}
