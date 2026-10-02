package com.milelog.tracking

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.DetectedActivity

/** Google's motion service. Only the "play" build contains this; see the "foss" twin. */
object GoogleMotion {

    const val AVAILABLE = true

    /** A sentence for the user if Play services cannot be used at all, else null. */
    fun unavailableReason(context: Context): String? {
        val available = runCatching {
            GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
        }.getOrDefault(ConnectionResult.SERVICE_MISSING)
        return if (available != ConnectionResult.SUCCESS) {
            "Google Play services is not available on this phone."
        } else null
    }

    fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, 2001,
            android.content.Intent(context, DriveDetectReceiver::class.java)
                .setAction(DriveDetectReceiver.ACTION),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    @SuppressLint("MissingPermission")
    fun start(context: Context): Boolean {
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
    fun stop(context: Context) {
        runCatching {
            ActivityRecognition.getClient(context)
                .removeActivityTransitionUpdates(pendingIntent(context))
        }
    }
}
