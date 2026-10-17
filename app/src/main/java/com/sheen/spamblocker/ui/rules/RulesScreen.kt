package com.sheen.spamblocker.ui.rules

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.sheen.spamblocker.data.AllowRule
import com.sheen.spamblocker.data.BlockRule
import com.sheen.spamblocker.data.DerivedSuggestion
import com.sheen.spamblocker.data.RuleOrigin
import com.sheen.spamblocker.data.RuleType
import com.sheen.spamblocker.data.RulesRepository
import com.sheen.spamblocker.rules.Action
import com.sheen.spamblocker.rules.CompiledPattern
import com.sheen.spamblocker.rules.InvalidPatternException
import com.sheen.spamblocker.rules.WildcardCompiler
import com.sheen.spamblocker.ui.common.ActionChoiceRow
import com.sheen.spamblocker.ui.common.ActionRow
import com.sheen.spamblocker.ui.common.EmptyState
import com.sheen.spamblocker.ui.common.coverageText
import com.sheen.spamblocker.ui.common.label
import com.sheen.spamblocker.ui.common.relativeTime
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RulesState(
    val numbers: List<BlockRule> = emptyList(),
    val patterns: List<BlockRule> = emptyList(),
    val allow: List<AllowRule> = emptyList(),
    val suggestions: List<DerivedSuggestion> = emptyList(),
)

@HiltViewModel
class RulesViewModel @Inject constructor(
    private val rules: RulesRepository,
    private val compiler: WildcardCompiler,
) : ViewModel() {
    val state: StateFlow<RulesState> = combine(
        rules.observeBlockRules,
        rules.observeAllowRules,
        rules.observePendingSuggestions,
    ) { block, allow, suggestions ->
        val (numbers, patterns) = block.partition { it.type == RuleType.EXACT }
        RulesState(numbers, patterns, allow, suggestions)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RulesState())

    /** Validates as the user types (SPEC §6.1). */
    fun preview(input: String): Result<CompiledPattern> =
        try {
            Result.success(compiler.compile(input))
        } catch (e: InvalidPatternException) {
            Result.failure(e)
        }

    fun blockNumber(input: String, action: Action) = viewModelScope.launch {
        val p = compiler.compile(input) as CompiledPattern.Exact
        rules.blockNumber("+${p.digits}", action)
    }

    fun addPattern(input: String, action: Action) = viewModelScope.launch { rules.addPattern(input, action) }

    fun addAllow(input: String) = viewModelScope.launch { rules.addAllow(input) }

    fun setEnabled(rule: BlockRule, enabled: Boolean) = viewModelScope.launch { rules.updateBlockRule(rule.copy(enabled = enabled)) }

    fun setAction(rule: BlockRule, action: Action) = viewModelScope.launch { rules.updateBlockRule(rule.copy(action = action)) }

    fun delete(rule: BlockRule) = viewModelScope.launch { rules.deleteBlockRule(rule) }

    fun delete(rule: AllowRule) = viewModelScope.launch { rules.deleteAllowRule(rule) }

    fun accept(s: DerivedSuggestion, action: Action) = viewModelScope.launch { rules.acceptSuggestion(s, action) }

    fun dismiss(s: DerivedSuggestion) = viewModelScope.launch { rules.dismissSuggestion(s) }
}

private enum class Tab(val title: String) { NUMBERS("Blocked"), PATTERNS("Patterns"), ALLOW("Allowlist"), SUGGESTIONS("Suggestions") }

private enum class AddKind { NUMBER, PATTERN, ALLOW }

@Composable
fun RulesScreen(vm: RulesViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.NUMBERS) }
    var adding by remember { mutableStateOf<AddKind?>(null) }
    var editing by remember { mutableStateOf<BlockRule?>(null) }
    var accepting by remember { mutableStateOf<DerivedSuggestion?>(null) }

    Box(Modifier.fillMaxSize()) {
        Column {
            PrimaryScrollableTabRow(selectedTabIndex = tab.ordinal, edgePadding = 0.dp) {
                Tab.entries.forEach { t ->
                    Tab(selected = tab == t, onClick = { tab = t }, text = {
                        if (t == Tab.SUGGESTIONS && state.suggestions.isNotEmpty()) {
                            BadgedBox(badge = { Badge { Text(state.suggestions.size.toString()) } }) { Text(t.title) }
                        } else {
                            Text(t.title)
                        }
                    })
                }
            }
            when (tab) {
                Tab.NUMBERS -> BlockRuleList(state.numbers, "No blocked numbers yet.", vm, onEdit = { editing = it })
                Tab.PATTERNS -> BlockRuleList(
                    state.patterns,
                    "No patterns. Add one like +9198765* or *4321.",
                    vm,
                    onEdit = { editing = it },
                )
                Tab.ALLOW -> AllowList(state.allow, vm)
                Tab.SUGGESTIONS -> SuggestionList(state.suggestions, onAccept = { accepting = it }, onDismiss = vm::dismiss)
            }
        }
        val addKind = when (tab) {
            Tab.NUMBERS -> AddKind.NUMBER
            Tab.PATTERNS -> AddKind.PATTERN
            Tab.ALLOW -> AddKind.ALLOW
            Tab.SUGGESTIONS -> null
        }
        if (addKind != null) {
            FloatingActionButton(onClick = { adding = addKind }, Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
                Icon(Icons.Filled.Add, contentDescription = "Add")
            }
        }
    }

    adding?.let { kind -> AddRuleDialog(kind, vm, onDismiss = { adding = null }) }
    editing?.let { rule -> EditRuleDialog(rule, vm, onDismiss = { editing = null }) }
    accepting?.let { s ->
        var action by remember(s) { mutableStateOf(Action.SILENCE) }
        AlertDialog(
            onDismissRequest = { accepting = null },
            title = { Text("Block ${s.prefix}*") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Covers ${coverageText(s.rangeSize)} numbers.")
                    ActionChoiceRow(action, { action = it })
                }
            },
            confirmButton = { TextButton(onClick = { vm.accept(s, action); accepting = null }) { Text("Accept") } },
            dismissButton = { TextButton(onClick = { accepting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun BlockRuleList(rules: List<BlockRule>, empty: String, vm: RulesViewModel, onEdit: (BlockRule) -> Unit) {
    if (rules.isEmpty()) {
        EmptyState(empty)
        return
    }
    LazyColumn {
        items(rules, key = { it.id }) { rule ->
            ListItem(
                modifier = Modifier.clickable { onEdit(rule) },
                headlineContent = { Text(rule.pattern) },
                supportingContent = {
                    val origin = when (rule.origin) {
                        RuleOrigin.USER -> "Added by you"
                        RuleOrigin.IMPORT -> "Imported"
                        RuleOrigin.MANUAL_PATTERN -> "Pattern"
                        RuleOrigin.DERIVED -> "Accepted suggestion"
                    }
                    Text("${rule.action.label} · $origin · ${relativeTime(rule.createdAt)}")
                },
                trailingContent = { Switch(checked = rule.enabled, onCheckedChange = { vm.setEnabled(rule, it) }) },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun AllowList(rules: List<AllowRule>, vm: RulesViewModel) {
    if (rules.isEmpty()) {
        EmptyState("Numbers and prefixes here are never blocked.")
        return
    }
    LazyColumn {
        items(rules, key = { it.id }) { rule ->
            ListItem(
                headlineContent = { Text(rule.pattern) },
                supportingContent = { Text(relativeTime(rule.createdAt)) },
                trailingContent = {
                    IconButton(onClick = { vm.delete(rule) }) { Icon(Icons.Filled.Delete, contentDescription = "Remove") }
                },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun SuggestionList(suggestions: List<DerivedSuggestion>, onAccept: (DerivedSuggestion) -> Unit, onDismiss: (DerivedSuggestion) -> Unit) {
    if (suggestions.isEmpty()) {
        EmptyState("When several blocked numbers share a prefix, a range to block will be suggested here.")
        return
    }
    LazyColumn {
        items(suggestions, key = { it.prefix }) { s ->
            ListItem(
                headlineContent = { Text("${s.prefix}*") },
                supportingContent = {
                    Text("${coverageText(s.rangeSize)} numbers, from ${s.sampleNumbers.size} blocked:\n${s.sampleNumbers.joinToString("\n")}")
                },
            )
            ActionRow {
                TextButton(onClick = { onDismiss(s) }) { Text("Dismiss") }
                TextButton(onClick = { onAccept(s) }) { Text("Accept") }
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun AddRuleDialog(kind: AddKind, vm: RulesViewModel, onDismiss: () -> Unit) {
    var input by rememberSaveable { mutableStateOf("") }
    var action by remember { mutableStateOf(Action.REJECT) }
    var confirmBroad by remember { mutableStateOf(false) }
    val preview = input.takeIf { it.isNotBlank() }?.let(vm::preview)
    val pattern = preview?.getOrNull()

    val error = when {
        preview == null -> null
        pattern == null -> preview.exceptionOrNull()?.message
        kind == AddKind.NUMBER && (pattern !is CompiledPattern.Exact || pattern.hasSingleDigitWildcard) ->
            "Enter a single number; use Patterns for ranges"
        kind == AddKind.ALLOW && (pattern is CompiledPattern.Suffix || pattern.hasSingleDigitWildcard) ->
            "The allowlist takes numbers or prefixes ending in *"
        else -> null
    }
    val coverage = pattern?.let(WildcardCompiler::coverage)
    val broad = kind == AddKind.PATTERN && coverage != null && coverage > WildcardCompiler.COVERAGE_WARNING_THRESHOLD

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when (kind) {
                    AddKind.NUMBER -> "Block a number"
                    AddKind.PATTERN -> "Add a pattern"
                    AddKind.ALLOW -> "Allow a number or prefix"
                },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it; confirmBroad = false },
                    label = { Text(if (kind == AddKind.NUMBER) "Number" else "Number or pattern") },
                    placeholder = { Text(if (kind == AddKind.NUMBER) "98765 43210" else "+9198765*") },
                    singleLine = true,
                    isError = error != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    supportingText = {
                        Text(
                            error ?: when {
                                pattern == null -> if (kind == AddKind.NUMBER) "" else "* = any digits (start or end), ? = one digit"
                                kind == AddKind.NUMBER -> pattern.text
                                else -> "${pattern.text} · covers ${coverageText(coverage ?: 0)} numbers"
                            },
                        )
                    },
                )
                if (kind != AddKind.ALLOW) ActionChoiceRow(action, { action = it })
                if (broad && confirmBroad) {
                    Text(
                        "This rule covers more than ${coverageText(WildcardCompiler.COVERAGE_WARNING_THRESHOLD)} numbers " +
                            "and may block calls you want. Tap Add again to save it anyway.",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = pattern != null && error == null,
                onClick = {
                    if (broad && !confirmBroad) {
                        confirmBroad = true
                        return@TextButton
                    }
                    when (kind) {
                        AddKind.NUMBER -> vm.blockNumber(input, action)
                        AddKind.PATTERN -> vm.addPattern(input, action)
                        AddKind.ALLOW -> vm.addAllow(input)
                    }
                    onDismiss()
                },
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun EditRuleDialog(rule: BlockRule, vm: RulesViewModel, onDismiss: () -> Unit) {
    var action by remember { mutableStateOf(rule.action) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(rule.pattern) },
        text = { ActionChoiceRow(action, { action = it }) },
        confirmButton = { TextButton(onClick = { vm.setAction(rule, action); onDismiss() }) { Text("Save") } },
        dismissButton = {
            TextButton(onClick = { vm.delete(rule); onDismiss() }) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
    )
}
