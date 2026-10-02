package com.milelog.tracking

import android.content.Context

/** The F-Droid build has no Google code; drives are found by watching the phone's own GPS. */
object GoogleMotion {

    const val AVAILABLE = false

    fun unavailableReason(context: Context): String? =
        "This build of MileLog does not include Google Play services."

    fun start(context: Context): Boolean = false

    fun stop(context: Context) = Unit
}
