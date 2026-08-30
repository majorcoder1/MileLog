package com.milelog.tracking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.milelog.data.Prefs
import com.milelog.work.Jobs

/** Puts detection and the background jobs back after a restart. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = Prefs(context)
        if (prefs.autoDetect) DriveDetect.enable(context)
        Jobs.scheduleAll(context)
    }
}
