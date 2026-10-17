package com.sheen.spamblocker.screening

import com.sheen.spamblocker.phone.PhoneNormalizer
import com.sheen.spamblocker.rules.Action
import com.sheen.spamblocker.rules.IndexEntry
import com.sheen.spamblocker.rules.RuleIndex
import com.sheen.spamblocker.rules.RuleMatch
import com.sheen.spamblocker.rules.WildcardCompiler
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class DecisionEngineTest {
    private val normalizer = PhoneNormalizer()
    private val compiler = WildcardCompiler(normalizer)
    private val now = 1_800_000_000_000L

    private val contact = "+919000000001"
    private val allowlisted = "+919000000002"
    private val calledYesterday = "+919000000003"
    private val calledLastMonth = "+919000000004"
    private val repeatCaller = "+919000000005"
    private val userBlocked = "+919000000006"
    private val remoteListed = "+919000000007"
    private val blockedButCalled = "+919000000008"
    private val blockedRepeatCaller = "+919000000009"
    private val blockedContact = "+919000000010"
    private val unknown = "+919000000099"

    private val index = RuleIndex.build(
        blockEntries = listOf(
            entry(userBlocked, Stage.USER_BLOCK, Action.REJECT, ruleId = 1),
            entry("+9188888*", Stage.PATTERN, Action.WARN, ruleId = 2),
            entry("+9177777777*", Stage.DERIVED_PREFIX, Action.SILENCE, ruleId = 3),
            entry(remoteListed, Stage.REMOTE_LIST, Action.SILENCE, sourceId = 10),
            // Also on block lists, to check that allow stages take precedence.
            entry(allowlisted, Stage.USER_BLOCK, Action.REJECT, ruleId = 4),
            entry(calledYesterday, Stage.REMOTE_LIST, Action.SILENCE, sourceId = 10),
            entry(contact, Stage.REMOTE_LIST, Action.SILENCE, sourceId = 10),
            // On the user block list, to check it beats the safety-net allows.
            entry(blockedButCalled, Stage.USER_BLOCK, Action.REJECT, ruleId = 5),
            entry(blockedRepeatCaller, Stage.USER_BLOCK, Action.SILENCE, ruleId = 6),
            entry("+911600123457", Stage.USER_BLOCK, Action.REJECT, ruleId = 7),
            entry(blockedContact, Stage.USER_BLOCK, Action.REJECT, ruleId = 8),
            // A remote list and a user block both match: the user block's action wins.
            entry(blockedButCalled, Stage.REMOTE_LIST, Action.SILENCE, sourceId = 10),
        ),
        allowEntries = listOf(entry(allowlisted, Stage.ALLOWLIST, null, ruleId = 20)),
    )

    private fun entry(pattern: String, stage: Stage, action: Action?, ruleId: Long? = null, sourceId: Long? = null) =
        IndexEntry(compiler.compile(pattern), RuleMatch(stage, action, ruleId, sourceId))

    private val engine = DecisionEngine(
        contacts = { it == contact || it == blockedContact },
        recentOutgoing = { number, since ->
            (number in listOf(calledYesterday, blockedButCalled) && since <= now - DAY) ||
                (number == calledLastMonth && since <= now - 30 * DAY)
        },
        recentEvents = { number, since ->
            listOf("+911400000000", repeatCaller, blockedRepeatCaller).contains(number) && since <= now - 2 * MINUTE
        },
    )

    private fun call(raw: String?, presentation: Presentation = Presentation.ALLOWED) =
        CallContext(normalizer.normalize(raw), presentation, now)

    private data class Case(
        val name: String,
        val raw: String?,
        val expected: ScreeningDecision,
        val presentation: Presentation = Presentation.ALLOWED,
        val settings: ScreeningSettings = ScreeningSettings(),
    )

    private val cases = listOf(
        Case("1 contact, even when listed", contact, ScreeningDecision.allow(Stage.CONTACT)),
        Case("2 allowlist beats block list", allowlisted, ScreeningDecision(Decision.ALLOW, Stage.ALLOWLIST, ruleId = 20)),
        Case("1 contact beats user block", blockedContact, ScreeningDecision.allow(Stage.CONTACT)),
        Case("3 user block", userBlocked, ScreeningDecision(Decision.REJECT, Stage.USER_BLOCK, ruleId = 1)),
        Case(
            "3 user block beats recent outgoing and remote list",
            blockedButCalled,
            ScreeningDecision(Decision.REJECT, Stage.USER_BLOCK, ruleId = 5),
        ),
        Case(
            "3 user block beats repeat caller",
            blockedRepeatCaller,
            ScreeningDecision(Decision.SILENCE, Stage.USER_BLOCK, ruleId = 6),
        ),
        Case("3 user block beats TRAI 160", "1600123457", ScreeningDecision(Decision.REJECT, Stage.USER_BLOCK, ruleId = 7)),
        Case("4 TRAI 160", "1600123456", ScreeningDecision.allow(Stage.TRAI_160)),
        Case(
            "4 TRAI 160 off falls through",
            "1600123456",
            ScreeningDecision.allow(Stage.DEFAULT),
            settings = ScreeningSettings(trai160Allow = false),
        ),
        Case("5 called recently beats remote list", calledYesterday, ScreeningDecision.allow(Stage.RECENT_OUTGOING)),
        Case("5 called outside window", calledLastMonth, ScreeningDecision.allow(Stage.DEFAULT)),
        Case(
            "5 recent outgoing off",
            calledYesterday,
            ScreeningDecision(Decision.SILENCE, Stage.REMOTE_LIST, sourceId = 10),
            settings = ScreeningSettings(recentOutgoingDays = 0),
        ),
        Case("6 repeat caller", repeatCaller, ScreeningDecision.allow(Stage.REPEAT_CALLER)),
        Case("6 repeat caller in 140-series is allowed", "1400000000", ScreeningDecision.allow(Stage.REPEAT_CALLER)),
        Case(
            "6 repeat caller off",
            repeatCaller,
            ScreeningDecision.allow(Stage.DEFAULT),
            settings = ScreeningSettings(repeatCallerMinutes = 0),
        ),
        Case("7 pattern", "8888812345", ScreeningDecision(Decision.WARN, Stage.PATTERN, ruleId = 2)),
        Case("8 derived prefix", "7777777712", ScreeningDecision(Decision.SILENCE, Stage.DERIVED_PREFIX, ruleId = 3)),
        Case("9 remote list", remoteListed, ScreeningDecision(Decision.SILENCE, Stage.REMOTE_LIST, sourceId = 10)),
        Case("10 TRAI 140", "+91 140 123 4567", ScreeningDecision(Decision.REJECT, Stage.TRAI_140)),
        Case(
            "10 TRAI 140 custom action",
            "1401234567",
            ScreeningDecision(Decision.SILENCE, Stage.TRAI_140),
            settings = ScreeningSettings(trai140Action = Action.SILENCE),
        ),
        Case(
            "10 TRAI 140 off",
            "1401234567",
            ScreeningDecision.allow(Stage.DEFAULT),
            settings = ScreeningSettings(trai140Enabled = false),
        ),
        Case("11 hidden: null handle", null, ScreeningDecision(Decision.SILENCE, Stage.HIDDEN)),
        Case(
            "11 hidden: restricted presentation skips allow stages",
            contact,
            ScreeningDecision(Decision.SILENCE, Stage.HIDDEN),
            presentation = Presentation.RESTRICTED,
        ),
        Case("11 hidden: payphone", "", ScreeningDecision(Decision.SILENCE, Stage.HIDDEN), Presentation.PAYPHONE),
        Case(
            "11 hidden off",
            null,
            ScreeningDecision.allow(Stage.DEFAULT),
            settings = ScreeningSettings(blockHidden = false),
        ),
        Case("12 international", "+1 650 253 0000", ScreeningDecision(Decision.SILENCE, Stage.INTERNATIONAL)),
        Case(
            "12 international off",
            "+1 650 253 0000",
            ScreeningDecision.allow(Stage.DEFAULT),
            settings = ScreeningSettings(blockInternational = false),
        ),
        Case("13 default", unknown, ScreeningDecision.allow(Stage.DEFAULT)),
    )

    @Test
    fun `pipeline table`() = runTest {
        for (c in cases) {
            val actual = engine.decide(call(c.raw, c.presentation), index, c.settings)
            assertEquals(c.name, c.expected, actual)
        }
    }

    private companion object {
        const val MINUTE = 60_000L
        const val DAY = 24 * 60 * MINUTE
    }
}
