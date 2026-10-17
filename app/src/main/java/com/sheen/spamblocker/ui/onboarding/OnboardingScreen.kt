package com.sheen.spamblocker.ui.onboarding

import android.Manifest
import android.app.role.RoleManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.sheen.spamblocker.calllog.CallLogItem
import com.sheen.spamblocker.calllog.CallLogReader
import com.sheen.spamblocker.data.CallEventDao
import com.sheen.spamblocker.data.RulesRepository
import com.sheen.spamblocker.data.SettingsRepository
import com.sheen.spamblocker.screening.Decision
import com.sheen.spamblocker.ui.common.relativeTime
import com.sheen.spamblocker.ui.common.rememberAccessState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.abs

data class ImportState(
    val loading: Boolean = true,
    val calls: List<CallLogItem> = emptyList(),
    val selected: Set<String> = emptySet(),
)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val callLog: CallLogReader,
    private val callEvents: CallEventDao,
    private val rules: RulesRepository,
    private val settings: SettingsRepository,
) : ViewModel() {
    private val _import = MutableStateFlow(ImportState())
    val import = _import.asStateFlow()

    /**
     * SPEC §7: every blocked call-log number, all checked by default. Calls
     * this app rejected are logged as blocked too; they're left out so a
     * re-run doesn't copy list or TRAI matches into the user block list.
     */
    fun loadBlockedCalls() = viewModelScope.launch {
        val calls = callLog.blockedCalls().filterNot { call ->
            val event = call.e164?.let { callEvents.latestFor(it) }
            event != null && event.decision == Decision.REJECT && abs(event.timestamp - call.dateMillis) <= MATCH_WINDOW_MILLIS
        }
        _import.value = ImportState(loading = false, calls = calls, selected = calls.mapNotNull { it.e164 }.toSet())
    }

    fun toggle(number: String) = _import.update {
        it.copy(selected = if (number in it.selected) it.selected - number else it.selected + number)
    }

    fun finish(importSelected: Boolean, onDone: () -> Unit) = viewModelScope.launch {
        if (importSelected) rules.importBlocked(_import.value.selected)
        settings.update { it.copy(importOffered = true) }
        onDone()
    }

    private companion object {
        const val MATCH_WINDOW_MILLIS = 2 * 60 * 1000L
    }
}

private enum class Step { INTRO, ROLE, PERMISSIONS, IMPORT }

/**
 * SPEC §11: explanation → call screening role → permissions → first-run import.
 * With [importOnly], only the import step is shown (Settings → Re-run import).
 */
@Composable
fun OnboardingScreen(onDone: () -> Unit, importOnly: Boolean = false, vm: OnboardingViewModel = hiltViewModel()) {
    var step by rememberSaveable { mutableStateOf(if (importOnly) Step.IMPORT else Step.INTRO) }
    val access = rememberAccessState()
    val context = LocalContext.current

    val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        step = Step.PERMISSIONS
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        step = Step.IMPORT
    }

    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Spacer(Modifier.height(24.dp))
        when (step) {
            Step.INTRO -> {
                Title("Stop spam calls before they ring")
                Body(
                    "Spam Call Blocker screens incoming calls using TRAI's 140-series, lists you subscribe to, " +
                        "your own block list and number patterns.\n\nEverything stays on this phone. " +
                        "The app only goes online to download the lists you add.",
                )
                Spacer(Modifier.weight(1f))
                Button(onClick = { step = Step.ROLE }, Modifier.fillMaxWidth()) { Text("Get started") }
            }
            Step.ROLE -> {
                Title("Let the app screen calls")
                Body(
                    "Android asks you to choose this app as your caller ID & spam app. " +
                        "Calls from your contacts are never sent to it.",
                )
                Spacer(Modifier.weight(1f))
                if (access.screeningRole) {
                    Button(onClick = { step = Step.PERMISSIONS }, Modifier.fillMaxWidth()) { Text("Continue") }
                } else {
                    Button(
                        onClick = {
                            val rm = context.getSystemService(RoleManager::class.java)
                            roleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
                        },
                        Modifier.fillMaxWidth(),
                    ) { Text("Allow call screening") }
                    TextButton(onClick = { step = Step.PERMISSIONS }, Modifier.fillMaxWidth()) { Text("Skip") }
                }
            }
            Step.PERMISSIONS -> {
                Title("A few permissions")
                Body(
                    "• Call log: import calls you've already blocked, and let through numbers you called recently.\n" +
                        "• Contacts: never block or suggest ranges that include your contacts.\n" +
                        "• Notifications: spam warnings and \"Block this number?\" prompts.\n\n" +
                        "Each one is optional; the related feature just turns off.",
                )
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = {
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.READ_CALL_LOG,
                                Manifest.permission.READ_CONTACTS,
                                Manifest.permission.POST_NOTIFICATIONS,
                            ),
                        )
                    },
                    Modifier.fillMaxWidth(),
                ) { Text("Continue") }
            }
            Step.IMPORT -> ImportStep(vm, canReadCallLog = access.callLog, onDone = onDone)
        }
    }
}

@Composable
private fun ImportStep(vm: OnboardingViewModel, canReadCallLog: Boolean, onDone: () -> Unit) {
    val state by vm.import.collectAsStateWithLifecycle()
    LaunchedEffect(canReadCallLog) { vm.loadBlockedCalls() }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Title("Import blocked numbers")
        when {
            !canReadCallLog -> Body("Without call log access, numbers you've already blocked can't be imported.")
            state.loading -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            state.calls.isEmpty() -> Body("Your call log has no blocked calls to import.")
            else -> {
                Body("These numbers were blocked by your phone. Checked ones are added to your block list.")
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    items(state.calls, key = { it.id }) { call ->
                        val number = call.e164 ?: return@items
                        ListItem(
                            headlineContent = { Text(number) },
                            supportingContent = { Text("Blocked ${relativeTime(call.dateMillis)}") },
                            leadingContent = {
                                Checkbox(checked = number in state.selected, onCheckedChange = { vm.toggle(number) })
                            },
                        )
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.finish(importSelected = false, onDone) }, Modifier.weight(1f)) { Text("Skip") }
            Button(
                onClick = { vm.finish(importSelected = true, onDone) },
                enabled = !state.loading || !canReadCallLog,
                modifier = Modifier.weight(1f),
            ) { Text(if (state.selected.isEmpty()) "Done" else "Import ${state.selected.size}") }
        }
    }
}

@Composable
private fun Title(text: String) = Text(text, style = MaterialTheme.typography.headlineSmall)

@Composable
private fun Body(text: String) = Text(text, style = MaterialTheme.typography.bodyLarge)
