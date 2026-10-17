package com.sheen.spamblocker.lists

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.sheen.spamblocker.data.ListSourceDao
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

/**
 * Refreshes one source ([KEY_SOURCE_ID]) or every enabled source (SPEC §8.3).
 * Network errors retry with WorkManager's exponential backoff.
 */
@HiltWorker
class ListRefreshWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val sources: ListSourceDao,
    private val refresher: ListRefresher,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val only = inputData.getLong(KEY_SOURCE_ID, -1L).takeIf { it >= 0 }
        val ids = if (only != null) listOf(only) else sources.getAll().filter { it.enabled }.map { it.id }
        // One failing source must not stop the others from refreshing.
        val outcomes = ids.map { id ->
            try {
                refresher.refresh(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Refreshing source $id failed", e)
                RefreshOutcome.FAILED
            }
        }
        val shouldRetry = RefreshOutcome.RETRYABLE_FAILURE in outcomes && runAttemptCount < MAX_ATTEMPTS
        return if (shouldRetry) Result.retry() else Result.success()
    }

    companion object {
        const val KEY_SOURCE_ID = "sourceId"
        private const val TAG = "ListRefreshWorker"
        private const val MAX_ATTEMPTS = 5
    }
}
