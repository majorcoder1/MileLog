package com.milelog.data

import android.content.Context
import androidx.core.content.edit

/** Small settings that do not need their own table. */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("milelog", Context.MODE_PRIVATE)

    var autoDetect: Boolean
        get() = sp.getBoolean(K_AUTO_DETECT, false)
        set(v) = sp.edit { putBoolean(K_AUTO_DETECT, v) }

    var scheduleEnabled: Boolean
        get() = sp.getBoolean(K_SCHEDULE, false)
        set(v) = sp.edit { putBoolean(K_SCHEDULE, v) }

    /** Trips inside your work hours start out marked with this purpose. */
    var workHoursPurposeId: Long
        get() = sp.getLong(K_WORK_PURPOSE, 0L)
        set(v) = sp.edit { putLong(K_WORK_PURPOSE, v) }

    var defaultVehicleId: Long
        get() = sp.getLong(K_VEHICLE, 0L)
        set(v) = sp.edit { putLong(K_VEHICLE, v) }

    var exportEmail: String
        get() = sp.getString(K_EMAIL, "") ?: ""
        set(v) = sp.edit { putString(K_EMAIL, v) }

    var dailyBackup: Boolean
        get() = sp.getBoolean(K_DAILY_BACKUP, true)
        set(v) = sp.edit { putBoolean(K_DAILY_BACKUP, v) }

    var lastBackupEpoch: Long
        get() = sp.getLong(K_LAST_BACKUP, 0L)
        set(v) = sp.edit { putLong(K_LAST_BACKUP, v) }

    /** Set while a trip is being recorded so the service can be resumed after a crash. */
    var activeTripId: Long
        get() = sp.getLong(K_ACTIVE_TRIP, 0L)
        set(v) = sp.edit { putLong(K_ACTIVE_TRIP, v) }

    var yearEndNotifiedFor: Int
        get() = sp.getInt(K_YEAR_END, 0)
        set(v) = sp.edit { putInt(K_YEAR_END, v) }

    var seenOnboarding: Boolean
        get() = sp.getBoolean(K_ONBOARD, false)
        set(v) = sp.edit { putBoolean(K_ONBOARD, v) }

    private companion object {
        const val K_AUTO_DETECT = "auto_detect"
        const val K_SCHEDULE = "schedule_enabled"
        const val K_WORK_PURPOSE = "work_purpose"
        const val K_VEHICLE = "default_vehicle"
        const val K_EMAIL = "export_email"
        const val K_DAILY_BACKUP = "daily_backup"
        const val K_LAST_BACKUP = "last_backup"
        const val K_ACTIVE_TRIP = "active_trip"
        const val K_YEAR_END = "year_end_notified"
        const val K_ONBOARD = "seen_onboarding"
    }
}
