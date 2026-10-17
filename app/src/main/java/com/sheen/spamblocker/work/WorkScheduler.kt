package com.sheen.spamblocker.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.sheen.spamblocker.data.RefreshInterval
import com.sheen.spamblocker.lists.ListRefreshWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkScheduler @Inject constructor(@param:ApplicationContext private val context: Context) {
    private val wm get() = WorkManager.getInstance(context)

    /** SPEC §8.3: periodic refresh of all enabled sources, or none when MANUAL. */
    fun scheduleListRefresh(interval: RefreshInterval, unmeteredOnly: Boolean) {
        val hours = interval.hours
        if (hours == null) {
            wm.cancelUniqueWork(LIST_REFRESH)
            return
        }
        val request = PeriodicWorkRequestBuilder<ListRefreshWorker>(hours, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (unmeteredOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniquePeriodicWork(LIST_REFRESH, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** "Refresh now" for one source; ignores the unmetered setting since the user asked. */
    fun refreshNow(sourceId: Long) {
        val request = OneTimeWorkRequestBuilder<ListRefreshWorker>()
            .setInputData(workDataOf(ListRefreshWorker.KEY_SOURCE_ID to sourceId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniqueWork("$REFRESH_ONE$sourceId", ExistingWorkPolicy.REPLACE, request)
    }

    fun refreshStatus(sourceId: Long) = wm.getWorkInfosForUniqueWorkFlow("$REFRESH_ONE$sourceId")

    fun schedulePeriodicJobs() {
        wm.enqueueUniquePeriodicWork(
            MAINTENANCE,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<MaintenanceWorker>(1, TimeUnit.DAYS).build(),
        )
        wm.enqueueUniquePeriodicWork(
            POST_CALL_CHECK,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<PostCallCheckWorker>(15, TimeUnit.MINUTES).build(),
        )
    }

    private companion object {
        const val LIST_REFRESH = "list-refresh"
        const val REFRESH_ONE = "list-refresh-"
        const val MAINTENANCE = "maintenance"
        const val POST_CALL_CHECK = "post-call-check"
        const val BACKOFF_MINUTES = 10L
    }
}
