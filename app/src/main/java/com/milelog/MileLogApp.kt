package com.milelog

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.preference.PreferenceManager
import com.milelog.data.Repo
import com.milelog.tracking.DriveDetect
import com.milelog.work.Jobs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration

class MileLogApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createChannels()
        configureMaps()

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val repo = Repo.get(this@MileLogApp)
            repo.seed()
            closeOutAbandonedTrip(repo)
            if (repo.prefs.autoDetect) DriveDetect.enable(this@MileLogApp)
        }
        Jobs.scheduleAll(this)
    }

    /**
     * If the phone killed the app mid-drive, the trip row is still open. Close it out
     * so a zero-mile stub never shows up in the list.
     */
    private suspend fun closeOutAbandonedTrip(repo: Repo) {
        val id = repo.prefs.activeTripId
        if (id == 0L) return
        val trip = repo.trips.byId(id)
        if (trip != null) {
            if (trip.miles < 0.1) {
                repo.trips.delete(trip)
            } else {
                repo.trips.update(trip.copy(endEpoch = maxOf(trip.updatedAt, trip.endEpoch)))
            }
        }
        repo.prefs.activeTripId = 0L
    }

    /** osmdroid needs a user agent and a cache folder before it will fetch tiles. */
    private fun configureMaps() {
        val config = Configuration.getInstance()
        config.load(this, PreferenceManager.getDefaultSharedPreferences(this))
        config.userAgentValue = packageName
        config.osmdroidBasePath = filesDir.resolve("osmdroid").apply { mkdirs() }
        config.osmdroidTileCache = filesDir.resolve("osmdroid/tiles").apply { mkdirs() }
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_TRACKING, "Trip tracking", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows while a drive is being recorded."
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERTS, "Reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Year-end totals, shifts, and service reminders."
            }
        )
    }

    companion object {
        const val CH_TRACKING = "tracking"
        const val CH_ALERTS = "alerts"
    }
}
