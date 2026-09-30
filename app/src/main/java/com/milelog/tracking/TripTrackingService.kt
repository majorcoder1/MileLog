package com.milelog.tracking

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import com.milelog.MainActivity
import com.milelog.MileLogApp
import com.milelog.R
import com.milelog.data.Fmt
import com.milelog.data.Repo
import com.milelog.data.Trip
import com.milelog.data.TripSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Records driving, one leg at a time.
 *
 * A working day is not one journey. Stop at a restaurant, sit for four minutes, drive
 * on — that is two legs, and recording it as a single five-hour trip makes the day
 * impossible to check against anything and loses the lot if the process dies once.
 *
 * So the service has two states. While a leg is running it holds a high-accuracy
 * location stream. When the vehicle has been still for a couple of minutes it closes
 * the leg, drops to the passive provider — position updates that cost nothing because
 * they are collected whenever some other app asks for a fix — and waits. Movement, or a
 * drive-detection event, opens the next leg. GPS is off for the whole of that wait,
 * which is where the battery goes.
 *
 * Location comes from Android's own LocationManager rather than Google's fused provider,
 * so recording a drive does not depend on Google Play services being present or
 * permitted. On a phone where Google cannot do the detecting either, this service also
 * stands the watch itself: see [ACTION_WATCH] and [DriveWatch].
 */
class TripTrackingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val locations by lazy { getSystemService(LocationManager::class.java) }
    private lateinit var repo: Repo

    // Written on the main thread by onFix, read from IO when a leg is saved, so every
    // one of these needs to be visible across threads.
    @Volatile private var tripId: Long = 0
    @Volatile private var miles = 0.0
    @Volatile private var startedAt = 0L
    @Volatile private var autoStarted = false
    @Volatile private var last: Location? = null
    /** Guarded by itself. Never iterate it without holding the lock. */
    private val points = mutableListOf<Pair<Double, Double>>()
    @Volatile private var stopTimer: Job? = null
    @Volatile private var idleTimer: Job? = null
    @Volatile private var saving = false
    /** Set synchronously, unlike tripId, so a second START cannot slip past the guard. */
    @Volatile private var starting = false
    @Volatile private var lastPersistedAt = 0L
    /** The arithmetic itself lives here, where it can be driven on a desk. */
    private val meter = MileageMeter()
    @Volatile private var parkedAt: Location? = null
    // Kept so a short-looking leg can be explained rather than guessed at.
    @Volatile private var fixesUsed = 0
    @Volatile private var droppedLegs = 0
    @Volatile private var droppedMiles = 0.0
    /**
     * One announcement per driving session, not one per leg. Stop-and-go work splits
     * into dozens of legs a day and a chime for every one of them would be unusable.
     */
    @Volatile private var announcedThisSession = false

    // ---- standing the watch without Google ------------------------------------------
    /** True when this service, rather than Google, is what notices the next drive. */
    @Volatile private var standingWatch = false
    @Volatile private var watchJob: Job? = null
    @Volatile private var lastCheckAt = 0L
    /** The decision "is this a drive", kept separate so it can be tested. */
    private val watch = DriveWatch()
    /** Set by the phone's own movement sensor, cleared when a check acts on it. */
    private val motionSeen = AtomicBoolean(true)
    @Volatile private var motionTrigger: TriggerEventListener? = null
    /** What to say on the notification while watching, when it is not the usual. */
    @Volatile private var watchNote: String? = null

    private val recording: Boolean get() = tripId != 0L

    private val listener = LocationListenerCompat { location -> onFix(location) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        repo = Repo.get(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                // A previous command may have stood this instance down. It is being
                // reused, so the shutdown flag has to go or nothing can stop it later.
                saving = false
                // Android can refuse this outright: a location service started from the
                // background needs "Allow all the time". Refusal must not kill the app.
                if (!promoteToForeground()) {
                    warnCannotTrack()
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (!recording) startLeg(intent.getBooleanExtra(EXTRA_AUTO, false))
            }
            ACTION_WATCH -> {
                saving = false
                // Nothing to tell the user if this is refused: no drive has been missed
                // yet, and the app re-arms the watch every time it is opened.
                if (!promoteToForeground()) {
                    Log.w(TAG, "Not allowed to stand watch from the background just now")
                    stopSelf()
                    return START_NOT_STICKY
                }
                standingWatch = true
                if (!recording) beginWatching(from = last ?: parkedAt)
                publishWatching()
                updateNotification()
            }
            ACTION_STOP_WATCH -> {
                standingWatch = false
                if (recording) {
                    updateNotification()
                } else {
                    shutDown()
                }
            }
            ACTION_STOP -> {
                standingWatch = false
                endLeg(discard = false)
                shutDown()
            }
            ACTION_DISCARD -> {
                standingWatch = false
                endLeg(discard = true)
                shutDown()
            }
            ACTION_ARM_STOP -> armStop()
            ACTION_CANCEL_STOP -> { stopTimer?.cancel(); stopTimer = null }
            else -> {
                // A null action means the system recreated us after killing the process.
                // Opening a fresh leg here would never be promoted to the foreground, so
                // it would collect no location and leave an empty row behind.
                stopSelf()
                return START_NOT_STICKY
            }
        }
        // Deliberately not sticky: a resurrected service cannot promote itself, and a
        // half-alive tracker is worse than none.
        return START_NOT_STICKY
    }

    // ---- legs ---------------------------------------------------------------------

    private fun startLeg(auto: Boolean) {
        if (recording || starting) return
        starting = true
        if (!hasLocationPermission()) {
            starting = false
            warnCannotTrack()
            shutDown()
            return
        }

        idleTimer?.cancel()
        stopWatching()
        startedAt = System.currentTimeMillis()
        autoStarted = auto
        miles = 0.0
        meter.reset()
        last = null
        parkedAt = null
        fixesUsed = 0
        droppedLegs = 0
        droppedMiles = 0.0
        synchronized(points) { points.clear() }

        scope.launch {
            val purposeId = defaultPurposeForNow()
            val id = repo.trips.insert(
                Trip(
                    startEpoch = startedAt,
                    endEpoch = startedAt,
                    miles = 0.0,
                    purposeId = purposeId,
                    vehicleId = repo.defaultVehicleId(),
                    source = TripSource.GPS,
                    autoDetected = auto
                )
            )
            tripId = id
            repo.prefs.activeTripId = id
            starting = false
            Log.i(TAG, "Leg $id started, ${if (auto) "detected automatically" else "started by hand"}")
            TripTracker.set(
                LiveTrip(active = true, tripId = id, startedAt = startedAt, autoStarted = auto)
            )
        }

        requestUpdates(active = true)
        updateNotification()

        if (auto && !announcedThisSession) {
            announcedThisSession = true
            announceDetected()
        }
    }

    /**
     * Closes the current leg and drops to the cheap watching state. The service stays
     * alive briefly so a quick turnaround does not have to pay for a cold start — or
     * indefinitely, when it is this service rather than Google keeping the watch.
     */
    private fun endLeg(discard: Boolean) {
        stopTimer?.cancel()
        stopTimer = null
        val id = tripId
        if (id == 0L) return

        val endedAt = System.currentTimeMillis()
        val finalMiles = miles
        val snapshot = synchronized(points) { points.toList() }
        val path = encodePath(snapshot)
        val first = snapshot.firstOrNull()
        val lastPoint = snapshot.lastOrNull()

        Log.i(
            TAG,
            "Leg $id finished: ${"%.2f".format(finalMiles)} mi over " +
                "${(endedAt - startedAt) / 60000} min, $fixesUsed fixes used, " +
                "$droppedLegs legs dropped worth ${"%.2f".format(droppedMiles)} mi"
        )

        tripId = 0
        parkedAt = last
        TripTracker.clear()

        scope.launch {
            val trip = repo.trips.byId(id)
            if (trip != null) {
                // A drive under a tenth of a mile is noise, not a trip.
                if (discard || finalMiles < MIN_MILES) {
                    repo.trips.delete(trip)
                } else {
                    val startAddr = first?.let { Geo.addressOf(this@TripTrackingService, it.first, it.second) } ?: ""
                    val endAddr = lastPoint?.let { Geo.addressOf(this@TripTrackingService, it.first, it.second) } ?: ""
                    repo.trips.update(
                        trip.copy(
                            endEpoch = endedAt,
                            miles = finalMiles,
                            startLat = first?.first, startLon = first?.second,
                            endLat = lastPoint?.first, endLon = lastPoint?.second,
                            startAddress = startAddr,
                            endAddress = endAddr,
                            pathCsv = path,
                            updatedAt = endedAt
                        )
                    )
                }
            }
            repo.prefs.activeTripId = 0L
        }

        if (standingWatch) {
            beginWatching(from = parkedAt)
            publishWatching()
        } else {
            requestUpdates(active = false)
            armIdleShutdown()
        }
        updateNotification()
    }

    /** Nothing more expected for a while; let go of everything. */
    private fun shutDown() {
        if (saving) return
        saving = true
        stopTimer?.cancel()
        idleTimer?.cancel()
        stopWatching()
        runCatching { locations?.removeUpdates(listener) }
        scope.launch {
            TripTracker.clear()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun armIdleShutdown() {
        idleTimer?.cancel()
        idleTimer = scope.launch {
            delay(IDLE_SHUTDOWN_MS)
            Log.i(TAG, "Idle with no movement; standing down until the next drive")
            // Drive detection will start us again when it matters.
            withContext(Dispatchers.Main) { shutDown() }
        }
    }

    /** Auto-detect said the drive ended. Wait it out in case it was a long red light. */
    private fun armStop() {
        if (!recording) return
        stopTimer?.cancel()
        stopTimer = scope.launch {
            delay(STOP_GRACE_MS)
            // Back to the main thread: onFix runs there, and the meter is not shared.
            withContext(Dispatchers.Main) { endLeg(discard = false) }
        }
    }

    // ---- watching, with no Google in it ---------------------------------------------

    /**
     * Starts the cheap watch for the next drive: free passive fixes, the phone's own
     * movement sensor, and a slow loop that takes a reading of its own when the phone
     * has actually moved. GPS itself stays off until one of those says to look.
     */
    private fun beginWatching(from: Location?) {
        requestUpdates(active = false)
        watch.reset()
        from?.let { watch.anchor(it.toWatchFix()) }
        armMotionTrigger()
        watchJob?.cancel()
        watchJob = scope.launch {
            while (standingWatch && !recording) {
                delay(TICK_MS)
                if (!standingWatch || recording) break
                val moved = motionSeen.getAndSet(false)
                val since = System.currentTimeMillis() - lastCheckAt
                val due = when {
                    // The phone says it has moved. That is what we were waiting for.
                    moved -> true
                    // No movement sensor to wait on, so this loop is the only signal.
                    !hasMotionSensor() -> since >= NO_SENSOR_CHECK_MS
                    // A look now and then regardless, in case the sensor is the thing
                    // that has stopped reporting.
                    else -> since >= RESTING_CHECK_MS
                }
                if (due) checkForDriving("loop")
            }
        }
    }

    private fun stopWatching() {
        watchJob?.cancel()
        watchJob = null
        disarmMotionTrigger()
    }

    /**
     * The phone's own significant-motion sensor: hardware, no Google, and free until it
     * fires. It is one-shot, so every trigger re-arms it. A parked car sets this off the
     * moment it pulls away, which is the whole point.
     */
    private fun armMotionTrigger() {
        disarmMotionTrigger()
        val sensors = getSystemService(SensorManager::class.java) ?: return
        val sensor = sensors.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION) ?: return
        val trigger = object : TriggerEventListener() {
            override fun onTrigger(event: TriggerEvent?) {
                motionSeen.set(true)
                motionTrigger = null
                if (!standingWatch || recording) return
                armMotionTrigger()
                // Look now rather than waiting for the next tick: this is the phone
                // telling us it has started moving.
                scope.launch { checkForDriving("movement") }
            }
        }
        motionTrigger = trigger
        runCatching { sensors.requestTriggerSensor(trigger, sensor) }
            .onFailure { motionTrigger = null }
    }

    private fun disarmMotionTrigger() {
        val trigger = motionTrigger ?: return
        motionTrigger = null
        val sensors = getSystemService(SensorManager::class.java) ?: return
        val sensor = sensors.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION) ?: return
        runCatching { sensors.cancelTriggerSensor(trigger, sensor) }
    }

    private fun hasMotionSensor(): Boolean =
        getSystemService(SensorManager::class.java)
            ?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION) != null

    /**
     * Takes one reading and opens a leg if it looks like a drive. Guarded so a phone
     * being carried around cannot turn this into a continuous GPS session.
     */
    private suspend fun checkForDriving(because: String) {
        if (!standingWatch || recording) return
        val now = System.currentTimeMillis()
        if (now - lastCheckAt < MIN_CHECK_GAP_MS) return
        lastCheckAt = now

        val wake = runCatching {
            getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "milelog:watch")
                ?.apply { acquire(FIX_TIMEOUT_MS + 5_000L) }
        }.getOrNull()

        try {
            val fix = freshFix()
            if (fix == null) {
                val off = locations?.let { lm ->
                    runCatching { !lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)
                } ?: false
                watchNote = if (off) {
                    "Location is turned off, so MileLog cannot see the car move."
                } else {
                    null
                }
                updateNotification()
                return
            }
            watchNote = null
            // onFix judges passive fixes on the main thread and DriveWatch keeps state
            // between fixes, so this one is judged there too rather than racing it.
            val verdict = withContext(Dispatchers.Main) { watch.consider(fix.toWatchFix()) }
            Log.i(
                TAG,
                "Watch check ($because): $verdict at " +
                    "${watch.lastMph?.let { "%.1f".format(it) } ?: "no"} mph"
            )
            if (verdict == DriveWatch.Verdict.DRIVING) {
                withContext(Dispatchers.Main) { startLeg(auto = true) }
            }
        } finally {
            runCatching { if (wake?.isHeld == true) wake.release() }
        }
    }

    /**
     * A position to judge, cheapest first: a reading some other app has just paid for,
     * and only failing that one of our own.
     */
    private suspend fun freshFix(): Location? {
        val lm = locations ?: return null
        if (!hasLocationPermission()) return null

        val providers = buildList {
            add(LocationManager.GPS_PROVIDER)
            if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
        }
        val cached = providers
            .mapNotNull { provider ->
                runCatching { lm.getLastKnownLocation(provider) }.getOrNull()
            }
            .maxByOrNull { it.time }
        if (cached != null && System.currentTimeMillis() - cached.time < CACHED_FIX_MAX_AGE_MS) {
            return cached
        }
        return ownFix(lm)
    }

    /** Turns GPS on for as long as it takes to get one fix, and no longer. */
    @SuppressLint("MissingPermission")
    private suspend fun ownFix(lm: LocationManager): Location? {
        if (!runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)) {
            return null
        }
        return withTimeoutOrNull(FIX_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val once = object : LocationListenerCompat {
                    override fun onLocationChanged(location: Location) {
                        runCatching { lm.removeUpdates(this) }
                        if (cont.isActive) cont.resume(location)
                    }
                }
                cont.invokeOnCancellation { runCatching { lm.removeUpdates(once) } }
                val request = LocationRequestCompat.Builder(1000L)
                    .setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY)
                    .build()
                runCatching {
                    LocationManagerCompat.requestLocationUpdates(
                        lm, LocationManager.GPS_PROVIDER, request, once, mainLooper
                    )
                }.onFailure { if (cont.isActive) cont.resume(null) }
            }
        }
    }

    private fun publishWatching() {
        if (recording) return
        TripTracker.set(LiveTrip(watching = standingWatch))
    }

    // ---- location -----------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun requestUpdates(active: Boolean) {
        val lm = locations ?: return
        if (!hasLocationPermission()) return
        runCatching { lm.removeUpdates(listener) }

        val request: LocationRequestCompat
        val provider: String
        if (active) {
            request = LocationRequestCompat.Builder(ACTIVE_INTERVAL_MS)
                .setMinUpdateIntervalMillis(ACTIVE_MIN_INTERVAL_MS)
                // No distance filter: a fix every few seconds follows a curve, where one
                // every eight metres of displacement cuts the corners off it.
                .setMinUpdateDistanceMeters(0f)
                .setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY)
                .build()
            provider = LocationManager.GPS_PROVIDER
        } else {
            // Passive costs nothing: it only ever hands us a fix some other app already
            // paid for. Between legs this is the whole of our location use.
            request = LocationRequestCompat.Builder(PASSIVE_INTERVAL_MS)
                .setMinUpdateDistanceMeters(RESUME_METERS)
                .setQuality(LocationRequestCompat.QUALITY_LOW_POWER)
                .build()
            provider = LocationManager.PASSIVE_PROVIDER
        }

        runCatching {
            LocationManagerCompat.requestLocationUpdates(lm, provider, request, listener, mainLooper)
        }.onFailure { Log.w(TAG, "Could not ask for location: ${it.message}") }
    }

    private fun onFix(loc: Location) {
        if (!recording) {
            // Watching. A free fix showing the car is under way is the cue to open the
            // next leg — the same test the periodic check uses.
            if (watch.consider(loc.toWatchFix()) == DriveWatch.Verdict.DRIVING) {
                Log.i(TAG, "Movement seen while watching; opening the next leg")
                startLeg(auto = true)
            }
            return
        }

        val result = meter.accept(
            MileageMeter.Fix(
                latitude = loc.latitude,
                longitude = loc.longitude,
                timeMillis = loc.time,
                speedMps = if (loc.hasSpeed()) loc.speed.toDouble() else null,
                accuracyMeters = if (loc.hasAccuracy()) loc.accuracy.toDouble() else null
            )
        )
        droppedLegs = meter.droppedLegs
        droppedMiles = meter.droppedMiles
        fixesUsed = meter.fixesCounted
        miles = meter.miles

        when (result.outcome) {
            MileageMeter.Outcome.LEG_ENDED -> {
                Log.i(TAG, "Stopped long enough to be finished; closing the leg")
                endLeg(discard = false)
                return
            }
            MileageMeter.Outcome.COUNTED, MileageMeter.Outcome.ANCHORED -> Unit
            // Fuzzy, stationary, below the floor, or an impossible jump: nothing to add,
            // and nothing worth putting on the map either.
            else -> return
        }

        last = loc
        val snapshot = synchronized(points) {
            if (points.size < MAX_POINTS) {
                points += loc.latitude to loc.longitude
            } else {
                // Past the ceiling, keep moving the final point rather than freezing it,
                // so a long leg still ends where it actually ended.
                points[points.lastIndex] = loc.latitude to loc.longitude
            }
            points.toList()
        }
        TripTracker.update {
            it.copy(miles = miles, points = snapshot, lastFixAt = System.currentTimeMillis())
        }
        updateNotification()

        val now = System.currentTimeMillis()
        if (now - lastPersistedAt >= PERSIST_EVERY_MS) {
            lastPersistedAt = now
            persistProgress(snapshot)
        }
    }

    /** Saves how far we have got, so a killed process costs seconds rather than the leg. */
    private fun persistProgress(snapshot: List<Pair<Double, Double>>) {
        val id = tripId
        if (id == 0L) return
        val current = miles
        val first = snapshot.firstOrNull()
        val latest = snapshot.lastOrNull()
        scope.launch {
            runCatching {
                val trip = repo.trips.byId(id) ?: return@launch
                repo.trips.update(
                    trip.copy(
                        endEpoch = System.currentTimeMillis(),
                        miles = current,
                        startLat = first?.first ?: trip.startLat,
                        startLon = first?.second ?: trip.startLon,
                        endLat = latest?.first,
                        endLon = latest?.second,
                        pathCsv = encodePath(snapshot),
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }.onFailure { Log.w(TAG, "Could not save progress: ${it.message}") }
        }
    }

    /**
     * Thins the recorded route before storing it. A four-thousand point trace is tens of
     * kilobytes of text per trip, and every trip list query carries it; a couple of
     * hundred points draws the same line on a phone-sized map.
     */
    private fun encodePath(raw: List<Pair<Double, Double>>): String {
        if (raw.isEmpty()) return ""
        val keep = if (raw.size <= STORED_POINTS) raw else {
            val step = raw.size.toDouble() / STORED_POINTS
            (0 until STORED_POINTS - 1).map { raw[(it * step).toInt().coerceAtMost(raw.lastIndex)] } + raw.last()
        }
        return keep.joinToString(";") { Geo.formatPoint(it.first, it.second) }
    }

    /**
     * A trip that starts inside your work hours is marked with the purpose you set
     * for those hours. Everything else lands unclassified for you to swipe.
     */
    private suspend fun defaultPurposeForNow(): Long? {
        if (!repo.prefs.scheduleEnabled) return null
        val now = LocalDateTime.now(ZoneId.systemDefault())
        val minute = now.hour * 60 + now.minute
        val day = now.dayOfWeek.value
        val inWindow = repo.schedule.enabledWindows().any {
            it.dayOfWeek == day && minute >= it.startMinute && minute <= it.endMinute
        }
        if (!inWindow) return null
        return repo.prefs.workHoursPurposeId.takeIf { it != 0L }
    }

    private fun hasLocationPermission() =
        ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun Location.toWatchFix() = DriveWatch.Fix(
        latitude = latitude,
        longitude = longitude,
        timeMillis = time,
        speedMps = if (hasSpeed()) speed.toDouble() else null,
        speedAccuracyMps = if (hasSpeedAccuracy()) speedAccuracyMetersPerSecond.toDouble() else null,
        accuracyMeters = if (hasAccuracy()) accuracy.toDouble() else null
    )

    // ---- notification -------------------------------------------------------------

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, TripTrackingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopWatching = PendingIntent.getService(
            this, 2, Intent(this, TripTrackingService::class.java).setAction(ACTION_STOP_WATCH),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = NotificationCompat.Builder(this, MileLogApp.CH_TRACKING)
            .setSmallIcon(R.drawable.ic_stat_trip)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        return if (recording) {
            val elapsed = if (startedAt > 0) Fmt.duration(System.currentTimeMillis() - startedAt) else "0m"
            builder
                .setContentTitle("Recording a drive")
                .setContentText("${Fmt.miles(miles)} mi  ·  $elapsed")
                .addAction(0, "Stop", stop)
                .build()
        } else {
            builder
                .setContentTitle("Watching for your next drive")
                .setContentText(watchNote ?: "GPS is off until you move.")
                .addAction(0, "Stop watching", stopWatching)
                .build()
        }
    }

    private fun promoteToForeground(): Boolean = try {
        startForeground(NOTIF_ID, buildNotification())
        true
    } catch (e: Exception) {
        Log.w(TAG, "Could not run in the foreground: ${e.javaClass.simpleName}: ${e.message}")
        false
    }

    private fun updateNotification() {
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
        }
    }

    /**
     * Says out loud that tracking has picked a drive up. The ongoing notification is
     * deliberately silent, so without this there is nothing to tell you it is working.
     */
    private fun announceDetected() {
        val open = PendingIntent.getActivity(
            this, 4,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, "trips"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, MileLogApp.CH_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_trip)
            .setContentTitle("MileLog is recording")
            .setContentText("Picked up that you are driving. Tap to watch it.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIF_DETECTED, notification)
        }
    }

    /** Tells the user why nothing got recorded, and takes them to the setting that fixes it. */
    private fun warnCannotTrack() {
        val needsBackground = !DriveDetect.hasBackgroundLocation(this)
        val settings = PendingIntent.getActivity(
            this, 3,
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null)
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = if (needsBackground) {
            "To record drives on its own, MileLog needs Location set to " +
                "\"Allow all the time\". Tap to open the setting."
        } else {
            "MileLog could not start recording. Open the app and press start."
        }
        val notification = NotificationCompat.Builder(this, MileLogApp.CH_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_trip)
            .setContentTitle("That drive was not recorded")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(settings)
            .setAutoCancel(true)
            .build()
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIF_WARN, notification)
        }
    }

    override fun onDestroy() {
        runCatching { locations?.removeUpdates(listener) }
        stopWatching()
        stopTimer?.cancel()
        idleTimer?.cancel()
        // The timers and any in-flight save would otherwise outlive the service and keep
        // a reference to it.
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.milelog.START"
        const val ACTION_STOP = "com.milelog.STOP"
        const val ACTION_DISCARD = "com.milelog.DISCARD"
        const val ACTION_ARM_STOP = "com.milelog.ARM_STOP"
        const val ACTION_CANCEL_STOP = "com.milelog.CANCEL_STOP"
        const val ACTION_WATCH = "com.milelog.WATCH"
        const val ACTION_STOP_WATCH = "com.milelog.STOP_WATCH"
        const val EXTRA_AUTO = "auto"

        private const val TAG = "MileLogTracking"
        private const val NOTIF_ID = 1001
        private const val NOTIF_WARN = 1002
        private const val NOTIF_DETECTED = 1003
        private const val MAX_POINTS = 4000
        /** How many route points survive into storage. */
        private const val STORED_POINTS = 200
        private const val PERSIST_EVERY_MS = 20_000L
        private const val MIN_MILES = 0.1

        private const val ACTIVE_INTERVAL_MS = 3000L
        private const val ACTIVE_MIN_INTERVAL_MS = 1500L
        private const val PASSIVE_INTERVAL_MS = 30_000L

        /** Drive detection saying the drive ended is given a shorter benefit of the doubt. */
        private const val STOP_GRACE_MS = 90 * 1000L
        /** Hang about this long after a leg before letting go of everything. */
        private const val IDLE_SHUTDOWN_MS = 20 * 60 * 1000L
        /** Far enough from where we parked to count as setting off again. */
        private const val RESUME_METERS = 80f

        /** How often the watching loop wakes up to consider taking a reading. */
        private const val TICK_MS = 2 * 60 * 1000L
        /** Never two readings closer together than this, whatever asks for them. */
        private const val MIN_CHECK_GAP_MS = 100 * 1000L
        /** A reading this often even if the phone has not stirred, in case the sensor has not. */
        private const val RESTING_CHECK_MS = 30 * 60 * 1000L
        /** On a phone with no movement sensor, the loop is all there is. */
        private const val NO_SENSOR_CHECK_MS = 5 * 60 * 1000L
        /** A fix this recent is worth using as it stands rather than paying for another. */
        private const val CACHED_FIX_MAX_AGE_MS = 60 * 1000L
        /** How long to leave GPS on waiting for one fix of our own. */
        private const val FIX_TIMEOUT_MS = 30 * 1000L

        fun start(context: Context, auto: Boolean = false) {
            val intent = Intent(context, TripTrackingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_AUTO, auto)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, TripTrackingService::class.java).setAction(ACTION_STOP)
            )
        }

        /** Asks the service to keep the watch itself, on phones where Google cannot. */
        fun watch(context: Context) {
            runCatching {
                context.startForegroundService(
                    Intent(context, TripTrackingService::class.java).setAction(ACTION_WATCH)
                )
            }.onFailure { Log.w(TAG, "Could not start the watch: ${it.message}") }
        }

        /** Stands the watch down. Does nothing if nobody is watching, rather than starting one. */
        fun stopWatch(context: Context) {
            if (!TripTracker.state.value.watching) return
            runCatching {
                context.startService(
                    Intent(context, TripTrackingService::class.java).setAction(ACTION_STOP_WATCH)
                )
            }
        }

        fun send(context: Context, action: String) {
            if (!TripTracker.state.value.active) return
            context.startService(Intent(context, TripTrackingService::class.java).setAction(action))
        }
    }
}
