package com.sheen.spamblocker.data

import androidx.room.withTransaction
import com.sheen.spamblocker.rules.Action
import com.sheen.spamblocker.rules.CompiledPattern
import com.sheen.spamblocker.rules.InvalidPatternException
import com.sheen.spamblocker.rules.WildcardCompiler
import com.sheen.spamblocker.screening.Decision
import com.sheen.spamblocker.screening.Stage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

/** A number's exact user rule, shown next to past calls (SPEC §7). */
sealed interface NumberStatus {
    data class Blocked(val action: Action) : NumberStatus
    data object Allowlisted : NumberStatus
}

/** User-facing rule changes: Block, Not spam, patterns, allowlist, suggestions, import. */
@Singleton
class RulesRepository @Inject constructor(
    private val db: SpamBlockerDatabase,
    private val compiler: WildcardCompiler,
) {
    private val blockRules = db.blockRules()
    private val allowRules = db.allowRules()

    val observeBlockRules = blockRules.observeAll()
    val observeAllowRules = allowRules.observeAll()
    val observePendingSuggestions = db.suggestions().observePending()

    /**
     * Exact allow and enabled exact block rules by number. Past calls keep the
     * decision they got, so this is how the UI shows that a Block took effect.
     * The allowlist wins, as in the pipeline (SPEC §5).
     */
    val observeNumberStatus: Flow<Map<String, NumberStatus>> =
        combine(observeBlockRules, observeAllowRules) { blocks, allows ->
            buildMap {
                blocks.filter { it.type == RuleType.EXACT && it.enabled }
                    .forEach { put(it.pattern, NumberStatus.Blocked(it.action)) }
                allows.filter { it.type == RuleType.EXACT }.forEach { put(it.pattern, NumberStatus.Allowlisted) }
            }
        }

    /** "Block" (SPEC §7): exact block, replacing any exact allow for the number. */
    suspend fun blockNumber(e164: String, action: Action, origin: RuleOrigin = RuleOrigin.USER, note: String? = null) {
        db.withTransaction {
            allowRules.find(e164)?.let { allowRules.delete(it) }
            val existing = blockRules.findExact(e164)
            if (existing != null) {
                blockRules.update(existing.copy(action = action, enabled = true))
            } else {
                blockRules.insert(
                    BlockRule(
                        pattern = e164, type = RuleType.EXACT, action = action, origin = origin,
                        note = note, createdAt = now(),
                    ),
                )
            }
        }
    }

    /**
     * "Not spam" (SPEC §7): allowlist the number and drop its exact block. If
     * the last block came from a remote list or a derived prefix, record it as
     * a false positive for that source.
     */
    suspend fun markNotSpam(e164: String) {
        db.withTransaction {
            blockRules.deleteExact(e164)
            if (allowRules.find(e164) == null) {
                allowRules.insert(AllowRule(pattern = e164, type = RuleType.EXACT, createdAt = now()))
            }
            val last = db.callEvents().latestFor(e164)
            if (last != null && last.decision != Decision.ALLOW &&
                (last.stage == Stage.REMOTE_LIST || last.stage == Stage.DERIVED_PREFIX)
            ) {
                db.falsePositives().insert(
                    FalsePositive(
                        number = e164, stage = last.stage, ruleId = last.ruleId, sourceId = last.sourceId,
                        createdAt = now(),
                    ),
                )
            }
        }
    }

    /** Adds a manual pattern rule (SPEC §6.1). Throws [InvalidPatternException]. */
    suspend fun addPattern(input: String, action: Action, note: String? = null): BlockRule {
        val pattern = compiler.compile(input)
        val rule = BlockRule(
            pattern = pattern.text, type = pattern.ruleType, action = action,
            origin = RuleOrigin.MANUAL_PATTERN, note = note, createdAt = now(),
        )
        return rule.copy(id = blockRules.insert(rule))
    }

    /** Allowlist entries are exact numbers or plain prefixes (SPEC §9.1). Throws [InvalidPatternException]. */
    suspend fun addAllow(input: String, note: String? = null) {
        val pattern = compiler.compile(input)
        if (pattern is CompiledPattern.Suffix || pattern.hasSingleDigitWildcard) {
            throw InvalidPatternException("The allowlist takes exact numbers or prefixes (ending in *)")
        }
        if (allowRules.find(pattern.text) != null) return
        allowRules.insert(AllowRule(pattern = pattern.text, type = pattern.ruleType, note = note, createdAt = now()))
    }

    suspend fun updateBlockRule(rule: BlockRule) = blockRules.update(rule)

    suspend fun deleteBlockRule(rule: BlockRule) = blockRules.delete(rule)

    suspend fun deleteAllowRule(rule: AllowRule) = allowRules.delete(rule)

    /** Accepting turns the suggestion into a DERIVED prefix rule (SPEC §6.2). */
    suspend fun acceptSuggestion(suggestion: DerivedSuggestion, action: Action) {
        db.withTransaction {
            blockRules.insert(
                BlockRule(
                    pattern = "${suggestion.prefix}*", type = RuleType.PREFIX, action = action,
                    origin = RuleOrigin.DERIVED, createdAt = now(),
                    note = "From ${suggestion.sampleNumbers.size} blocked numbers",
                ),
            )
            db.suggestions().upsert(suggestion.copy(status = SuggestionStatus.ACCEPTED))
        }
    }

    suspend fun dismissSuggestion(suggestion: DerivedSuggestion) {
        db.suggestions().upsert(suggestion.copy(status = SuggestionStatus.DISMISSED))
    }

    /** First-run import (SPEC §7): adds numbers the system dialer blocked, skipping known ones. */
    suspend fun importBlocked(numbers: Collection<String>, action: Action = Action.REJECT): Int =
        db.withTransaction {
            val fresh = numbers.distinct().filter { blockRules.findExact(it) == null }
            blockRules.insertAll(
                fresh.map {
                    BlockRule(pattern = it, type = RuleType.EXACT, action = action, origin = RuleOrigin.IMPORT, createdAt = now())
                },
            )
            fresh.size
        }

    private fun now() = System.currentTimeMillis()
}

val CompiledPattern.ruleType: RuleType
    get() = when {
        this is CompiledPattern.Suffix -> RuleType.SUFFIX
        hasSingleDigitWildcard -> RuleType.WILDCARD
        this is CompiledPattern.Prefix -> RuleType.PREFIX
        else -> RuleType.EXACT
    }
