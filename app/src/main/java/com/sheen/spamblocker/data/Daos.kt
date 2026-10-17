package com.sheen.spamblocker.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import com.sheen.spamblocker.rules.Action
import com.sheen.spamblocker.screening.Decision
import kotlinx.coroutines.flow.Flow

@Dao
interface BlockRuleDao {
    @Query("SELECT * FROM BlockRule ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<BlockRule>>

    @Query("SELECT * FROM BlockRule")
    suspend fun getAll(): List<BlockRule>

    @Query("SELECT * FROM BlockRule WHERE type = 'EXACT' AND pattern = :e164 LIMIT 1")
    suspend fun findExact(e164: String): BlockRule?

    @Insert
    suspend fun insert(rule: BlockRule): Long

    @Insert
    suspend fun insertAll(rules: List<BlockRule>)

    @Update
    suspend fun update(rule: BlockRule)

    @Delete
    suspend fun delete(rule: BlockRule)

    @Query("DELETE FROM BlockRule WHERE type = 'EXACT' AND pattern = :e164")
    suspend fun deleteExact(e164: String)
}

@Dao
interface AllowRuleDao {
    @Query("SELECT * FROM AllowRule ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<AllowRule>>

    @Query("SELECT * FROM AllowRule")
    suspend fun getAll(): List<AllowRule>

    @Query("SELECT * FROM AllowRule WHERE pattern = :pattern LIMIT 1")
    suspend fun find(pattern: String): AllowRule?

    @Insert
    suspend fun insert(rule: AllowRule): Long

    @Delete
    suspend fun delete(rule: AllowRule)
}

@Dao
interface ListSourceDao {
    @Query("SELECT * FROM ListSource ORDER BY name")
    fun observeAll(): Flow<List<ListSource>>

    @Query("SELECT * FROM ListSource")
    suspend fun getAll(): List<ListSource>

    @Query("SELECT * FROM ListSource WHERE id = :id")
    suspend fun get(id: Long): ListSource?

    @Insert
    suspend fun insert(source: ListSource): Long

    @Update
    suspend fun update(source: ListSource)

    @Delete
    suspend fun delete(source: ListSource)
}

/** A list entry joined with its source's action, for building the rule index. */
data class IndexableListEntry(val value: String, val isPrefix: Boolean, val sourceId: Long, val action: Action)

@Dao
interface ListEntryDao {
    @Query(
        """SELECT e.value, e.isPrefix, e.sourceId, s.action FROM ListEntry e
           JOIN ListSource s ON s.id = e.sourceId WHERE s.enabled = 1""",
    )
    suspend fun getIndexable(): List<IndexableListEntry>

    @Query("DELETE FROM ListEntry WHERE sourceId = :sourceId")
    suspend fun deleteForSource(sourceId: Long)

    @Insert
    suspend fun insertAll(entries: List<ListEntry>)

    /** SPEC §8.3: delete, then insert, in one transaction. */
    @Transaction
    suspend fun replaceForSource(sourceId: Long, entries: List<ListEntry>) {
        deleteForSource(sourceId)
        entries.chunked(INSERT_CHUNK).forEach { insertAll(it) }
    }

    private companion object {
        const val INSERT_CHUNK = 5_000
    }
}

data class LatencySample(val decisionMicros: Long)

@Dao
interface CallEventDao {
    @Insert
    suspend fun insert(event: CallEvent): Long

    @Query("SELECT * FROM CallEvent ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<CallEvent>>

    @Query("SELECT * FROM CallEvent WHERE (:decision IS NULL OR decision = :decision) ORDER BY timestamp DESC")
    fun observeAll(decision: Decision?): Flow<List<CallEvent>>

    @Query("SELECT * FROM CallEvent WHERE timestamp >= :sinceMillis ORDER BY timestamp DESC")
    fun observeSince(sinceMillis: Long): Flow<List<CallEvent>>

    /** Repeat-caller check (SPEC §5, §10): one indexed query. */
    @Query("SELECT EXISTS(SELECT 1 FROM CallEvent WHERE number = :e164 AND timestamp >= :sinceMillis)")
    suspend fun existsSince(e164: String, sinceMillis: Long): Boolean

    @Query("SELECT * FROM CallEvent WHERE number = :e164 ORDER BY timestamp DESC LIMIT 1")
    suspend fun latestFor(e164: String): CallEvent?

    @Query("SELECT * FROM CallEvent WHERE timestamp >= :sinceMillis")
    suspend fun getSince(sinceMillis: Long): List<CallEvent>

    @Query("SELECT decisionMicros FROM CallEvent WHERE timestamp >= :sinceMillis")
    fun observeLatencies(sinceMillis: Long): Flow<List<LatencySample>>

    @Query("DELETE FROM CallEvent WHERE timestamp < :beforeMillis")
    suspend fun deleteOlderThan(beforeMillis: Long): Int
}

@Dao
interface DerivedSuggestionDao {
    @Query("SELECT * FROM DerivedSuggestion WHERE status = 'PENDING' ORDER BY createdAt DESC")
    fun observePending(): Flow<List<DerivedSuggestion>>

    @Query("SELECT * FROM DerivedSuggestion")
    suspend fun getAll(): List<DerivedSuggestion>

    @Query("SELECT * FROM DerivedSuggestion WHERE prefix = :prefix")
    suspend fun get(prefix: String): DerivedSuggestion?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(suggestions: List<DerivedSuggestion>)

    @Upsert
    suspend fun upsert(suggestion: DerivedSuggestion)

    /** Pending suggestions that are no longer derived (e.g. a number was unblocked). */
    @Query("DELETE FROM DerivedSuggestion WHERE status = 'PENDING' AND prefix NOT IN (:keep)")
    suspend fun deletePendingExcept(keep: List<String>)
}

data class SourceFalsePositives(val sourceId: Long, val count: Int)

@Dao
interface FalsePositiveDao {
    @Insert
    suspend fun insert(fp: FalsePositive)

    @Query("SELECT sourceId, COUNT(*) AS count FROM FalsePositive WHERE sourceId IS NOT NULL GROUP BY sourceId")
    fun observeBySource(): Flow<List<SourceFalsePositives>>
}
