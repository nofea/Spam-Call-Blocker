package com.sheen.spamblocker.calllog

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager

/** Runtime permission checks; every feature degrades gracefully without them (SPEC §3). */
object Permissions {
    fun canReadCallLog(context: Context) = granted(context, Manifest.permission.READ_CALL_LOG)

    fun canReadContacts(context: Context) = granted(context, Manifest.permission.READ_CONTACTS)

    fun canNotify(context: Context) = granted(context, Manifest.permission.POST_NOTIFICATIONS)

    fun holdsScreeningRole(context: Context): Boolean =
        context.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_CALL_SCREENING)

    private fun granted(context: Context, permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
