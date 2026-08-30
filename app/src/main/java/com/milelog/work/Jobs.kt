package com.milelog.work

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.milelog.MainActivity
import com.milelog.MileLogApp
import com.milelog.R
import com.milelog.data.DayRange
import com.milelog.data.Fmt
import com.milelog.data.Repo
import com.milelog.export.Backup
import com.milelog.tracking.DriveDetect
import com.milelog.tracking.TripTracker
import com.milelog.tracking.TripTrackingService
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

object Jobs {
    private const val BACKUP = "milelog-daily-backup"
    private const val DAILY_CHECK = "milelog-daily-check"
    private const val SCHEDULE = "milelog-schedule"

    fun scheduleAll(context: Context) {
        val wm = WorkManager.getInstance(context)

        wm.enqueueUniquePeriodicWork(
            BACKUP,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<DailyBackupWorker>(1, TimeUnit.DAYS).build()
        )

        wm.enqueueUniquePeriodicWork(
            DAILY_CHECK,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<DailyCheckWorker>(12, TimeUnit.HOURS).build()
        )

        wm.enqueueUniquePeriodicWork(
            SCHEDULE,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ScheduleWorker>(15, TimeUnit.MINUTES).build()
        )
    }

    fun notify(context: Context, id: Int, title: String, text: String, tab: String? = null) {
        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (tab != null) putExtra(MainActivity.EXTRA_TAB, tab)
        }
        val pending = PendingIntent.getActivity(
            context, id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(context, MileLogApp.CH_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_trip)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(id, n) }
    }
}

/** Writes a backup file once a day and keeps the last 30. */
class DailyBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repo = Repo.get(applicationContext)
        if (!repo.prefs.dailyBackup) return Result.success()
        return runCatching { Backup.create(applicationContext) }
            .fold({ Result.success() }, { Result.retry() })
    }
}

/**
 * Runs a couple of times a day. In January it tells you last year's totals, and it
 * watches the service reminders and upcoming shifts.
 */
class DailyCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repo = Repo.get(applicationContext)
        val today = LocalDate.now()

        if (today.monthValue == 1 && repo.prefs.yearEndNotifiedFor != today.year) {
            val lastYear = today.year - 1
            val summary = repo.summarize(DayRange.forYear(lastYear))
            if (summary.totalMiles > 0 || summary.tripCount > 0) {
                Jobs.notify(
                    applicationContext,
                    NOTIF_YEAR_END,
                    "Your $lastYear miles are ready",
                    "You drove ${Fmt.miles(summary.totalMiles)} miles in $lastYear, " +
                        "${Fmt.miles(summary.businessMiles)} of them for work. " +
                        "That is ${Fmt.dollars(summary.deduction)} in mileage deduction. " +
                        "Tap to build the spreadsheet.",
                    tab = "taxes"
                )
                repo.prefs.yearEndNotifiedFor = today.year
            }
        }

        checkServiceReminders(repo)
        checkShifts(repo)
        return Result.success()
    }

    private suspend fun checkServiceReminders(repo: Repo) {
        val vehicles = repo.vehicles.allNow().associateBy { it.id }
        val todayDay = LocalDate.now().toEpochDay()
        repo.schedule.enabledReminders().forEach { r ->
            val vehicle = r.vehicleId?.let { vehicles[it] }
            val dueByMiles = r.intervalMiles != null && r.lastDoneOdometer != null && vehicle != null &&
                vehicle.odometer >= r.lastDoneOdometer + r.intervalMiles
            val dueByDate = r.intervalDays != null && r.lastDoneEpochDay != null &&
                todayDay >= r.lastDoneEpochDay + r.intervalDays
            if (dueByMiles || dueByDate) {
                Jobs.notify(
                    applicationContext,
                    NOTIF_SERVICE + r.id.toInt(),
                    r.title,
                    "${vehicle?.name ?: "Your vehicle"} is due. Log it as an expense when you get it done.",
                    tab = "transactions"
                )
            }
        }
    }

    private suspend fun checkShifts(repo: Repo) {
        val now = System.currentTimeMillis()
        repo.schedule.upcomingShifts(now).forEach { s ->
            val remindAt = s.startEpoch - s.remindMinutesBefore * 60_000L
            if (now in remindAt..s.startEpoch) {
                Jobs.notify(
                    applicationContext,
                    NOTIF_SHIFT + s.id.toInt(),
                    "Shift starts at ${Fmt.time(s.startEpoch)}",
                    s.notes.ifBlank { "Tap to start tracking." }
                )
            }
        }
    }

    private companion object {
        const val NOTIF_YEAR_END = 3001
        const val NOTIF_SERVICE = 4000
        const val NOTIF_SHIFT = 5000
    }
}

/**
 * Arms and disarms drive detection around the work hours you set.
 *
 * It deliberately does not start the tracking service itself. A background job has no
 * exemption from the foreground-service start rules, so that throws
 * ForegroundServiceStartNotAllowedException. Activity-recognition transitions do have an
 * exemption, so this worker only decides whether detection is listening, and the drive
 * itself is what starts the service.
 */
class ScheduleWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repo = Repo.get(applicationContext)
        if (!repo.prefs.scheduleEnabled) return Result.success()

        val now = LocalDateTime.now(ZoneId.systemDefault())
        val minute = now.hour * 60 + now.minute
        val day = now.dayOfWeek.value
        val inWindow = repo.schedule.enabledWindows().any {
            it.dayOfWeek == day && minute in it.startMinute..it.endMinute
        }

        if (inWindow) {
            DriveDetect.enable(applicationContext)
        } else if (!repo.prefs.autoDetect) {
            // Outside work hours, only stop listening if all-day detection is off.
            DriveDetect.disable(applicationContext)
            if (TripTracker.state.value.active && TripTracker.state.value.autoStarted) {
                TripTrackingService.stop(applicationContext)
            }
        }
        return Result.success()
    }
}
