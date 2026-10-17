package com.sheen.spamblocker.calllog

import android.content.ContentResolver
import android.content.Context
import android.os.Bundle
import android.provider.CallLog.Calls
import android.provider.ContactsContract
import android.net.Uri
import com.sheen.spamblocker.phone.NormalizedNumber
import com.sheen.spamblocker.phone.PhoneNormalizer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class CallLogItem(
    val id: Long,
    val rawNumber: String?,
    /** E.164, or null for hidden or unparseable numbers. */
    val e164: String?,
    val type: Int,
    val dateMillis: Long,
    val durationSeconds: Long,
    val cachedName: String?,
) {
    val typeLabel: String
        get() = when (type) {
            Calls.INCOMING_TYPE -> "Incoming"
            Calls.MISSED_TYPE -> "Missed"
            Calls.REJECTED_TYPE -> "Rejected"
            Calls.BLOCKED_TYPE -> "Blocked"
            Calls.OUTGOING_TYPE -> "Outgoing"
            Calls.VOICEMAIL_TYPE -> "Voicemail"
            else -> "Other"
        }
}

/**
 * Reads the system call log (SPEC §7) and contacts. Every method returns an
 * empty or negative result instead of throwing when the permission is missing.
 */
@Singleton
class CallLogReader @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val normalizer: PhoneNormalizer,
) {
    private val resolver: ContentResolver get() = context.contentResolver

    /** Calls the system dialer already blocked, newest first, one per number (first-run import). */
    suspend fun blockedCalls(): List<CallLogItem> =
        query("${Calls.TYPE} = ?", arrayOf(Calls.BLOCKED_TYPE.toString()))
            .filter { it.e164 != null }
            .distinctBy { it.e164 }

    /** Incoming, missed, rejected and blocked calls for the Call log screen. */
    suspend fun recentIncoming(limit: Int = 300): List<CallLogItem> =
        query(
            "${Calls.TYPE} IN (?, ?, ?, ?)",
            arrayOf(Calls.INCOMING_TYPE, Calls.MISSED_TYPE, Calls.REJECTED_TYPE, Calls.BLOCKED_TYPE).map(Int::toString)
                .toTypedArray(),
            limit,
        )

    /**
     * Incoming and missed calls logged after row [afterId] that started at or
     * after [sinceMillis], for the post-call prompt. Rows are added when a call
     * ends, so the row id (not the start time) tells what is new.
     */
    suspend fun answeredOrMissedAfter(afterId: Long, sinceMillis: Long): List<CallLogItem> =
        query(
            "${Calls.TYPE} IN (?, ?) AND ${Calls._ID} > ? AND ${Calls.DATE} >= ?",
            arrayOf(Calls.INCOMING_TYPE.toString(), Calls.MISSED_TYPE.toString(), afterId.toString(), sinceMillis.toString()),
        )

    /** Every number the user has called (excluded from derived prefixes, SPEC §6.2). */
    suspend fun outgoingNumbers(): Set<String> =
        query("${Calls.TYPE} = ?", arrayOf(Calls.OUTGOING_TYPE.toString())).mapNotNullTo(HashSet()) { it.e164 }

    /**
     * Recent-outgoing check (SPEC §10): one query with LIMIT 1. Dialers store
     * numbers in different formats, so it matches the common spellings.
     */
    suspend fun calledSince(e164: String, sinceMillis: Long): Boolean = withContext(Dispatchers.IO) {
        if (!Permissions.canReadCallLog(context)) return@withContext false
        val variants = spellings(e164)
        val selection = "${Calls.TYPE} = ? AND ${Calls.DATE} >= ? AND ${Calls.NUMBER} IN (${variants.joinToString { "?" }})"
        val args = arrayOf(Calls.OUTGOING_TYPE.toString(), sinceMillis.toString()) + variants
        runCatching {
            resolver.query(Calls.CONTENT_URI, arrayOf(Calls._ID), queryArgs(selection, args, null, 1), null)
                ?.use { it.moveToFirst() } ?: false
        }.getOrDefault(false)
    }

    /** Contact check (SPEC §10): one PhoneLookup query. */
    suspend fun isContact(e164: String): Boolean = withContext(Dispatchers.IO) {
        if (!Permissions.canReadContacts(context)) return@withContext false
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(e164))
        runCatching {
            resolver.query(uri, arrayOf(ContactsContract.PhoneLookup._ID), null, null, null)
                ?.use { it.moveToFirst() } ?: false
        }.getOrDefault(false)
    }

    /** All saved contacts' numbers in E.164 (excluded from derived prefixes, SPEC §6.2). */
    suspend fun contactNumbers(): Set<String> = withContext(Dispatchers.IO) {
        if (!Permissions.canReadContacts(context)) return@withContext emptySet()
        val result = HashSet<String>()
        runCatching {
            resolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    (normalizer.normalize(c.getString(0)) as? NormalizedNumber.Parsed)?.let { result += it.e164 }
                }
            }
        }
        result
    }

    private suspend fun query(selection: String, args: Array<String>, limit: Int? = null): List<CallLogItem> =
        withContext(Dispatchers.IO) {
            if (!Permissions.canReadCallLog(context)) return@withContext emptyList()
            val projection = arrayOf(Calls._ID, Calls.NUMBER, Calls.TYPE, Calls.DATE, Calls.DURATION, Calls.CACHED_NAME)
            val items = ArrayList<CallLogItem>()
            runCatching {
                resolver.query(Calls.CONTENT_URI, projection, queryArgs(selection, args, "${Calls.DATE} DESC", limit), null)
                    ?.use { c ->
                        while (c.moveToNext()) {
                            val raw = c.getString(1)
                            items += CallLogItem(
                                id = c.getLong(0),
                                rawNumber = raw,
                                e164 = (normalizer.normalize(raw) as? NormalizedNumber.Parsed)?.e164,
                                type = c.getInt(2),
                                dateMillis = c.getLong(3),
                                durationSeconds = c.getLong(4),
                                cachedName = c.getString(5)?.takeIf { it.isNotBlank() },
                            )
                        }
                    }
            }
            items
        }

    private fun queryArgs(selection: String, args: Array<String>, sort: String?, limit: Int?) = Bundle().apply {
        putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
        putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
        sort?.let { putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, it) }
        limit?.let { putInt(ContentResolver.QUERY_ARG_LIMIT, it) }
    }

    /** `+919876543210` → itself, `919876543210`, `09876543210`, `9876543210`. */
    private fun spellings(e164: String): Array<String> {
        val variants = linkedSetOf(e164, e164.removePrefix("+"))
        if (e164.startsWith("+91")) {
            val national = e164.removePrefix("+91")
            variants += "0$national"
            variants += national
        }
        return variants.toTypedArray()
    }
}
