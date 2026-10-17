package com.sheen.spamblocker.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.sheen.spamblocker.data.RefreshInterval
import com.sheen.spamblocker.data.Settings
import com.sheen.spamblocker.data.SettingsRepository
import com.sheen.spamblocker.rules.Action
import com.sheen.spamblocker.rules.PrefixDeriver
import com.sheen.spamblocker.ui.common.ActionChoiceRow
import com.sheen.spamblocker.ui.common.SectionHeader
import com.sheen.spamblocker.ui.common.label
import com.sheen.spamblocker.ui.common.rememberAccessState
import com.sheen.spamblocker.work.SuggestionRefresher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repo: SettingsRepository,
    private val suggestions: SuggestionRefresher,
) : ViewModel() {
    val settings: StateFlow<Settings?> = repo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun update(transform: (Settings) -> Settings) = viewModelScope.launch {
        val before = repo.current()
        repo.update(transform)
        val after = repo.current()
        if (before.deriveMinCount != after.deriveMinCount || before.deriveMinPrefixDigits != after.deriveMinPrefixDigits) {
            suggestions.refresh()
        }
    }
}

/** SPEC §9.2 and §11 Settings. */
@Composable
fun SettingsScreen(onRerunImport: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val s = vm.settings.collectAsStateWithLifecycle().value ?: return
    val access = rememberAccessState()
    var about by remember { mutableStateOf(false) }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        SectionHeader("Built-in rules")
        ToggleWithAction(
            "Block hidden callers", "No number, or the number is withheld",
            s.blockHidden, s.hiddenAction,
            onToggle = { v -> vm.update { it.copy(blockHidden = v) } },
            onAction = { a -> vm.update { it.copy(hiddenAction = a) } },
        )
        ToggleWithAction(
            "Block international calls", "Any number outside +91",
            s.blockInternational, s.internationalAction,
            onToggle = { v -> vm.update { it.copy(blockInternational = v) } },
            onAction = { a -> vm.update { it.copy(internationalAction = a) } },
        )
        ToggleWithAction(
            "Block TRAI 140-series", "Promotional telemarketers",
            s.trai140Enabled, s.trai140Action,
            onToggle = { v -> vm.update { it.copy(trai140Enabled = v) } },
            onAction = { a -> vm.update { it.copy(trai140Action = a) } },
        )
        Toggle("Always allow TRAI 160-series", "Banks, government and other regulated senders", s.trai160Allow) { v ->
            vm.update { it.copy(trai160Allow = v) }
        }

        SectionHeader("Let these through")
        NumberSlider(
            title = "Numbers you called recently",
            value = s.recentOutgoingDays, range = 0..30,
            format = { if (it == 0) "Off" else "Last $it days" },
            note = "Needs call log access".takeIf { !access.callLog },
        ) { v -> vm.update { it.copy(recentOutgoingDays = v) } }
        NumberSlider(
            title = "Repeat callers",
            value = s.repeatCallerMinutes, range = 0..30,
            format = { if (it == 0) "Off" else "Second call within $it min" },
        ) { v -> vm.update { it.copy(repeatCallerMinutes = v) } }

        SectionHeader("Prefix suggestions")
        NumberSlider(
            title = "Blocked numbers needed", value = s.deriveMinCount, range = 2..10,
            format = { "$it numbers" },
        ) { v -> vm.update { it.copy(deriveMinCount = v) } }
        NumberSlider(
            title = "Shortest prefix", value = s.deriveMinPrefixDigits, range = 6..PrefixDeriver.MAX_PREFIX_DIGITS,
            format = { "$it digits (up to ${"%,d".format(pow10(10 - it))} numbers)" },
        ) { v -> vm.update { it.copy(deriveMinPrefixDigits = v) } }

        SectionHeader("Remote lists")
        RefreshIntervalPicker(s.listRefreshInterval) { v -> vm.update { it.copy(listRefreshInterval = v) } }
        Toggle("Wi-Fi only", "Only refresh lists on unmetered networks", s.listRefreshUnmeteredOnly) { v ->
            vm.update { it.copy(listRefreshUnmeteredOnly = v) }
        }

        SectionHeader("Notifications")
        Toggle("Ask after unknown calls", "\"Block this number?\" after a call from an unknown number", s.postCallPrompt) { v ->
            vm.update { it.copy(postCallPrompt = v) }
        }
        Toggle("Daily blocked-calls summary", null, s.notifyOnBlock) { v -> vm.update { it.copy(notifyOnBlock = v) } }

        SectionHeader("More")
        ListItem(
            modifier = Modifier.clickable(onClick = onRerunImport),
            headlineContent = { Text("Re-run blocked-call import") },
            supportingContent = { Text("Add numbers your phone has blocked to your block list") },
        )
        ListItem(
            modifier = Modifier.clickable { about = true },
            headlineContent = { Text("About / privacy") },
        )
    }

    if (about) {
        AlertDialog(
            onDismissRequest = { about = false },
            title = { Text("Privacy") },
            text = {
                Text(
                    "Your calls, contacts and rules never leave this phone. The app has no analytics, crash " +
                        "reporting or ads. Its only network requests download the list URLs you add, and app data " +
                        "is excluded from cloud backups.",
                )
            },
            confirmButton = { TextButton(onClick = { about = false }) { Text("OK") } },
        )
    }
}

@Composable
private fun Toggle(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        modifier = Modifier.clickable { onChange(!checked) },
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
    )
}

@Composable
private fun ToggleWithAction(
    title: String,
    subtitle: String,
    enabled: Boolean,
    action: Action,
    onToggle: (Boolean) -> Unit,
    onAction: (Action) -> Unit,
) {
    Toggle(title, if (enabled) "$subtitle · ${action.label}" else subtitle, enabled, onToggle)
    if (enabled) ActionChoiceRow(action, onAction, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
    HorizontalDivider()
}

@Composable
private fun NumberSlider(
    title: String,
    value: Int,
    range: IntRange,
    format: (Int) -> String,
    note: String? = null,
    onChange: (Int) -> Unit,
) {
    var current by remember(value) { mutableStateOf(value.toFloat()) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(format(current.roundToInt()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        Slider(
            value = current,
            onValueChange = { current = it },
            onValueChangeFinished = { onChange(current.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = range.last - range.first - 1,
        )
    }
}

@Composable
private fun RefreshIntervalPicker(selected: RefreshInterval, onSelect: (RefreshInterval) -> Unit) {
    Column {
        RefreshInterval.entries.forEach { option ->
            ListItem(
                modifier = Modifier.clickable { onSelect(option) },
                headlineContent = { Text(option.label) },
                leadingContent = { RadioButton(selected = option == selected, onClick = { onSelect(option) }) },
            )
        }
    }
}

private fun pow10(exp: Int) = (1..exp).fold(1L) { acc, _ -> acc * 10 }
