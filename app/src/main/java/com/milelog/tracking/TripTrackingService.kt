package com.milelog.tracking

import android.app.Notification
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
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Records one drive. Runs in the foreground because Android will not give a
 * background app a location stream.
 */
class TripTrackingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private lateinit var repo: Repo

    // Written on the main thread by onFix, read from IO when a trip is saved, so
    // every one of these needs to be visible across threads.
    @Volatile private var tripId: Long = 0
    @Volatile private var miles = 0.0
    @Volatile private var startedAt = 0L
    @Volatile private var autoStarted = false
    @Volatile private var last: Location? = null
    /** Guarded by itself. Never iterate it without holding the lock. */
    private val points = mutableListOf<Pair<Double, Double>>()
    @Volatile private var stopTimer: Job? = null
    @Volatile private var saving = false
    /** Set synchronously, unlike tripId, so a second START cannot slip past the guard. */
    @Volatile private var starting = false
    @Volatile private var lastPersistedAt = 0L

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { onFix(it) }
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
                start(intent.getBooleanExtra(EXTRA_AUTO, false))
            }
            ACTION_STOP -> finish(discard = false)
            ACTION_DISCARD -> finish(discard = true)
            ACTION_ARM_STOP -> armStop()
            ACTION_CANCEL_STOP -> { stopTimer?.cancel(); stopTimer = null }
            else -> {
                // A null action means the system recreated us after killing the process.
                // Opening a fresh trip here would never be promoted to the foreground, so
                // it would collect no location and leave an empty row behind. The drive so
                // far is already on disk; let the next drive-detect event start a real one.
                stopSelf()
                return START_NOT_STICKY
            }
        }
        // Deliberately not sticky: a resurrected service cannot promote itself, and a
        // half-alive tracker is worse than none.
        return START_NOT_STICKY
    }

    private fun start(auto: Boolean) {
        if (tripId != 0L || starting) return
        starting = true
        if (!hasLocationPermission()) {
            starting = false
            warnCannotTrack()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        startedAt = System.currentTimeMillis()
        autoStarted = auto
        miles = 0.0
        last = null
        points.clear()

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
            TripTracker.set(
                LiveTrip(active = true, tripId = id, startedAt = startedAt, autoStarted = auto)
            )
        }

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 4000L)
            .setMinUpdateIntervalMillis(2000L)
            .setMinUpdateDistanceMeters(8f)
            .setWaitForAccurateLocation(false)
            .build()
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            client.requestLocationUpdates(request, callback, mainLooper)
        }
    }

    private fun onFix(loc: Location) {
        // Throw away fixes too fuzzy or too fast to be real driving.
        if (loc.hasAccuracy() && loc.accuracy > 60f) return
        val prev = last
        if (prev != null) {
            val meters = prev.distanceTo(loc)
            val seconds = ((loc.time - prev.time) / 1000.0).coerceAtLeast(0.5)
            val mph = Geo.metersToMiles(meters.toDouble()) / (seconds / 3600.0)
            if (meters < 5f) return
            if (mph > 120) { last = loc; return }
            miles += Geo.metersToMiles(meters.toDouble())
        }
        last = loc
        val snapshot = synchronized(points) {
            if (points.size < MAX_POINTS) points += loc.latitude to loc.longitude
            points.toList()
        }
        TripTracker.update {
            it.copy(miles = miles, points = snapshot, lastFixAt = System.currentTimeMillis())
        }
        updateNotification()

        // Write progress to disk regularly. Without this the drive lives only in memory
        // and the whole thing is lost the moment Android reclaims the process.
        val now = System.currentTimeMillis()
        if (now - lastPersistedAt >= PERSIST_EVERY_MS) {
            lastPersistedAt = now
            persistProgress(snapshot)
        }
    }

    /** Saves how far we have got, so a killed process costs seconds rather than the drive. */
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
            (0 until STORED_POINTS).map { raw[(it * step).toInt().coerceAtMost(raw.lastIndex)] } + raw.last()
        }
        return keep.joinToString(";") { Geo.formatPoint(it.first, it.second) }
    }

    /**
     * Becomes a foreground service, or reports that it could not. Returns false when the
     * system rejects the promotion, which happens when a location service is started from
     * the background without ACCESS_BACKGROUND_LOCATION.
     */
    private fun promoteToForeground(): Boolean = try {
        startForeground(NOTIF_ID, buildNotification())
        true
    } catch (e: Exception) {
        Log.w(TAG, "Could not run in the foreground: ${e.javaClass.simpleName}: ${e.message}")
        false
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
            getSystemService(android.app.NotificationManager::class.java).notify(NOTIF_WARN, notification)
        }
    }

    /** Auto-detect said the drive ended. Wait it out in case it was a long red light. */
    private fun armStop() {
        stopTimer?.cancel()
        stopTimer = scope.launch {
            delay(STOP_GRACE_MS)
            finish(discard = false)
        }
    }

    private fun finish(discard: Boolean) {
        if (saving) return
        saving = true
        stopTimer?.cancel()
        runCatching { client.removeLocationUpdates(callback) }

        val id = tripId
        val endedAt = System.currentTimeMillis()
        val finalMiles = miles
        val snapshot = synchronized(points) { points.toList() }
        val path = encodePath(snapshot)
        val first = snapshot.firstOrNull()
        val lastPoint = snapshot.lastOrNull()

        scope.launch {
            if (id != 0L) {
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
            }
            repo.prefs.activeTripId = 0L
            TripTracker.clear()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
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
        val configured = repo.prefs.workHoursPurposeId
        return if (configured != 0L) configured else null
    }

    private fun hasLocationPermission() =
        ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, TripTrackingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val elapsed = if (startedAt > 0) Fmt.duration(System.currentTimeMillis() - startedAt) else "0m"
        return NotificationCompat.Builder(this, MileLogApp.CH_TRACKING)
            .setSmallIcon(R.drawable.ic_stat_trip)
            .setContentTitle("Recording a drive")
            .setContentText("${Fmt.miles(miles)} mi  ·  $elapsed")
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification() {
        runCatching {
            getSystemService(android.app.NotificationManager::class.java)
                .notify(NOTIF_ID, buildNotification())
        }
    }

    override fun onDestroy() {
        runCatching { client.removeLocationUpdates(callback) }
        stopTimer?.cancel()
        // The five-minute stop timer and any in-flight save would otherwise outlive the
        // service and keep a reference to it.
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
        private const val MAX_POINTS = 4000
        /** How many route points survive into storage. */
        private const val STORED_POINTS = 200
        private const val PERSIST_EVERY_MS = 20_000L
        private const val MIN_MILES = 0.1
        private const val STOP_GRACE_MS = 5 * 60 * 1000L

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
