package com.sheen.spamblocker.calllog

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import com.sheen.spamblocker.data.CallEventDao
import com.sheen.spamblocker.data.SettingsRepository
import com.sheen.spamblocker.di.AppScope
import com.sheen.spamblocker.notify.Notifier
import com.sheen.spamblocker.screening.Decision
import com.sheen.spamblocker.screening.Stage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Post-call "Block <number>?" prompt (SPEC §7) for unknown numbers the app
 * allowed. Driven by a call-log [ContentObserver] while the process is alive,
 * with a 15-minute WorkManager fallback calling [check].
 */
@Singleton
class PostCallPrompter @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val callLog: CallLogReader,
    private val callEvents: CallEventDao,
    private val settings: SettingsRepository,
    private val notifier: Notifier,
    @param:AppScope private val scope: CoroutineScope,
) {
    private val lock = Mutex()
    private var observer: ContentObserver? = null

    fun registerObserver() {
        if (observer != null || !Permissions.canReadCallLog(context)) return
        observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                scope.launch { check() }
            }
        }.also {
            context.contentResolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, it)
        }
    }

    suspend fun check() = lock.withLock {
        val s = settings.current()
        val calls = callLog.answeredOrMissedAfter(s.lastPromptedCallId, System.currentTimeMillis() - MAX_AGE_MILLIS)
        calls.maxOfOrNull { it.id }?.let { newest -> settings.update { it.copy(lastPromptedCallId = newest) } }
        if (!s.postCallPrompt) return@withLock

        for (call in calls) {
            val number = call.e164 ?: continue
            if (call.cachedName != null || callLog.isContact(number)) continue
            val event = callEvents.latestFor(number) ?: continue
            val sameCall = abs(event.timestamp - call.dateMillis) <= MATCH_WINDOW_MILLIS
            if (sameCall && event.decision == Decision.ALLOW && event.stage in PROMPT_STAGES) {
                notifier.showBlockPrompt(number)
            }
        }
    }

    private companion object {
        /** Calls older than this are never prompted for (SPEC §7). */
        const val MAX_AGE_MILLIS = 60 * 60 * 1000L

        /** The call log's date and the screening time differ by a few seconds. */
        const val MATCH_WINDOW_MILLIS = 2 * 60 * 1000L

        /** "Unknown number the app allowed": nothing vouched for it. */
        val PROMPT_STAGES = setOf(Stage.DEFAULT, Stage.REPEAT_CALLER)
    }
}
