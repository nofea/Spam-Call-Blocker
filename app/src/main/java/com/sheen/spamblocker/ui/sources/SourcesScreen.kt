package com.sheen.spamblocker.ui.sources

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import com.sheen.spamblocker.data.ListSource
import com.sheen.spamblocker.data.ListSourceDao
import com.sheen.spamblocker.data.FalsePositiveDao
import com.sheen.spamblocker.lists.ListFormat
import com.sheen.spamblocker.ui.common.ActionChoiceRow
import com.sheen.spamblocker.ui.common.ActionRow
import com.sheen.spamblocker.ui.common.ChoiceRow
import com.sheen.spamblocker.ui.common.EmptyState
import com.sheen.spamblocker.ui.common.label
import com.sheen.spamblocker.ui.common.relativeTime
import com.sheen.spamblocker.work.WorkScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject

data class SourceRow(val source: ListSource, val falsePositives: Int)

@HiltViewModel
class SourcesViewModel @Inject constructor(
    private val sources: ListSourceDao,
    falsePositives: FalsePositiveDao,
    private val scheduler: WorkScheduler,
) : ViewModel() {
    val rows: StateFlow<List<SourceRow>> = combine(sources.observeAll(), falsePositives.observeBySource()) { list, fps ->
        val counts = fps.associate { it.sourceId to it.count }
        list.map { SourceRow(it, counts[it.id] ?: 0) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun isRefreshing(sourceId: Long): Flow<Boolean> =
        scheduler.refreshStatus(sourceId).map { infos -> infos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED } }

    fun save(source: ListSource) = viewModelScope.launch {
        if (source.id == 0L) {
            scheduler.refreshNow(sources.insert(source))
        } else {
            val old = sources.get(source.id)
            // A new URL or format invalidates the cached validators.
            val changed = old == null || old.url != source.url || old.format != source.format || old.csvColumn != source.csvColumn
            sources.update(if (changed) source.copy(etag = null, lastModified = null) else source)
            if (changed) scheduler.refreshNow(source.id)
        }
    }

    fun setEnabled(source: ListSource, enabled: Boolean) = viewModelScope.launch { sources.update(source.copy(enabled = enabled)) }

    fun delete(source: ListSource) = viewModelScope.launch { sources.delete(source) }

    fun refreshNow(source: ListSource) = scheduler.refreshNow(source.id)
}

/** SPEC §8 / §11: remote list subscriptions. */
@Composable
fun SourcesScreen(vm: SourcesViewModel = hiltViewModel()) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<ListSource?>(null) }

    Box(Modifier.fillMaxSize()) {
        if (rows.isEmpty()) {
            EmptyState("No lists yet. Add the URL of a spam number list (TXT, CSV or JSON) to subscribe to it.")
        } else {
            LazyColumn {
                items(rows, key = { it.source.id }) { row -> SourceItem(row, vm, onEdit = { editing = row.source }) }
            }
        }
        FloatingActionButton(
            onClick = { editing = ListSource(name = "", url = "", format = ListFormat.TXT) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) { Icon(Icons.Filled.Add, contentDescription = "Add list") }
    }
    editing?.let { source -> SourceDialog(source, onSave = { vm.save(it); editing = null }, onDelete = { vm.delete(it); editing = null }, onDismiss = { editing = null }) }
}

@Composable
private fun SourceItem(row: SourceRow, vm: SourcesViewModel, onEdit: () -> Unit) {
    val s = row.source
    val refreshing by remember(s.id) { vm.isRefreshing(s.id) }.collectAsStateWithLifecycle(false)
    ListItem(
        modifier = Modifier.clickable(onClick = onEdit),
        headlineContent = { Text(s.name) },
        supportingContent = {
            Column {
                Text(s.url, maxLines = 1)
                val fetched = s.lastFetchedAt?.let { "updated ${relativeTime(it)}" } ?: "never fetched"
                Text("${s.format} · ${s.action.label} · %,d entries · $fetched".format(s.entryCount))
                if (row.falsePositives > 0) Text("${row.falsePositives} marked not spam")
                s.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        trailingContent = { Switch(checked = s.enabled, onCheckedChange = { vm.setEnabled(s, it) }) },
    )
    ActionRow {
        TextButton(onClick = { vm.refreshNow(s) }, enabled = !refreshing) { Text(if (refreshing) "Refreshing…" else "Refresh now") }
    }
    HorizontalDivider()
}

@Composable
private fun SourceDialog(source: ListSource, onSave: (ListSource) -> Unit, onDelete: (ListSource) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(source.name) }
    var url by remember { mutableStateOf(source.url) }
    var format by remember { mutableStateOf(source.format) }
    var column by remember { mutableStateOf(source.csvColumn.orEmpty()) }
    var action by remember { mutableStateOf(source.action) }
    val urlValid = url.trim().toHttpUrlOrNull() != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (source.id == 0L) "Add list" else "Edit list") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    url, { url = it },
                    label = { Text("URL") },
                    singleLine = true,
                    isError = url.isNotBlank() && !urlValid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                ChoiceRow(ListFormat.entries, format, { it.name }, { format = it })
                if (format == ListFormat.CSV) {
                    OutlinedTextField(
                        column, { column = it },
                        label = { Text("Number column") },
                        supportingText = { Text("Header name or column number; blank = first column") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text("Action for numbers on this list", style = MaterialTheme.typography.labelLarge)
                ActionChoiceRow(action, { action = it })
                if (source.id != 0L) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                        TextButton(onClick = { onDelete(source) }) { Text("Delete list", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && urlValid,
                onClick = {
                    onSave(
                        source.copy(
                            name = name.trim(),
                            url = url.trim(),
                            format = format,
                            csvColumn = column.trim().takeIf { format == ListFormat.CSV && it.isNotEmpty() },
                            action = action,
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
