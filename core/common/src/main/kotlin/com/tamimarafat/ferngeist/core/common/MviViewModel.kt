package com.tamimarafat.ferngeist.core.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

abstract class MviViewModel<State, Intent, Effect>(
    initialState: State,
) : ViewModel() {
    private val _state = MutableStateFlow(initialState)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _effects = Channel<Effect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    fun dispatch(intent: Intent) {
        viewModelScope.launch { handleIntent(intent) }
    }

    protected abstract suspend fun handleIntent(intent: Intent)

    protected fun updateState(reducer: State.() -> State) {
        _state.update(reducer)
    }

    protected suspend fun emitEffect(effect: Effect) {
        _effects.send(effect)
    }
}
