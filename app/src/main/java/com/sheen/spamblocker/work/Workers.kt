package com.sheen.spamblocker.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.sheen.spamblocker.calllog.PostCallPrompter
import com.sheen.spamblocker.data.CallEventDao
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/** Daily: prune old history (SPEC §9.1) and re-derive prefix suggestions (SPEC §6.2). */
@HiltWorker
class MaintenanceWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val callEvents: CallEventDao,
    private val suggestions: SuggestionRefresher,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        callEvents.deleteOlderThan(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(HISTORY_DAYS))
        suggestions.refresh()
        return Result.success()
    }

    private companion object {
        const val HISTORY_DAYS = 90L
    }
}

/** Every 15 minutes: the fallback for post-call prompts when the observer wasn't running (SPEC §7). */
@HiltWorker
class PostCallCheckWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val prompter: PostCallPrompter,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        prompter.check()
        return Result.success()
    }
}
