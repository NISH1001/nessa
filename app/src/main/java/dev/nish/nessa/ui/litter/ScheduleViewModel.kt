package dev.nish.nessa.ui.litter

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.nish.nessa.schedule.ApplyResult
import dev.nish.nessa.schedule.LitterManager
import dev.nish.nessa.schedule.ManagerState
import dev.nish.nessa.schedule.ManagerStatus
import dev.nish.nessa.schedule.Override
import dev.nish.nessa.schedule.Scheduling
import dev.nish.nessa.schedule.WeekSchedule
import dev.nish.nessa.schedule.Window
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ScheduleViewModel(app: Application, private val manager: LitterManager, private val onRobotChanged: suspend () -> Unit) : AndroidViewModel(app) {
    val state: StateFlow<ManagerState> = manager.state
    val status: StateFlow<ManagerStatus> = manager.status

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    init {
        viewModelScope.launch { manager.load() }
    }

    fun setEnabled(on: Boolean) = act { manager.setEnabled(on) }
    fun setOverride(o: Override) = act { manager.setOverride(o) }
    fun check() = act { manager.apply() }
    fun sleepNow(hours: Int?) = act { manager.sleepNow(hours) }
    fun endSleepNow() = act { manager.endSleepNow() }
    fun powerOff() = act { manager.powerOff() }
    fun powerOn() = act { manager.powerOn() }

    fun saveWindow(dow: Int, window: Window) = editDay(dow) { day ->
        if (day.any { it.id == window.id }) day.map { if (it.id == window.id) window else it } else day + window
    }

    fun deleteWindow(dow: Int, id: String) = editDay(dow) { day -> day.filterNot { it.id == id } }

    fun copyDay(from: Int, to: Set<Int>) = act { manager.setSchedule(state.value.schedule.copyDay(from, to)) }

    private fun editDay(dow: Int, edit: (List<Window>) -> List<Window>) = act {
        val schedule: WeekSchedule = state.value.schedule
        manager.setSchedule(schedule.withDay(dow, edit(schedule.days[dow]).sortedBy { it.startMin }))
    }

    private fun act(block: suspend () -> ApplyResult) = viewModelScope.launch {
        _busy.value = true
        try {
            val result = block()
            Scheduling.sync(getApplication(), manager.state.value.needsChecks, manager.status.value.nextCheckAt)
            if (result == ApplyResult.Applied) onRobotChanged()
        } finally {
            _busy.value = false
        }
    }
}
