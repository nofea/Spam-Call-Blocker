package com.sheen.spamblocker.ui.calllog

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.sheen.spamblocker.calllog.CallLogItem
import com.sheen.spamblocker.calllog.CallLogReader
import com.sheen.spamblocker.data.CallEvent
import com.sheen.spamblocker.data.CallEventDao
import com.sheen.spamblocker.data.NumberStatus
import com.sheen.spamblocker.data.RulesRepository
import com.sheen.spamblocker.rules.Action
import com.sheen.spamblocker.ui.common.ActionRow
import com.sheen.spamblocker.ui.common.BlockDialog
import com.sheen.spamblocker.ui.common.DecisionBadge
import com.sheen.spamblocker.ui.common.EmptyState
import com.sheen.spamblocker.ui.common.label
import com.sheen.spamblocker.ui.common.relativeTime
import com.sheen.spamblocker.ui.common.rememberAccessState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.math.abs

data class CallLogRow(val call: CallLogItem, val event: CallEvent?, val status: NumberStatus?)

@HiltViewModel
class CallLogViewModel @Inject constructor(
    private val reader: CallLogReader,
    callEvents: CallEventDao,
    private val rules: RulesRepository,
) : ViewModel() {
    private val calls = MutableStateFlow<List<CallLogItem>?>(null)

    /** null while loading. Each call carries the app's decision for it, if it screened it. */
    val rows: StateFlow<List<CallLogRow>?> = combine(
        calls,
        callEvents.observeSince(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(90)),
        rules.observeNumberStatus,
    ) { calls, events, status ->
        val byNumber = events.filter { it.number != null }.groupBy { it.number }
        calls?.map { call ->
            val event = byNumber[call.e164]?.minByOrNull { abs(it.timestamp - call.dateMillis) }
                ?.takeIf { abs(it.timestamp - call.dateMillis) <= MATCH_WINDOW_MILLIS }
            CallLogRow(call, event, call.e164?.let(status::get))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun reload() = viewModelScope.launch { calls.value = reader.recentIncoming() }

    fun block(number: String, action: Action) = viewModelScope.launch { rules.blockNumber(number, action) }

    fun notSpam(number: String) = viewModelScope.launch { rules.markNotSpam(number) }

    private companion object {
        const val MATCH_WINDOW_MILLIS = 2 * 60 * 1000L
    }
}

/** SPEC §7 / §11: the system call log with the app's decisions and Block / Not spam. */
@Composable
fun CallLogScreen(vm: CallLogViewModel = hiltViewModel()) {
    val access = rememberAccessState()
    val rows by vm.rows.collectAsStateWithLifecycle()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.reload() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.reload() }

    if (!access.callLog) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("The call log needs permission to read your call history.", style = MaterialTheme.typography.bodyLarge)
            Button(onClick = { permission.launch(Manifest.permission.READ_CALL_LOG) }) { Text("Allow") }
        }
        return
    }
    val list = rows ?: return
    if (list.isEmpty()) {
        EmptyState("No incoming calls in your call log.")
        return
    }
    LazyColumn {
        items(list, key = { it.call.id }) { row ->
            CallRow(row, vm::block, vm::notSpam)
            HorizontalDivider()
        }
    }
}

@Composable
private fun CallRow(row: CallLogRow, onBlock: (String, Action) -> Unit, onNotSpam: (String) -> Unit) {
    var blocking by remember { mutableStateOf(false) }
    val number = row.call.e164
    Column {
        ListItem(
            headlineContent = { Text(row.call.cachedName ?: number ?: row.call.rawNumber?.ifEmpty { null } ?: "Hidden number") },
            supportingContent = {
                val detail = listOfNotNull(
                    number.takeIf { row.call.cachedName != null },
                    row.call.typeLabel,
                    row.event?.stage?.label,
                    relativeTime(row.call.dateMillis),
                )
                Text(listOfNotNull(detail.joinToString(" · "), row.status?.label).joinToString("\n"))
            },
            trailingContent = { row.event?.let { Row { DecisionBadge(it.decision) } } },
        )
        if (number != null) {
            ActionRow {
                TextButton(onClick = { onNotSpam(number) }) { Text("Not spam") }
                TextButton(onClick = { blocking = true }) { Text("Block") }
            }
        }
    }
    if (blocking && number != null) {
        BlockDialog(number, (row.status as? NumberStatus.Blocked)?.action ?: Action.REJECT, onBlock = { onBlock(number, it); blocking = false }, onDismiss = { blocking = false })
    }
}
