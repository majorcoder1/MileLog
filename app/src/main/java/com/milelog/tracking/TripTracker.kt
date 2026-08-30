package com.milelog.tracking

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the UI needs to know about the drive being recorded right now. */
data class LiveTrip(
    val active: Boolean = false,
    val tripId: Long = 0,
    val miles: Double = 0.0,
    val startedAt: Long = 0,
    val points: List<Pair<Double, Double>> = emptyList(),
    val autoStarted: Boolean = false,
    val lastFixAt: Long = 0
)

/** Single source of truth the service writes and the screens read. */
object TripTracker {
    private val _state = MutableStateFlow(LiveTrip())
    val state: StateFlow<LiveTrip> = _state

    fun set(value: LiveTrip) { _state.value = value }
    fun update(block: (LiveTrip) -> LiveTrip) { _state.value = block(_state.value) }
    fun clear() { _state.value = LiveTrip() }
}
