package com.milelog.tracking

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.ActivityCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.DetectedActivity
import com.milelog.data.Prefs

/**
 * Automatic drive detection, in either of two ways.
 *
 * Google's motion service is the better one: it is built into the phone's own sensor
 * hub, so noticing that you have pulled out of the driveway costs nothing and MileLog
 * never has to touch GPS to find out. It is the default.
 *
 * It also stops working the moment Google Play services is sandboxed or absent, which is
 * exactly what happened when this phone moved to GrapheneOS — the transitions simply
 * never arrived and days of driving went unrecorded. So there is a second way that
 * involves no Google at all: MileLog keeps its own low-power watch on the phone's GPS.
 * See TripTrackingService's watching state and DriveWatch.
 *
 * Which one is in use is [mode]: the user's choice, overruled only when their choice
 * cannot work on this phone.
 */
object DriveDetect {

    enum class Mode { GOOGLE, PHONE }

    private const val TAG = "MileLogDetect"
    private const val PLAY_SERVICES = "com.google.android.gms"

    // ---- which way ------------------------------------------------------------------

    fun mode(context: Context): Mode =
        if (Prefs(context).useGoogleDetect && googleProblem(context) == null) Mode.GOOGLE else Mode.PHONE

    /**
     * Why Google's motion service cannot be used on this phone, in a sentence fit to show
     * the user — or null when it can.
     *
     * The permission check is of Play services itself, not of MileLog. A sandboxed Play
     * services is an ordinary app with ordinary permissions, and with location and
     * physical activity denied it reports nothing while still answering every call
     * successfully, which is the failure that loses a day of driving without a word.
     */
    fun googleProblem(context: Context): String? {
        val available = runCatching {
            GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
        }.getOrDefault(ConnectionResult.SERVICE_MISSING)
        if (available != ConnectionResult.SUCCESS) {
            return "Google Play services is not available on this phone."
        }
        if (!hasPermission(context)) {
            return "MileLog has no physical-activity permission yet."
        }
        if (!playServicesCanDetect(context)) {
            return "Google Play services has no location or physical-activity permission " +
                "on this phone, so it never reports that a drive has started."
        }
        return null
    }

    private fun playServicesCanDetect(context: Context): Boolean {
        val pm = context.packageManager
        fun granted(permission: String) =
            runCatching { pm.checkPermission(permission, PLAY_SERVICES) }
                .getOrDefault(PackageManager.PERMISSION_DENIED) == PackageManager.PERMISSION_GRANTED

        return granted(Manifest.permission.ACTIVITY_RECOGNITION) &&
            (granted(Manifest.permission.ACCESS_FINE_LOCATION) ||
                granted(Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    // ---- turning it on and off -------------------------------------------------------

    /**
     * Puts the phone into whatever state the settings ask for. Returns whether anything
     * is now listening for the next drive.
     */
    fun apply(context: Context): Boolean {
        if (!Prefs(context).autoDetect) {
            disable(context)
            return false
        }
        return enable(context)
    }

    /** Turns detection on the best way this phone can manage. False means nothing is listening. */
    fun enable(context: Context): Boolean {
        val mode = mode(context)
        Log.i(TAG, "Detection on, using ${if (mode == Mode.GOOGLE) "Google" else "the phone's own GPS"}")
        if (mode == Mode.GOOGLE) {
            TripTrackingService.stopWatch(context)
            if (startGoogle(context)) return true
            // Google said yes to everything and then did nothing, or refused outright.
            // Rather than leave the switch on and record nothing, fall back.
            Log.w(TAG, "Google would not take the request; watching the phone's GPS instead")
        } else {
            stopGoogle(context)
        }
        return startPhoneWatch(context)
    }

    fun disable(context: Context) {
        stopGoogle(context)
        TripTrackingService.stopWatch(context)
    }

    private fun startPhoneWatch(context: Context): Boolean {
        if (!hasLocation(context)) return false
        TripTrackingService.watch(context)
        return true
    }

    // ---- Google's motion service ----------------------------------------------------

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, 2001,
            Intent(context, DriveDetectReceiver::class.java).setAction(DriveDetectReceiver.ACTION),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    @SuppressLint("MissingPermission")
    private fun startGoogle(context: Context): Boolean {
        if (!hasPermission(context)) return false
        val transitions = listOf(
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.IN_VEHICLE)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build(),
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.IN_VEHICLE)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT)
                .build()
        )
        return runCatching {
            ActivityRecognition.getClient(context)
                .requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), pendingIntent(context))
        }.isSuccess
    }

    @SuppressLint("MissingPermission")
    private fun stopGoogle(context: Context) {
        if (!hasPermission(context)) return
        runCatching {
            ActivityRecognition.getClient(context)
                .removeActivityTransitionUpdates(pendingIntent(context))
        }
    }

    // ---- permissions ----------------------------------------------------------------

    /**
     * Recording a drive with the app closed needs "Allow all the time". Without it the
     * system refuses to let the location service run in the background.
     */
    fun hasBackgroundLocation(context: Context): Boolean =
        ActivityCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    /** MileLog's own physical-activity permission, which only Google's way needs. */
    fun hasPermission(context: Context): Boolean =
        ActivityCompat.checkSelfPermission(
            context, Manifest.permission.ACTIVITY_RECOGNITION
        ) == PackageManager.PERMISSION_GRANTED

    fun hasLocation(context: Context): Boolean =
        ActivityCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
}
