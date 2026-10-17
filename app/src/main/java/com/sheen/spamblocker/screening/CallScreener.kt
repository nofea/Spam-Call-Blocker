package com.sheen.spamblocker.screening

import android.util.Log
import com.sheen.spamblocker.calllog.CallLogReader
import com.sheen.spamblocker.data.CallEvent
import com.sheen.spamblocker.data.CallEventDao
import com.sheen.spamblocker.data.RuleIndexHolder
import com.sheen.spamblocker.data.SettingsRepository
import com.sheen.spamblocker.data.ListSourceDao
import com.sheen.spamblocker.di.AppScope
import com.sheen.spamblocker.notify.Notifier
import com.sheen.spamblocker.phone.NormalizedNumber
import com.sheen.spamblocker.phone.PhoneNormalizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Screens one incoming call: decide, then record and notify in the
 * background. Independent of `Call.Details`, so tests can drive it directly
 * (SPEC §13.2).
 */
@Singleton
class CallScreener @Inject constructor(
    private val normalizer: PhoneNormalizer,
    private val indexHolder: RuleIndexHolder,
    private val settings: SettingsRepository,
    private val callEvents: CallEventDao,
    private val sources: ListSourceDao,
    private val callLog: CallLogReader,
    private val notifier: Notifier,
    @param:AppScope private val scope: CoroutineScope,
) {
    private val engine = DecisionEngine(
        contacts = callLog::isContact,
        recentOutgoing = callLog::calledSince,
        recentEvents = callEvents::existsSince,
    )

    /**
     * Returns the decision without waiting for the event write or
     * notifications. Never throws: on any failure the call is allowed.
     */
    suspend fun screen(handle: String?, presentation: Presentation, timestampMillis: Long): ScreeningDecision {
        val startNanos = System.nanoTime()
        val number = normalizer.normalize(handle)
        val call = CallContext(number, presentation, timestampMillis)

        val decision = try {
            withTimeoutOrNull(DECISION_TIMEOUT_MILLIS) {
                val index = indexHolder.getOrLoad()
                    ?: return@withTimeoutOrNull ScreeningDecision.allow(Stage.INDEX_UNAVAILABLE)
                engine.decide(call, index, settings.current().toScreeningSettings())
            } ?: ScreeningDecision.allow(Stage.INDEX_UNAVAILABLE)
        } catch (e: Exception) {
            Log.e(TAG, "Screening failed; allowing the call", e)
            ScreeningDecision.allow(Stage.INDEX_UNAVAILABLE)
        }
        val micros = (System.nanoTime() - startNanos) / 1_000

        scope.launch { record(call, decision, micros) }
        return decision
    }

    private suspend fun record(call: CallContext, decision: ScreeningDecision, micros: Long) {
        callEvents.insert(
            CallEvent(
                number = call.e164,
                timestamp = call.timestampMillis,
                decision = decision.decision,
                stage = decision.stage,
                ruleId = decision.ruleId,
                sourceId = decision.sourceId,
                decisionMicros = micros,
                unparsed = call.number is NormalizedNumber.Unparsed,
            ),
        )
        when (decision.decision) {
            Decision.WARN -> call.e164?.let { notifier.showWarning(it, reason(decision)) }
            Decision.REJECT, Decision.SILENCE -> if (settings.current().notifyOnBlock) notifier.updateBlockedSummary()
            Decision.ALLOW -> Unit
        }
    }

    private suspend fun reason(decision: ScreeningDecision): String =
        decision.sourceId?.let { sources.get(it)?.name } ?: decision.stage.label

    private companion object {
        const val TAG = "CallScreener"

        /** Far below the system's ~5 s limit (SPEC §2.1). */
        const val DECISION_TIMEOUT_MILLIS = 4_000L
    }
}
