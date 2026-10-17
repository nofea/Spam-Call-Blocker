package com.sheen.spamblocker

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.sheen.spamblocker.calllog.PostCallPrompter
import com.sheen.spamblocker.data.BlockRuleDao
import com.sheen.spamblocker.data.RuleIndexHolder
import com.sheen.spamblocker.data.SettingsRepository
import com.sheen.spamblocker.di.AppScope
import com.sheen.spamblocker.notify.Notifier
import com.sheen.spamblocker.work.SuggestionRefresher
import com.sheen.spamblocker.work.WorkScheduler
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class SpamBlockerApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var indexHolder: RuleIndexHolder
    @Inject lateinit var notifier: Notifier
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var blockRules: BlockRuleDao
    @Inject lateinit var suggestions: SuggestionRefresher
    @Inject lateinit var scheduler: WorkScheduler
    @Inject lateinit var postCallPrompter: PostCallPrompter
    @Inject @AppScope lateinit var scope: CoroutineScope

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    @OptIn(FlowPreview::class)
    override fun onCreate() {
        super.onCreate()
        notifier.createChannels()
        // Load the rule index now so the first screened call doesn't wait for it (SPEC §10).
        indexHolder.start()
        scheduler.schedulePeriodicJobs()
        postCallPrompter.registerObserver()

        scope.launch {
            settings.settings
                .map { it.listRefreshInterval to it.listRefreshUnmeteredOnly }
                .distinctUntilChanged()
                .collect { (interval, unmetered) -> scheduler.scheduleListRefresh(interval, unmetered) }
        }
        // SPEC §6.2: re-derive suggestions after block-list changes (debounced).
        scope.launch {
            blockRules.observeAll()
                .drop(1)
                .debounce(DERIVE_DEBOUNCE_MILLIS)
                .collect { suggestions.refresh() }
        }
    }

    private companion object {
        const val DERIVE_DEBOUNCE_MILLIS = 2_000L
    }
}
