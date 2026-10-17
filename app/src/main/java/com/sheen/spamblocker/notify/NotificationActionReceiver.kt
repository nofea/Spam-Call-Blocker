package com.sheen.spamblocker.notify

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sheen.spamblocker.data.RulesRepository
import com.sheen.spamblocker.di.AppScope
import com.sheen.spamblocker.rules.Action
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Handles the Block / Not spam buttons on warning and post-call notifications. */
@AndroidEntryPoint
class NotificationActionReceiver : BroadcastReceiver() {
    @Inject lateinit var rules: RulesRepository
    @Inject lateinit var notifier: Notifier
    @Inject @AppScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        val number = intent.getStringExtra(EXTRA_NUMBER) ?: return
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        val pending = goAsync()
        scope.launch {
            try {
                when (intent.action) {
                    Notifier.ACTION_BLOCK -> rules.blockNumber(number, Action.REJECT)
                    Notifier.ACTION_NOT_SPAM -> rules.markNotSpam(number)
                }
                notifier.cancel(notificationId)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val EXTRA_NUMBER = "number"
        private const val EXTRA_NOTIFICATION_ID = "notificationId"

        fun intent(context: Context, action: String, e164: String, notificationId: Int): PendingIntent {
            val intent = Intent(context, NotificationActionReceiver::class.java)
                .setAction(action)
                .putExtra(EXTRA_NUMBER, e164)
                .putExtra(EXTRA_NOTIFICATION_ID, notificationId)
            return PendingIntent.getBroadcast(
                context,
                (action + e164).hashCode(),
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
    }
}
