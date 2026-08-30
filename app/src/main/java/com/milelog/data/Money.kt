package com.milelog.data

import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

object Fmt {
    private val money: NumberFormat = NumberFormat.getCurrencyInstance(Locale.US)
    private val dateShort = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", Locale.US)
    private val dateTiny = DateTimeFormatter.ofPattern("MMM d", Locale.US)
    private val timeShort = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

    fun cents(v: Long): String = money.format(v / 100.0)
    fun dollars(v: Double): String = money.format(v)
    fun miles(v: Double): String = String.format(Locale.US, "%,.1f", v)
    fun date(day: Long): String = LocalDate.ofEpochDay(day).format(dateShort)
    fun dateTiny(day: Long): String = LocalDate.ofEpochDay(day).format(dateTiny)

    fun time(epochMillis: Long): String =
        java.time.Instant.ofEpochMilli(epochMillis)
            .atZone(java.time.ZoneId.systemDefault()).toLocalTime().format(timeShort)

    fun dateOf(epochMillis: Long): String =
        java.time.Instant.ofEpochMilli(epochMillis)
            .atZone(java.time.ZoneId.systemDefault()).toLocalDate().format(dateShort)

    fun epochDayOf(epochMillis: Long): Long =
        java.time.Instant.ofEpochMilli(epochMillis)
            .atZone(java.time.ZoneId.systemDefault()).toLocalDate().toEpochDay()

    /** "1h 12m" */
    fun duration(millis: Long): String {
        val totalMin = millis / 60000
        val h = totalMin / 60
        val m = totalMin % 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }
}
