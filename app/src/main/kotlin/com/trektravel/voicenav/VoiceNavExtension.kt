package com.trektravel.voicenav

import android.content.SharedPreferences
import android.util.Log
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.InRideAlert
import io.hammerhead.karooext.models.OnLocationChanged
import io.hammerhead.karooext.models.OnNavigationState
import io.hammerhead.karooext.models.OnStreamState
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UserProfile
import kotlin.math.abs

/**
 * The Karoo starts this service automatically (see AndroidManifest.xml) and keeps it
 * running in the background. It listens to navigation data and speaks turn cues.
 */
class VoiceNavExtension : KarooExtension("voicenav", BuildConfig.VERSION_NAME) {
    companion object {
        const val TAG = "VoiceNav"
    }

    private lateinit var karoo: KarooSystemService
    private lateinit var settings: Settings
    private lateinit var speaker: Speaker
    private val consumers = ArrayList<String>()
    private val engine = CueEngine()
    private val lock = Any()

    // Latest values from the Karoo
    @Volatile private var route: Route? = null
    @Volatile private var karooRouteDistance: Double? = null
    @Volatile private var location: LatLng? = null
    @Volatile private var speedMps: Double? = null
    @Volatile private var distToDestination: Double? = null
    @Volatile private var distToTurn: Double? = null
    @Volatile private var profileUnits: Units = Units.IMPERIAL
    @Volatile private var recording = false
    @Volatile private var navigating = false
    private var lastAlong: Double? = null

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> applySettings() }

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        speaker = Speaker(this)
        Status.set(this, "service", "started, version ${BuildConfig.VERSION_NAME}")
        applySettings()
        settings.registerListener(prefsListener)

        karoo = KarooSystemService(applicationContext)
        karoo.connect { connected ->
            Log.i(TAG, "Karoo connected=$connected")
            Status.set(this, "service", if (connected) "running and connected to Karoo" else "DISCONNECTED from Karoo")
            if (connected) subscribe()
        }
    }

    override fun onDestroy() {
        settings.unregisterListener(prefsListener)
        consumers.forEach { karoo.removeConsumer(it) }
        consumers.clear()
        karoo.disconnect()
        speaker.shutdown()
        super.onDestroy()
    }

    /** "Repeat" and "toggle" can be mapped to a Karoo button or a ride-screen shortcut. */
    override fun onBonusAction(actionId: String) {
        when (actionId) {
            "repeat" -> {
                val d = distToTurn ?: return
                val tier = when {
                    d <= engine.nowThreshold(speedMps) -> Tier.NOW
                    d <= engine.thresholds().near * 1.5 -> Tier.NEAR
                    else -> Tier.FAR
                }
                announce(Phrases.turn(tier, maneuverFor(d), currentUnits()), urgent = true)
            }
            "toggle" -> {
                settings.enabled = !settings.enabled
                if (settings.enabled) speaker.say(Phrases.READY, urgent = true)
            }
        }
    }

    private fun subscribe() {
        if (consumers.isNotEmpty()) return

        consumers += karoo.addConsumer { p: UserProfile ->
            profileUnits = if (p.preferredUnit.distance == UserProfile.PreferredUnit.UnitType.METRIC)
                Units.METRIC else Units.IMPERIAL
            applySettings()
        }
        consumers += karoo.addConsumer { s: RideState -> recording = s is RideState.Recording }
        consumers += karoo.addConsumer { e: OnLocationChanged -> location = LatLng(e.lat, e.lng) }
        consumers += karoo.addConsumer { e: OnNavigationState -> onNavigation(e.state) }

        consumers += karoo.addConsumer(OnStreamState.StartStreaming(DataType.Type.SPEED)) { e: OnStreamState ->
            speedMps = (e.state as? StreamState.Streaming)?.dataPoint?.values?.get(DataType.Field.SPEED)
        }
        consumers += karoo.addConsumer(OnStreamState.StartStreaming(DataType.Type.DISTANCE_TO_DESTINATION)) { e: OnStreamState ->
            distToDestination = (e.state as? StreamState.Streaming)?.dataPoint
                ?.values?.get(DataType.Field.DISTANCE_TO_DESTINATION)
        }
        consumers += karoo.addConsumer(OnStreamState.StartStreaming(DataType.Type.DISTANCE_TO_NEXT_TURN)) { e: OnStreamState ->
            val values = (e.state as? StreamState.Streaming)?.dataPoint?.values ?: return@addConsumer
            val d = values[DataType.Field.DISTANCE_TO_NEXT_TURN] ?: return@addConsumer
            val onRoute = values[DataType.Field.ON_ROUTE]?.let { it > 0.5 }
            onTurnDistance(d, onRoute)
        }
    }

    private fun onNavigation(state: OnNavigationState.NavigationState) {
        val wasNavigating = navigating
        synchronized(lock) {
            when (state) {
                is OnNavigationState.NavigationState.NavigatingRoute -> {
                    val r = Route(Geo.decodePolyline(state.routePolyline))
                    route = if (state.reversed) r.reversed() else r
                    karooRouteDistance = state.routeDistance
                    // Breadcrumb mode = Karoo has no turns to give us, so nothing to say.
                    navigating = !state.breadcrumb
                }
                is OnNavigationState.NavigationState.NavigatingToDestination -> {
                    val r = Route(Geo.decodePolyline(state.polyline))
                    route = r
                    karooRouteDistance = r.length
                    navigating = true
                }
                else -> {
                    route = null
                    karooRouteDistance = null
                    navigating = false
                }
            }
            if (navigating != wasNavigating) {
                engine.reset()
                lastAlong = null
            }
        }
        Log.i(TAG, "Navigation: $state (route ${route?.points?.size ?: 0} pts)")
        Status.set(this, "nav", when (state) {
            is OnNavigationState.NavigationState.NavigatingRoute ->
                "route \"${state.name}\"" + (if (state.breadcrumb) " - BREADCRUMB mode, Karoo gives no turns" else "")
            is OnNavigationState.NavigationState.NavigatingToDestination -> "navigating to a destination"
            else -> "not navigating"
        })
        if (navigating && !wasNavigating && settings.enabled) speaker.say(Phrases.READY)
    }

    private var lastStatusMs = 0L

    private fun onTurnDistance(d: Double, onRoute: Boolean?) {
        distToTurn = d
        val now = System.currentTimeMillis()
        if (now - lastStatusMs > 3_000) {
            lastStatusMs = now
            Status.set(this, "turn", "next turn ${d.toInt()} m" + (if (onRoute == false) ", OFF route" else ""))
        }
        if (!navigating || !settings.enabled) return
        if (settings.onlyWhileRecording && !recording) return

        val cues = synchronized(lock) { engine.onReading(d, onRoute, speedMps, System.currentTimeMillis()) }
        for (cue in cues) {
            when (cue) {
                is Cue.Turn -> {
                    val m = maneuverFor(d)
                    Log.i(TAG, "Cue ${cue.tier} at ${d.toInt()} m: $m")
                    announce(Phrases.turn(cue.tier, m, currentUnits()), urgent = cue.tier == Tier.NOW)
                }
                Cue.OffRoute -> announce(Phrases.OFF_ROUTE)
                Cue.BackOnRoute -> announce(Phrases.ON_ROUTE)
            }
        }
    }

    /** Speak it, log it, and (optionally) flash the words on the ride screen. */
    private fun announce(p: Phrase, urgent: Boolean = false) {
        speaker.say(p, urgent)
        Status.set(this, "cue", "\"${p.text}\"")
        if (settings.showOnScreen) {
            karoo.dispatch(
                InRideAlert(
                    id = "voicenav-cue",
                    icon = R.drawable.ic_launcher,
                    title = p.text,
                    detail = null,
                    autoDismissMs = 4_000,
                    backgroundColor = R.color.vn_alert_bg,
                    textColor = R.color.vn_alert_text,
                ),
            )
        }
    }

    /** Work out which way the upcoming turn goes, using our position on the route line. */
    private fun maneuverFor(distanceToTurn: Double): Maneuver {
        val r = route ?: return Maneuver.UNKNOWN
        if (!r.isUsable) return Maneuver.UNKNOWN
        val along = synchronized(lock) { estimateAlong(r) } ?: return Maneuver.UNKNOWN
        val result = TurnClassifier.classify(r, along + distanceToTurn)
        Log.d(TAG, "along=${along.toInt()} turnAt=${result.turnAlong.toInt()} angle=${result.angle.toInt()}")
        return result.maneuver
    }

    /** How far along the route we are, in meters. */
    private fun estimateAlong(r: Route): Double? {
        // 1) What the Karoo's own "distance to destination" implies, scaled to our decoded line.
        val total = karooRouteDistance
        val toDest = distToDestination
        val expected = if (total != null && toDest != null && total > 0) {
            ((total - toDest) * (r.length / total)).coerceIn(0.0, r.length)
        } else null

        // 2) Where GPS puts us on the line. A route can pass the same spot twice
        //    (out-and-back, loops), so pick the pass that agrees with (1) or with last time.
        val loc = location
        val candidates = if (loc != null) r.projectionCandidates(loc) else emptyList()
        val reference = expected ?: lastAlong
        val best = when {
            candidates.isEmpty() -> null
            reference == null -> candidates.first()
            else -> candidates.minByOrNull { abs(it - reference) }
        }
        val along = when {
            best != null && (expected == null || abs(best - expected) < 200) -> best
            expected != null -> expected
            else -> best
        }
        if (along != null) lastAlong = along
        return along
    }

    private fun currentUnits(): Units = when (settings.units) {
        "metric" -> Units.METRIC
        "imperial" -> Units.IMPERIAL
        else -> profileUnits
    }

    private fun applySettings() {
        synchronized(lock) {
            engine.units = currentUnits()
            engine.farEnabled = settings.farCue
            engine.nearEnabled = settings.nearCue
            engine.nowEnabled = settings.nowCue
            engine.offRouteEnabled = settings.offRouteCue
        }
        speaker.useSystemTts = settings.useSystemTts
    }
}
