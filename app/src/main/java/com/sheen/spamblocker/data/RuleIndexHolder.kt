package com.sheen.spamblocker.data

import android.util.Log
import com.sheen.spamblocker.di.AppScope
import com.sheen.spamblocker.rules.CompiledPattern
import com.sheen.spamblocker.rules.IndexEntry
import com.sheen.spamblocker.rules.RuleIndex
import com.sheen.spamblocker.rules.RuleMatch
import com.sheen.spamblocker.rules.WildcardCompiler
import com.sheen.spamblocker.screening.Stage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the current [RuleIndex] (SPEC §10). Rebuilds are triggered by Room
 * invalidations of the rule tables, debounced by 500 ms, built on
 * [Dispatchers.Default] and swapped in atomically, so screening never waits
 * for a rebuild.
 */
@Singleton
class RuleIndexHolder @Inject constructor(
    private val db: SpamBlockerDatabase,
    private val compiler: WildcardCompiler,
    @param:AppScope private val scope: CoroutineScope,
) {
    private val current = AtomicReference<RuleIndex?>(null)
    private val started = AtomicBoolean(false)
    private val buildLock = Mutex()

    /** The index if it has loaded, without waiting. */
    fun peek(): RuleIndex? = current.get()

    /** Starts watching the rule tables. Safe to call more than once. */
    @OptIn(FlowPreview::class)
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            // createFlow emits once immediately, which performs the initial load.
            db.invalidationTracker.createFlow(*SpamBlockerDatabase.RULE_TABLES)
                .debounce(REBUILD_DEBOUNCE_MILLIS)
                .collect { rebuild() }
        }
    }

    /**
     * Cold start (SPEC §10): returns the current index, or loads it now with
     * a timeout. Null means it couldn't be loaded in time.
     */
    suspend fun getOrLoad(timeoutMillis: Long = COLD_START_TIMEOUT_MILLIS): RuleIndex? {
        start()
        current.get()?.let { return it }
        return withTimeoutOrNull(timeoutMillis) { current.get() ?: rebuild() }
    }

    private suspend fun rebuild(): RuleIndex = buildLock.withLock {
        withContext(Dispatchers.Default) {
            val startNanos = System.nanoTime()
            val index = build()
            current.set(index)
            Log.d(TAG, "RuleIndex: ${index.blockEntryCount} block / ${index.allowEntryCount} allow entries " +
                "in ${(System.nanoTime() - startNanos) / 1_000_000} ms")
            index
        }
    }

    private suspend fun build(): RuleIndex {
        val blockRules = db.blockRules().getAll()
        val allowRules = db.allowRules().getAll()
        val listEntries = db.listEntries().getIndexable()

        val block = ArrayList<IndexEntry>(blockRules.size + listEntries.size)
        for (rule in blockRules) {
            if (!rule.enabled) continue
            val pattern = compiler.compileOrNull(rule.pattern) ?: continue
            block += IndexEntry(pattern, RuleMatch(stageFor(rule), rule.action, ruleId = rule.id))
        }
        for (e in listEntries) {
            val digits = e.value.removePrefix("+")
            val pattern = if (e.isPrefix) CompiledPattern.Prefix(digits) else CompiledPattern.Exact(digits)
            block += IndexEntry(pattern, RuleMatch(Stage.REMOTE_LIST, e.action, sourceId = e.sourceId))
        }
        val allow = allowRules.mapNotNull { rule ->
            compiler.compileOrNull(rule.pattern)?.let { IndexEntry(it, RuleMatch(Stage.ALLOWLIST, null, ruleId = rule.id)) }
        }
        return RuleIndex.build(block, allow)
    }

    companion object {
        private const val TAG = "RuleIndexHolder"
        const val REBUILD_DEBOUNCE_MILLIS = 500L
        const val COLD_START_TIMEOUT_MILLIS = 2_000L

        /** Which pipeline stage (SPEC §5) a block rule belongs to. */
        fun stageFor(rule: BlockRule): Stage = when {
            rule.origin == RuleOrigin.DERIVED -> Stage.DERIVED_PREFIX
            rule.type == RuleType.EXACT -> Stage.USER_BLOCK
            else -> Stage.PATTERN
        }
    }
}
