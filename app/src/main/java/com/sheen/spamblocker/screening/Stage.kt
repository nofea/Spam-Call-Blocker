package com.sheen.spamblocker.screening

import com.sheen.spamblocker.rules.Action

/**
 * Pipeline stages in evaluation order (SPEC §5). The declaration order is the
 * priority order: when several block rules match, the earliest stage wins.
 */
enum class Stage(val label: String) {
    CONTACT("Saved contact"),
    ALLOWLIST("Allowlist"),
    USER_BLOCK("Block list"),
    TRAI_160("TRAI 160-series"),
    RECENT_OUTGOING("Recently called"),
    REPEAT_CALLER("Repeat caller"),
    PATTERN("Pattern rule"),
    DERIVED_PREFIX("Derived prefix"),
    REMOTE_LIST("Remote list"),
    TRAI_140("TRAI 140-series"),
    HIDDEN("Hidden caller"),
    INTERNATIONAL("International"),
    DEFAULT("No rule matched"),

    /** The rule index couldn't be loaded in time, so the call was allowed (SPEC §10). */
    INDEX_UNAVAILABLE("Rules not loaded"),
}

/** The outcome recorded on every `CallEvent`. */
enum class Decision { ALLOW, WARN, SILENCE, REJECT }

fun Action.toDecision(): Decision = when (this) {
    Action.REJECT -> Decision.REJECT
    Action.SILENCE -> Decision.SILENCE
    Action.WARN -> Decision.WARN
}
