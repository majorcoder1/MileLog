package com.milelog.tracking

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
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
import java.time.LocalDateTime
import java.time.ZoneId

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
 */
class TripTrackingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client by lazy { LocationServices.getFusedLocationProviderClient(this) }
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
    @Volatile private var lastMovedAt = 0L
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

    private val recording: Boolean get() = tripId != 0L

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            // Fused location batches fixes when the screen is off. Taking only the newest
            // one threw the rest of the batch away and measured the whole leg as a single
            // straight line between two distant points.
            result.locations.sortedBy { it.time }.forEach { onFix(it) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        repo = Repo.get(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                // Android can refuse this outright: a location service started from the
                // background needs "Allow all the time". Refusal must not kill the app.
                if (!promoteToForeground()) {
                    warnCannotTrack()
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (!recording) startLeg(intent.getBooleanExtra(EXTRA_AUTO, false))
            }
            ACTION_STOP -> {
                endLeg(discard = false)
                shutDown()
            }
            ACTION_DISCARD -> {
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
        startedAt = System.currentTimeMillis()
        lastMovedAt = startedAt
        autoStarted = auto
        miles = 0.0
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
     * alive briefly so a quick turnaround does not have to pay for a cold start.
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

        requestUpdates(active = false)
        updateNotification()
        armIdleShutdown()
    }

    /** Nothing more expected for a while; let go of everything. */
    private fun shutDown() {
        if (saving) return
        saving = true
        stopTimer?.cancel()
        idleTimer?.cancel()
        runCatching { client.removeLocationUpdates(callback) }
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
            // Drive detection will start us again when it matters.
            Log.i(TAG, "Idle with no movement; standing down until the next drive")
            shutDown()
        }
    }

    /** Auto-detect said the drive ended. Wait it out in case it was a long red light. */
    private fun armStop() {
        if (!recording) return
        stopTimer?.cancel()
        stopTimer = scope.launch {
            delay(STOP_GRACE_MS)
            endLeg(discard = false)
        }
    }

    // ---- location -----------------------------------------------------------------

    private fun requestUpdates(active: Boolean) {
        if (!hasLocationPermission()) return
        runCatching { client.removeLocationUpdates(callback) }

        val request = if (active) {
            LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, ACTIVE_INTERVAL_MS)
                .setMinUpdateIntervalMillis(ACTIVE_MIN_INTERVAL_MS)
                // No distance filter: a fix every few seconds follows a curve, where one
                // every eight metres of displacement cuts the corners off it.
                .setMinUpdateDistanceMeters(0f)
                .setMaxUpdateDelayMillis(0L)
                .setWaitForAccurateLocation(false)
                .build()
        } else {
            // Passive costs nothing: it only ever hands us a fix some other app already
            // paid for. Between legs this is the whole of our location use.
            LocationRequest.Builder(Priority.PRIORITY_PASSIVE, PASSIVE_INTERVAL_MS)
                .setMinUpdateDistanceMeters(RESUME_METERS)
                .build()
        }
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            client.requestLocationUpdates(request, callback, mainLooper)
        }
    }

    private fun onFix(loc: Location) {
        // Throw away fixes too fuzzy to be real driving.
        if (loc.hasAccuracy() && loc.accuracy > 60f) return

        if (!recording) {
            // Watching. A free passive fix showing we have left where we parked is the
            // cue to open the next leg.
            val parked = parkedAt
            if (parked == null || parked.distanceTo(loc) > RESUME_METERS) {
                Log.i(TAG, "Movement seen while watching; opening the next leg")
                startLeg(auto = true)
            }
            return
        }

        val prev = last
        if (prev != null) {
            val meters = prev.distanceTo(loc)
            val seconds = ((loc.time - prev.time) / 1000.0).coerceAtLeast(0.5)
            val mph = Geo.metersToMiles(meters.toDouble()) / (seconds / 3600.0)

            // Standing still. A parked phone's fix wanders several metres a minute, and
            // at a red light that wander used to be added as real distance. The
            // receiver's own Doppler speed is far more trustworthy than differencing
            // two positions.
            val speedMph = if (loc.hasSpeed()) loc.speed * MPH_PER_MPS else mph
            if (speedMph < STOPPED_MPH && meters < NOISE_METERS) {
                if (System.currentTimeMillis() - lastMovedAt >= STOP_SPLIT_MS) {
                    Log.i(TAG, "Stopped for ${STOP_SPLIT_MS / 60000} minutes; closing the leg")
                    endLeg(discard = false)
                }
                return
            }

            if (meters < MIN_SEGMENT_METERS) return
            if (seconds >= 1.0 && mph > MAX_PLAUSIBLE_MPH) {
                // Either a bad fix or a gap in the stream we cannot honestly measure.
                // Re-anchor, but keep count: this is distance leaving the total.
                droppedLegs++
                droppedMiles += Geo.metersToMiles(meters.toDouble())
                last = loc
                return
            }
            fixesUsed++
            miles += Geo.metersToMiles(meters.toDouble())
        }

        last = loc
        lastMovedAt = System.currentTimeMillis()
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
                .setContentText("GPS is off until you move.")
                .addAction(0, "Stop watching", stop)
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
        runCatching { client.removeLocationUpdates(callback) }
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

        /** Still for this long and the leg is closed, the way a delivery day really goes. */
        private const val STOP_SPLIT_MS = 2 * 60 * 1000L
        /** Drive detection saying the drive ended is given a shorter benefit of the doubt. */
        private const val STOP_GRACE_MS = 90 * 1000L
        /** Hang about this long after a leg before letting go of everything. */
        private const val IDLE_SHUTDOWN_MS = 20 * 60 * 1000L
        /** Far enough from where we parked to count as setting off again. */
        private const val RESUME_METERS = 80f

        private const val MPH_PER_MPS = 2.236936
        /** Below this the receiver is reporting a vehicle that is not moving. */
        private const val STOPPED_MPH = 2.0
        /** How far a fix must jump before it counts as movement rather than GPS wander. */
        private const val NOISE_METERS = 30f
        private const val MIN_SEGMENT_METERS = 5f
        private const val MAX_PLAUSIBLE_MPH = 120.0

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

        fun send(context: Context, action: String) {
            if (!TripTracker.state.value.active) return
            context.startService(Intent(context, TripTrackingService::class.java).setAction(action))
        }
    }
}
