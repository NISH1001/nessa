package dev.nish.nessa.ui.litter

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.nish.nessa.data.LitterRepository
import dev.nish.nessa.data.LitterState
import dev.nish.nessa.whisker.WhiskerSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

class LitterViewModel(private val repo: LitterRepository, session: WhiskerSession) : ViewModel() {
    val state: StateFlow<LitterState> = repo.state

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing

    private val _cleaning = MutableStateFlow(false)
    val cleaning: StateFlow<Boolean> = _cleaning

    /** One-line result of the last "Clean now", shown briefly. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    init {
        // Signing in or out anywhere in the app should update the litter box right away.
        viewModelScope.launch { session.status.drop(1).collect { repo.refresh() } }
    }

    fun refresh(showSpinner: Boolean = false): Job = viewModelScope.launch {
        if (showSpinner) _refreshing.value = true
        try {
            repo.refresh()
        } finally {
            _refreshing.value = false
        }
    }

    fun cleanNow(): Job = viewModelScope.launch {
        _cleaning.value = true
        try {
            _message.value = repo.cleanNow() ?: "Cleaning started"
        } finally {
            _cleaning.value = false
        }
    }

    fun clearMessage() { _message.value = null }
}
