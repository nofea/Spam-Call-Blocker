package com.sheen.spamblocker.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.sheen.spamblocker.calllog.Permissions

data class AccessState(
    val screeningRole: Boolean,
    val callLog: Boolean,
    val contacts: Boolean,
    val notifications: Boolean,
)

/** Role and permission state, re-read whenever the screen resumes (e.g. after a settings round-trip). */
@Composable
fun rememberAccessState(): AccessState {
    val context = LocalContext.current
    fun read() = AccessState(
        screeningRole = Permissions.holdsScreeningRole(context),
        callLog = Permissions.canReadCallLog(context),
        contacts = Permissions.canReadContacts(context),
        notifications = Permissions.canNotify(context),
    )
    var state by remember { mutableStateOf(read()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { state = read() }
    return state
}
