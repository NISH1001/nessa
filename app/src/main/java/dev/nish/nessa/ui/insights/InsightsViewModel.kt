package dev.nish.nessa.ui.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.nish.nessa.data.LitterRepository
import dev.nish.nessa.data.LitterState
import dev.nish.nessa.history.HistoryStore
import dev.nish.nessa.insights.InsightsModel
import dev.nish.nessa.insights.Range
import dev.nish.nessa.insights.buildInsights
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.ZoneId

/** Recomputes the insights whenever the history, the range, the robot or the time of day changes. */
class InsightsViewModel(store: HistoryStore, litter: LitterRepository) : ViewModel() {
    private val _range = MutableStateFlow(Range.Month)
    val range: StateFlow<Range> = _range

    fun setRange(r: Range) { _range.value = r }

    /** Ticks every few minutes so "today" rolls over and "last 24 h" stays true. */
    private val clock = flow {
        while (true) {
            emit(Instant.now())
            delay(5 * 60_000L)
        }
    }

    val model: StateFlow<InsightsModel?> = combine(store.activitiesFlow(), store.readingsFlow(), _range, litter.state, clock) { acts, readings, range, state, now ->
        val robot = (state as? LitterState.Ready)?.robot
        val zone = robot?.let(LitterRepository::zoneOf) ?: ZoneId.systemDefault()
        val filterDue = LitterRepository.parseInstant(robot?.nextFilterReplacementDate)?.atZone(zone)?.toLocalDate()
        buildInsights(acts, readings, range, zone, now, filterDue)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
