package com.sheen.spamblocker.ui.common

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sheen.spamblocker.rules.Action
import com.sheen.spamblocker.screening.Decision

val Action.label: String
    get() = when (this) {
        Action.REJECT -> "Reject"
        Action.SILENCE -> "Silence"
        Action.WARN -> "Warn"
    }

val Action.description: String
    get() = when (this) {
        Action.REJECT -> "End the call before it rings"
        Action.SILENCE -> "Let it arrive silently, shown as missed"
        Action.WARN -> "Ring normally with a spam warning"
    }

/** A segmented choice between the enum values in [options]. */
@Composable
fun <T> ChoiceRow(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        options.forEachIndexed { i, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
            ) { Text(label(option)) }
        }
    }
}

@Composable
fun ActionChoiceRow(selected: Action, onSelect: (Action) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        ChoiceRow(Action.entries, selected, { it.label }, onSelect)
        Text(
            selected.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** The action chooser behind every "Block" button (SPEC §7). */
@Composable
fun BlockDialog(number: String, initial: Action = Action.REJECT, onBlock: (Action) -> Unit, onDismiss: () -> Unit) {
    var action by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Block $number") },
        text = { ActionChoiceRow(action, { action = it }) },
        confirmButton = { TextButton(onClick = { onBlock(action) }) { Text("Block") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun DecisionBadge(decision: Decision, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val (container, content) = when (decision) {
        Decision.REJECT -> scheme.errorContainer to scheme.onErrorContainer
        Decision.SILENCE -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        Decision.WARN -> scheme.secondaryContainer to scheme.onSecondaryContainer
        Decision.ALLOW -> scheme.surfaceVariant to scheme.onSurfaceVariant
    }
    Badge(decision.name.lowercase().replaceFirstChar(Char::uppercase), container, content, modifier)
}

@Composable
fun Badge(text: String, container: Color, content: Color, modifier: Modifier = Modifier) {
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.small, modifier = modifier) {
        Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

/** Row of buttons aligned to the end, used under list items. */
@Composable
fun ActionRow(content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(end = 8.dp), horizontalArrangement = Arrangement.End) { content() }
}

fun relativeTime(millis: Long): String {
    val now = System.currentTimeMillis()
    if (now - millis in 0 until DateUtils.MINUTE_IN_MILLIS) return "Just now"
    return DateUtils.getRelativeTimeSpanString(millis, now, DateUtils.MINUTE_IN_MILLIS).toString()
}

/** "10⁵"-style coverage text (SPEC §6.1). */
fun coverageText(count: Long): String {
    if (count < 1_000) return count.toString()
    val exp = count.toString().length - 1
    return if (count == pow10(exp)) "10${superscript(exp)}" else "%,d".format(count)
}

private fun pow10(exp: Int) = (1..exp).fold(1L) { acc, _ -> acc * 10 }

private fun superscript(n: Int) = n.toString().map { "⁰¹²³⁴⁵⁶⁷⁸⁹"[it - '0'] }.joinToString("")
