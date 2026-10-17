package com.sheen.spamblocker.lists

import androidx.room.withTransaction
import com.sheen.spamblocker.data.ListEntry
import com.sheen.spamblocker.data.SpamBlockerDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

enum class RefreshOutcome { UPDATED, NOT_MODIFIED, REJECTED, RETRYABLE_FAILURE, FAILED }

/**
 * Fetch → parse → replace for one source (SPEC §8.3). On any failure the last
 * good entries stay and the error goes to `lastError`. The rule index rebuilds
 * itself from the table change.
 */
class ListRefresher @Inject constructor(
    private val db: SpamBlockerDatabase,
    private val fetcher: ListFetcher,
    private val parser: ListParser,
) {
    suspend fun refresh(sourceId: Long): RefreshOutcome {
        val sources = db.listSources()
        val source = sources.get(sourceId) ?: return RefreshOutcome.FAILED
        val now = System.currentTimeMillis()

        return when (val fetched = fetcher.fetch(source)) {
            FetchResult.NotModified -> {
                sources.update(source.copy(lastFetchedAt = now, lastError = null))
                RefreshOutcome.NOT_MODIFIED
            }
            is FetchResult.Failure -> {
                sources.update(source.copy(lastError = fetched.message))
                if (fetched.retryable) RefreshOutcome.RETRYABLE_FAILURE else RefreshOutcome.FAILED
            }
            is FetchResult.Body -> {
                val parsed = withContext(Dispatchers.Default) {
                    parser.parse(fetched.text, source.format, source.csvColumn)
                }
                when (parsed) {
                    is ParseResult.Rejected -> {
                        sources.update(source.copy(lastError = "Rejected: ${parsed.reason}"))
                        RefreshOutcome.REJECTED
                    }
                    is ParseResult.Success -> {
                        val entries = parsed.entries.map { ListEntry(sourceId = source.id, value = it.value, isPrefix = it.isPrefix) }
                        db.withTransaction {
                            db.listEntries().replaceForSource(source.id, entries)
                            sources.update(
                                source.copy(
                                    etag = fetched.etag,
                                    lastModified = fetched.lastModified,
                                    lastFetchedAt = now,
                                    entryCount = entries.size,
                                    lastError = parsed.failedLines.takeIf { it > 0 }
                                        ?.let { "Skipped $it of ${parsed.totalLines} lines" },
                                ),
                            )
                        }
                        RefreshOutcome.UPDATED
                    }
                }
            }
        }
    }
}
