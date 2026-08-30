package com.milelog.tracking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import com.milelog.data.Prefs

/** Starts a trip when the phone says you are driving, ends it when you are not. */
class DriveDetectReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityTransitionResult.hasResult(intent)) return
        if (!Prefs(context).autoDetect) return
        val result = ActivityTransitionResult.extractResult(intent) ?: return

        for (event in result.transitionEvents) {
            if (event.activityType != DetectedActivity.IN_VEHICLE) continue
            when (event.transitionType) {
                ActivityTransition.ACTIVITY_TRANSITION_ENTER -> {
                    if (TripTracker.state.value.active) {
                        TripTrackingService.send(context, TripTrackingService.ACTION_CANCEL_STOP)
                    } else {
                        TripTrackingService.start(context, auto = true)
                    }
                }
                ActivityTransition.ACTIVITY_TRANSITION_EXIT -> {
                    TripTrackingService.send(context, TripTrackingService.ACTION_ARM_STOP)
                }
            }
        }
    }

    companion object {
        const val ACTION = "com.milelog.DRIVE_TRANSITION"
    }
}
