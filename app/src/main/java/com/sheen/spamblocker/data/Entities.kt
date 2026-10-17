package com.sheen.spamblocker.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.sheen.spamblocker.lists.ListFormat
import com.sheen.spamblocker.rules.Action
import com.sheen.spamblocker.screening.Decision
import com.sheen.spamblocker.screening.Stage

// SPEC §9.1

enum class RuleType { EXACT, PREFIX, WILDCARD, SUFFIX }

enum class RuleOrigin { USER, IMPORT, MANUAL_PATTERN, DERIVED }

enum class SuggestionStatus { PENDING, ACCEPTED, DISMISSED }

@Entity(indices = [Index("pattern")])
data class BlockRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** E.164 for EXACT, otherwise the canonical wildcard text (`+9198765*`, `*4321`). */
    val pattern: String,
    val type: RuleType,
    val action: Action,
    val origin: RuleOrigin,
    val enabled: Boolean = true,
    val note: String? = null,
    val createdAt: Long,
)

@Entity(indices = [Index("pattern")])
data class AllowRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pattern: String,
    /** EXACT or PREFIX. */
    val type: RuleType,
    val note: String? = null,
    val createdAt: Long,
)

@Entity
data class ListSource(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val url: String,
    val format: ListFormat,
    /** CSV column: a header name or a 1-based column number; null = first column. */
    val csvColumn: String? = null,
    val action: Action = Action.SILENCE,
    val enabled: Boolean = true,
    val etag: String? = null,
    val lastModified: String? = null,
    val lastFetchedAt: Long? = null,
    val entryCount: Int = 0,
    val lastError: String? = null,
)

@Entity(
    indices = [Index("value"), Index("sourceId")],
    foreignKeys = [
        ForeignKey(
            entity = ListSource::class,
            parentColumns = ["id"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ListEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: Long,
    /** E.164 number, or an E.164 prefix without `*` when [isPrefix]. */
    val value: String,
    val isPrefix: Boolean,
)

@Entity(indices = [Index("number"), Index("timestamp")])
data class CallEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** E.164; null = hidden. */
    val number: String?,
    val timestamp: Long,
    val decision: Decision,
    val stage: Stage,
    val ruleId: Long? = null,
    val sourceId: Long? = null,
    val decisionMicros: Long,
    /** libphonenumber couldn't parse the handle (SPEC §4). */
    val unparsed: Boolean = false,
)

@Entity
data class DerivedSuggestion(
    /** E.164 prefix without `*`, e.g. `+9198765432`. */
    @PrimaryKey val prefix: String,
    val sampleNumbers: List<String>,
    val rangeSize: Long,
    val status: SuggestionStatus,
    val createdAt: Long,
)

/**
 * A "Not spam" on a number that a remote list or derived prefix had blocked
 * (SPEC §7, history reconciliation).
 */
@Entity(indices = [Index("sourceId")])
data class FalsePositive(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val number: String,
    val stage: Stage,
    val ruleId: Long? = null,
    val sourceId: Long? = null,
    val createdAt: Long,
)
