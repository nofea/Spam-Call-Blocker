package com.sheen.spamblocker.ui.history

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.sheen.spamblocker.data.CallEvent
import com.sheen.spamblocker.data.CallEventDao
import com.sheen.spamblocker.data.ListSourceDao
import com.sheen.spamblocker.data.NumberStatus
import com.sheen.spamblocker.data.RulesRepository
import com.sheen.spamblocker.rules.Action
import com.sheen.spamblocker.screening.Decision
import com.sheen.spamblocker.ui.common.EmptyState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HistoryState(
    val filter: Decision? = null,
    val events: List<CallEvent> = emptyList(),
    val sourceNames: Map<Long, String> = emptyMap(),
    val status: Map<String, NumberStatus> = emptyMap(),
)

@HiltViewModel
class HistoryViewModel @Inject constructor(
    callEvents: CallEventDao,
    sources: ListSourceDao,
    private val rules: RulesRepository,
) : ViewModel() {
    private val filter = MutableStateFlow<Decision?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<HistoryState> = combine(
        filter,
        filter.flatMapLatest { callEvents.observeAll(it) },
        sources.observeAll().map { list -> list.associate { it.id to it.name } },
        rules.observeNumberStatus,
        ::HistoryState,
    ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryState())

    fun setFilter(decision: Decision?) {
        filter.value = decision
    }

    fun block(number: String, action: Action) = viewModelScope.launch { rules.blockNumber(number, action) }

    fun notSpam(number: String) = viewModelScope.launch { rules.markNotSpam(number) }
}

@Composable
fun HistoryScreen(vm: HistoryViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    Column {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(selected = state.filter == null, onClick = { vm.setFilter(null) }, label = { Text("All") })
            Decision.entries.forEach { d ->
                FilterChip(
                    selected = state.filter == d,
                    onClick = { vm.setFilter(d) },
                    label = { Text(d.name.lowercase().replaceFirstChar(Char::uppercase)) },
                )
            }
        }
        if (state.events.isEmpty()) {
            EmptyState("No screened calls yet.")
        } else {
            LazyColumn {
                items(state.events, key = { it.id }) { event ->
                    EventRow(
                        event,
                        event.sourceId?.let(state.sourceNames::get),
                        event.number?.let(state.status::get),
                        vm::block,
                        vm::notSpam,
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
