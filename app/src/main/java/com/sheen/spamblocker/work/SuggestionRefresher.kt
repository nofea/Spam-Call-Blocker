package com.sheen.spamblocker.work

import com.sheen.spamblocker.calllog.CallLogReader
import com.sheen.spamblocker.data.DerivedSuggestion
import com.sheen.spamblocker.data.RuleOrigin
import com.sheen.spamblocker.data.RuleType
import com.sheen.spamblocker.data.SettingsRepository
import com.sheen.spamblocker.data.SpamBlockerDatabase
import com.sheen.spamblocker.data.SuggestionStatus
import com.sheen.spamblocker.rules.PrefixDeriver
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** Gathers the inputs for [PrefixDeriver] and stores new PENDING suggestions (SPEC §6.2). */
@Singleton
class SuggestionRefresher @Inject constructor(
    private val db: SpamBlockerDatabase,
    private val callLog: CallLogReader,
    private val settings: SettingsRepository,
) {
    private val lock = Mutex()

    suspend fun refresh() = lock.withLock {
        val s = settings.current()
        val blockRules = db.blockRules().getAll().filter { it.enabled }
        val allowRules = db.allowRules().getAll()
        val existing = db.suggestions().getAll()

        val blocked = blockRules.filter { it.type == RuleType.EXACT && it.origin != RuleOrigin.DERIVED }.map { it.pattern }
        val allowExact = allowRules.filter { it.type == RuleType.EXACT }.map { it.pattern }
        val allowPrefixes = allowRules.filter { it.type == RuleType.PREFIX }.map { it.pattern.removeSuffix("*") }
        val blockPrefixes = blockRules.filter { it.type == RuleType.PREFIX }.map { it.pattern.removeSuffix("*") }

        val candidates = PrefixDeriver(s.deriveMinCount, s.deriveMinPrefixDigits).derive(
            blockedNumbers = blocked,
            protectedNumbers = callLog.contactNumbers() + callLog.outgoingNumbers() + allowExact,
            allowPrefixes = allowPrefixes,
            existingBlockPrefixes = blockPrefixes,
            // Dismissed and accepted prefixes are never suggested again.
            dismissedPrefixes = existing.filter { it.status != SuggestionStatus.PENDING }.map { it.prefix },
        )

        val now = System.currentTimeMillis()
        db.suggestions().deletePendingExcept(candidates.map { it.prefix })
        candidates.forEach { c ->
            val current = existing.firstOrNull { it.prefix == c.prefix }
            if (current == null || current.status == SuggestionStatus.PENDING) {
                db.suggestions().upsert(
                    DerivedSuggestion(
                        prefix = c.prefix,
                        sampleNumbers = c.sampleNumbers,
                        rangeSize = c.rangeSize,
                        status = SuggestionStatus.PENDING,
                        createdAt = current?.createdAt ?: now,
                    ),
                )
            }
        }
    }
}
