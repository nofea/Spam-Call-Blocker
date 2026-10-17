package com.sheen.spamblocker.ui.home

import android.app.role.RoleManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.sheen.spamblocker.BuildConfig
import com.sheen.spamblocker.data.CallEvent
import com.sheen.spamblocker.data.CallEventDao
import com.sheen.spamblocker.data.ListSourceDao
import com.sheen.spamblocker.screening.Decision
import com.sheen.spamblocker.ui.common.EmptyState
import com.sheen.spamblocker.ui.common.SectionHeader
import com.sheen.spamblocker.ui.common.rememberAccessState
import com.sheen.spamblocker.ui.history.EventRow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject

data class HomeState(
    val blockedToday: Int = 0,
    val blockedThisWeek: Int = 0,
    val recent: List<CallEvent> = emptyList(),
    val sourceNames: Map<Long, String> = emptyMap(),
    val p50Millis: Double? = null,
    val p95Millis: Double? = null,
    val samples: Int = 0,
)

@HiltViewModel
class HomeViewModel @Inject constructor(callEvents: CallEventDao, sources: ListSourceDao) : ViewModel() {
    private val zone = ZoneId.systemDefault()
    private val today = LocalDate.now(zone)
    private val startOfToday = today.atStartOfDay(zone).toInstant().toEpochMilli()
    private val startOfWeek = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        .atStartOfDay(zone).toInstant().toEpochMilli()

    val state: StateFlow<HomeState> = combine(
        callEvents.observeSince(startOfWeek),
        callEvents.observeRecent(RECENT_EVENTS),
        sources.observeAll().map { list -> list.associate { it.id to it.name } },
        // SPEC §10: decision latency over the retained history.
        callEvents.observeLatencies(0),
    ) { week, recent, names, latencies ->
        val blocked = week.filter { it.decision == Decision.REJECT || it.decision == Decision.SILENCE }
        val sorted = latencies.map { it.decisionMicros }.sorted()
        HomeState(
            blockedToday = blocked.count { it.timestamp >= startOfToday },
            blockedThisWeek = blocked.size,
            recent = recent,
            sourceNames = names,
            p50Millis = percentile(sorted, 0.50),
            p95Millis = percentile(sorted, 0.95),
            samples = sorted.size,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState())

    private fun percentile(sorted: List<Long>, p: Double): Double? =
        if (sorted.isEmpty()) null else sorted[((sorted.size - 1) * p).toInt()] / 1_000.0

    private companion object {
        const val RECENT_EVENTS = 10
    }
}

@Composable
fun HomeScreen(vm: HomeViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val access = rememberAccessState()
    val context = LocalContext.current
    val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}

    LazyColumn {
        item {
            if (!access.screeningRole) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Protection is off", style = MaterialTheme.typography.titleMedium)
                        Text("This app isn't your call screening app, so no calls are being screened.")
                        FilledTonalButton(onClick = {
                            val rm = context.getSystemService(RoleManager::class.java)
                            roleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
                        }) { Text("Fix") }
                    }
                }
            } else {
                Text(
                    "Protection is on",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        item {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard("Blocked today", state.blockedToday.toString(), Modifier.weight(1f))
                StatCard("This week", state.blockedThisWeek.toString(), Modifier.weight(1f))
            }
        }
        if (BuildConfig.DEBUG && state.p50Millis != null) {
            item {
                Text(
                    "Decision time: p50 %.1f ms · p95 %.1f ms (%d calls)".format(state.p50Millis, state.p95Millis, state.samples),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        item { SectionHeader("Recent calls") }
        if (state.recent.isEmpty()) item { EmptyState("Screened calls will show up here.") }
        items(state.recent, key = { it.id }) { event ->
            EventRow(event, event.sourceId?.let(state.sourceNames::get))
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(16.dp)) {
            Text(value, style = MaterialTheme.typography.displaySmall)
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
