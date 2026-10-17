package com.sheen.spamblocker.screening

import com.sheen.spamblocker.phone.NormalizedNumber
import com.sheen.spamblocker.rules.Action
import com.sheen.spamblocker.rules.RuleIndex
import com.sheen.spamblocker.rules.TraiSeries
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** How the caller's number was presented (mirrors `TelecomManager.PRESENTATION_*`). */
enum class Presentation { ALLOWED, RESTRICTED, UNKNOWN, PAYPHONE }

data class CallContext(
    val number: NormalizedNumber,
    val presentation: Presentation,
    val timestampMillis: Long,
) {
    /** SPEC §4: no usable number, or the network says it's withheld. */
    val isHidden: Boolean
        get() = number is NormalizedNumber.Hidden || presentation != Presentation.ALLOWED

    /** E.164 form, or null when hidden. */
    val e164: String?
        get() = if (isHidden) null else when (val n = number) {
            is NormalizedNumber.Parsed -> n.e164
            is NormalizedNumber.Unparsed -> n.e164
            NormalizedNumber.Hidden -> null
        }
}

/** Android-backed lookups, as interfaces so tests can fake them (SPEC §5). */
fun interface ContactLookup {
    suspend fun isContact(e164: String): Boolean
}

fun interface RecentOutgoingLookup {
    /** Whether the user called [e164] at or after [sinceMillis]. */
    suspend fun calledSince(e164: String, sinceMillis: Long): Boolean
}

fun interface RecentEventsLookup {
    /** Whether the app has screened a call from [e164] at or after [sinceMillis]. */
    suspend fun screenedSince(e164: String, sinceMillis: Long): Boolean
}

/** The subset of settings (SPEC §9.2) the pipeline reads. */
data class ScreeningSettings(
    val blockHidden: Boolean = true,
    val hiddenAction: Action = Action.SILENCE,
    val blockInternational: Boolean = true,
    val internationalAction: Action = Action.SILENCE,
    val trai140Enabled: Boolean = true,
    val trai140Action: Action = Action.REJECT,
    val trai160Allow: Boolean = true,
    /** 0 = off. */
    val recentOutgoingDays: Int = 7,
    /** 0 = off. */
    val repeatCallerMinutes: Int = 5,
)

data class ScreeningDecision(
    val decision: Decision,
    val stage: Stage,
    val ruleId: Long? = null,
    val sourceId: Long? = null,
) {
    companion object {
        fun allow(stage: Stage) = ScreeningDecision(Decision.ALLOW, stage)
    }
}

/**
 * The ordered pipeline from SPEC §5: the first matching stage wins, and allow
 * stages come before block stages. Pure Kotlin; the I/O lookups are started
 * concurrently up front so the slowest one, not their sum, bounds the latency.
 */
class DecisionEngine(
    private val contacts: ContactLookup,
    private val recentOutgoing: RecentOutgoingLookup,
    private val recentEvents: RecentEventsLookup,
) {
    suspend fun decide(call: CallContext, index: RuleIndex, settings: ScreeningSettings): ScreeningDecision {
        val number = call.e164 ?: return decideHidden(settings)
        val parsed = call.number as? NormalizedNumber.Parsed
        val indianNsn = parsed?.takeIf { it.isIndian }?.nationalNumber

        return coroutineScope {
            val isContact = async { contacts.isContact(number) }
            val calledRecently: Deferred<Boolean>? = settings.recentOutgoingDays.takeIf { it > 0 }?.let { days ->
                async { recentOutgoing.calledSince(number, call.timestampMillis - days * DAY_MILLIS) }
            }
            val repeatCaller: Deferred<Boolean>? = settings.repeatCallerMinutes.takeIf { it > 0 }?.let { minutes ->
                async { recentEvents.screenedSince(number, call.timestampMillis - minutes * MINUTE_MILLIS) }
            }

            // 1–5: allow stages.
            if (isContact.await()) return@coroutineScope allow(Stage.CONTACT)
            index.allowMatch(number)?.let {
                return@coroutineScope ScreeningDecision(Decision.ALLOW, Stage.ALLOWLIST, ruleId = it.ruleId)
            }
            if (settings.trai160Allow && indianNsn != null && TraiSeries.isTransactional(indianNsn)) {
                return@coroutineScope allow(Stage.TRAI_160)
            }
            if (calledRecently?.await() == true) return@coroutineScope allow(Stage.RECENT_OUTGOING)
            if (repeatCaller?.await() == true) return@coroutineScope allow(Stage.REPEAT_CALLER)

            // 6–9: user block list, patterns, derived prefixes, remote lists.
            index.blockMatch(number)?.let { m ->
                return@coroutineScope ScreeningDecision(
                    decision = (m.action ?: Action.SILENCE).toDecision(),
                    stage = m.stage,
                    ruleId = m.ruleId,
                    sourceId = m.sourceId,
                )
            }

            // 10: TRAI 140-series.
            if (settings.trai140Enabled && indianNsn != null && TraiSeries.isPromotional(indianNsn)) {
                return@coroutineScope ScreeningDecision(settings.trai140Action.toDecision(), Stage.TRAI_140)
            }

            // 12: international (11, hidden, was handled before the scope).
            if (settings.blockInternational && parsed != null && !parsed.isIndian) {
                return@coroutineScope ScreeningDecision(settings.internationalAction.toDecision(), Stage.INTERNATIONAL)
            }

            allow(Stage.DEFAULT)
        }
    }

    /** Hidden callers skip stages 1–10 (SPEC §5). */
    private fun decideHidden(settings: ScreeningSettings): ScreeningDecision =
        if (settings.blockHidden) {
            ScreeningDecision(settings.hiddenAction.toDecision(), Stage.HIDDEN)
        } else {
            allow(Stage.DEFAULT)
        }

    private fun allow(stage: Stage) = ScreeningDecision.allow(stage)

    private companion object {
        const val MINUTE_MILLIS = 60_000L
        const val DAY_MILLIS = 24 * 60 * MINUTE_MILLIS
    }
}
