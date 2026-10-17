package com.sheen.spamblocker.ui.history

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.sheen.spamblocker.data.CallEvent
import com.sheen.spamblocker.data.NumberStatus
import com.sheen.spamblocker.rules.Action
import com.sheen.spamblocker.ui.common.ActionRow
import com.sheen.spamblocker.ui.common.BlockDialog
import com.sheen.spamblocker.ui.common.DecisionBadge
import com.sheen.spamblocker.ui.common.label
import com.sheen.spamblocker.ui.common.relativeTime

/** One screened call: number, decision, why, and when (SPEC §11 History). */
@Composable
fun EventRow(
    event: CallEvent,
    sourceName: String?,
    status: NumberStatus? = null,
    onBlock: ((String, Action) -> Unit)? = null,
    onNotSpam: ((String) -> Unit)? = null,
) {
    var blocking by remember { mutableStateOf(false) }
    val number = event.number
    Column {
        ListItem(
            headlineContent = { Text(number ?: "Hidden number") },
            supportingContent = {
                val why = buildString {
                    append(event.stage.label)
                    sourceName?.let { append(" · ").append(it) }
                    if (event.unparsed) append(" · unparsed")
                }
                Text(listOfNotNull(why, relativeTime(event.timestamp), status?.label).joinToString("\n"))
            },
            trailingContent = { DecisionBadge(event.decision) },
        )
        if (number != null && (onBlock != null || onNotSpam != null)) {
            ActionRow {
                onNotSpam?.let { TextButton(onClick = { it(number) }) { Text("Not spam") } }
                onBlock?.let { TextButton(onClick = { blocking = true }) { Text("Block") } }
            }
        }
    }
    if (blocking && number != null && onBlock != null) {
        BlockDialog(number, (status as? NumberStatus.Blocked)?.action ?: Action.REJECT, onBlock = { onBlock(number, it); blocking = false }, onDismiss = { blocking = false })
    }
}
