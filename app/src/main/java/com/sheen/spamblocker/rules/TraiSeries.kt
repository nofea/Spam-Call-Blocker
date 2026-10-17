package com.sheen.spamblocker.rules

/**
 * TRAI number-series allocations (SPEC §4), matched against the national
 * significant number. Kept in one place so they can be updated if TRAI
 * changes the allocation.
 */
object TraiSeries {
    /** Promotional telemarketers. */
    val PROMOTIONAL_PREFIXES: Set<String> = setOf("140")

    /** Transactional and service calls (banks, government, regulated entities). */
    val TRANSACTIONAL_PREFIXES: Set<String> = setOf("160")

    fun isPromotional(nationalNumber: String): Boolean =
        PROMOTIONAL_PREFIXES.any(nationalNumber::startsWith)

    fun isTransactional(nationalNumber: String): Boolean =
        TRANSACTIONAL_PREFIXES.any(nationalNumber::startsWith)
}
