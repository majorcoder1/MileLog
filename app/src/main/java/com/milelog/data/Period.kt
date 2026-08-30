package com.milelog.data

import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

enum class Period(val label: String) {
    TODAY("Today"),
    THIS_WEEK("This week"),
    THIS_MONTH("This month"),
    THIS_YEAR("This year"),
    LAST_WEEK("Last week"),
    LAST_MONTH("Last month"),
    LAST_YEAR("Last year"),
    CUSTOM("Custom")
}

/** An inclusive span of days, plus the epoch-millis window that matches it. */
data class DayRange(val fromDay: Long, val toDay: Long) {
    val fromMillis: Long
        get() = LocalDate.ofEpochDay(fromDay).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    val toMillis: Long
        get() = LocalDate.ofEpochDay(toDay).plusDays(1).atStartOfDay(ZoneId.systemDefault())
            .toInstant().toEpochMilli() - 1

    companion object {
        fun of(from: LocalDate, to: LocalDate) = DayRange(from.toEpochDay(), to.toEpochDay())

        fun forYear(year: Int) = of(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31))

        fun forPeriod(
            period: Period,
            today: LocalDate = LocalDate.now(),
            customFrom: LocalDate? = null,
            customTo: LocalDate? = null
        ): DayRange = when (period) {
            Period.TODAY -> of(today, today)
            Period.THIS_WEEK -> {
                val start = today.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.SUNDAY))
                of(start, start.plusDays(6))
            }
            Period.THIS_MONTH -> of(today.withDayOfMonth(1), today.with(TemporalAdjusters.lastDayOfMonth()))
            Period.THIS_YEAR -> forYear(today.year)
            Period.LAST_WEEK -> {
                val start = today.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.SUNDAY)).minusWeeks(1)
                of(start, start.plusDays(6))
            }
            Period.LAST_MONTH -> {
                val m = today.minusMonths(1)
                of(m.withDayOfMonth(1), m.with(TemporalAdjusters.lastDayOfMonth()))
            }
            Period.LAST_YEAR -> forYear(today.year - 1)
            Period.CUSTOM -> of(customFrom ?: today, customTo ?: today)
        }
    }
}
