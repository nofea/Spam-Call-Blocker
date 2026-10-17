package com.sheen.spamblocker.rules

/** What a matching block rule does to the call (SPEC §2.2). */
enum class Action { REJECT, SILENCE, WARN }
