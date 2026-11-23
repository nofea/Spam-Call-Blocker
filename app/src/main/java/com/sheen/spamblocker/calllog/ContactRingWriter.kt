package com.sheen.spamblocker.calllog

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.Contacts
import com.sheen.spamblocker.rules.Action
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Applies the user's exact block rules to saved contacts (SPEC §5, §7).
 *
 * Telecom never binds a screening app that isn't the default dialer for calls
 * from contacts, so a block rule on a contact has to be stored on the contact
 * itself, where Telecom reads it:
 * - Silence sets the contact's ringtone to "None" (an empty `CUSTOM_RINGTONE`).
 * - Reject sets `SEND_TO_VOICEMAIL`.
 * - Warn has no contact equivalent, so it's written the same as no rule.
 *
 * Every method returns false instead of throwing when the number isn't a
 * contact or `WRITE_CONTACTS` is missing.
 */
@Singleton
class ContactRingWriter @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    /** Makes [e164]'s contacts (if any) follow [action]. */
    suspend fun apply(e164: String, action: Action): Boolean = withContactIds(e164) { ids ->
        when (action) {
            Action.SILENCE -> update(ids, ContentValues().apply {
                put(Contacts.CUSTOM_RINGTONE, SILENT_RINGTONE)
                put(Contacts.SEND_TO_VOICEMAIL, 0)
            })
            Action.REJECT -> {
                // Undo an earlier Silence so a later Not spam has nothing stale to restore.
                clearSilentRingtone(ids)
                update(ids, ContentValues().apply { put(Contacts.SEND_TO_VOICEMAIL, 1) })
            }
            Action.WARN -> clearAll(ids)
        }
    }

    /**
     * Removes what [apply] wrote for [e164]. A ringtone is only reset when it
     * is the silent one, so a ringtone the user picked is left alone.
     */
    suspend fun clear(e164: String): Boolean = withContactIds(e164, ::clearAll)

    private fun clearAll(ids: List<Long>) {
        clearSilentRingtone(ids)
        update(ids, ContentValues().apply { put(Contacts.SEND_TO_VOICEMAIL, 0) })
    }

    private fun clearSilentRingtone(ids: List<Long>) =
        update(
            ids,
            ContentValues().apply { putNull(Contacts.CUSTOM_RINGTONE) },
            extraSelection = "${Contacts.CUSTOM_RINGTONE} = ?",
            extraArgs = arrayOf(SILENT_RINGTONE),
        )

    private fun update(ids: List<Long>, values: ContentValues, extraSelection: String? = null, extraArgs: Array<String> = emptyArray()) {
        val selection = buildString {
            append("${Contacts._ID} IN (${ids.joinToString { "?" }})")
            extraSelection?.let { append(" AND ").append(it) }
        }
        context.contentResolver.update(Contacts.CONTENT_URI, values, selection, ids.map(Long::toString).toTypedArray() + extraArgs)
    }

    /** Runs [block] on the IDs of the contacts that have [e164]; false when there are none or access is missing. */
    private suspend fun withContactIds(e164: String, block: (List<Long>) -> Unit): Boolean = withContext(Dispatchers.IO) {
        if (!Permissions.canReadContacts(context) || !Permissions.canWriteContacts(context)) return@withContext false
        runCatching {
            val ids = contactIds(e164)
            if (ids.isEmpty()) return@runCatching false
            block(ids)
            true
        }.getOrDefault(false)
    }

    private fun contactIds(e164: String): List<Long> {
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(e164))
        val ids = ArrayList<Long>()
        context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup._ID), null, null, null)?.use { c ->
            while (c.moveToNext()) ids += c.getLong(0)
        }
        return ids.distinct()
    }

    private companion object {
        /** What the Contacts app stores for ringtone "None"; Telecom reads it as silent. */
        const val SILENT_RINGTONE = ""
    }
}
