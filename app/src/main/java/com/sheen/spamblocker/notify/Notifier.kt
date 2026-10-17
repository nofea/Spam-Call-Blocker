package com.sheen.spamblocker.notify

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.sheen.spamblocker.MainActivity
import com.sheen.spamblocker.R
import com.sheen.spamblocker.calllog.Permissions
import com.sheen.spamblocker.data.CallEvent
import com.sheen.spamblocker.data.CallEventDao
import com.sheen.spamblocker.screening.Decision
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.DateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

/** Notification channels and builders (SPEC §11). */
@Singleton
class Notifier @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val callEvents: CallEventDao,
) {
    private val manager = NotificationManagerCompat.from(context)

    fun createChannels() {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_BLOCKED, "Blocked calls", NotificationManager.IMPORTANCE_LOW).apply {
                description = "A daily summary of rejected and silenced calls"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PROMPTS, "Spam prompts", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Possible-spam warnings and \"Block this number?\" prompts"
            },
        )
    }

    /** Warn action (SPEC §2.2): the call rings, and this explains why it looks like spam. */
    fun showWarning(e164: String, reason: String) {
        val id = promptId(e164)
        post(
            id,
            NotificationCompat.Builder(context, CHANNEL_PROMPTS)
                .setSmallIcon(R.drawable.ic_stat_blocked)
                .setContentTitle("Possible spam: $reason")
                .setContentText(e164)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setContentIntent(openApp())
                .setAutoCancel(true)
                .addAction(0, "Block", NotificationActionReceiver.intent(context, ACTION_BLOCK, e164, id))
                .addAction(0, "Not spam", NotificationActionReceiver.intent(context, ACTION_NOT_SPAM, e164, id))
                .build(),
        )
    }

    /** Post-call prompt (SPEC §7). */
    fun showBlockPrompt(e164: String) {
        val id = promptId(e164)
        post(
            id,
            NotificationCompat.Builder(context, CHANNEL_PROMPTS)
                .setSmallIcon(R.drawable.ic_stat_blocked)
                .setContentTitle("Block $e164?")
                .setContentText("You just had a call from this unknown number.")
                .setContentIntent(openApp())
                .setAutoCancel(true)
                .addAction(0, "Block", NotificationActionReceiver.intent(context, ACTION_BLOCK, e164, id))
                .addAction(0, "Not spam", NotificationActionReceiver.intent(context, ACTION_NOT_SPAM, e164, id))
                .build(),
        )
    }

    /** One grouped "Blocked calls" notification per day (SPEC §11), rebuilt after each block. */
    suspend fun updateBlockedSummary() {
        val today = LocalDate.now()
        val startOfDay = today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val blocked = callEvents.getSince(startOfDay)
            .filter { it.decision == Decision.REJECT || it.decision == Decision.SILENCE }
            .sortedByDescending { it.timestamp }
        if (blocked.isEmpty()) return

        val title = if (blocked.size == 1) "1 call blocked today" else "${blocked.size} calls blocked today"
        val style = NotificationCompat.InboxStyle().setBigContentTitle(title)
        blocked.take(INBOX_LINES).forEach { style.addLine(line(it)) }
        if (blocked.size > INBOX_LINES) style.setSummaryText("+${blocked.size - INBOX_LINES} more")

        post(
            BLOCKED_SUMMARY_ID_BASE + today.dayOfYear,
            NotificationCompat.Builder(context, CHANNEL_BLOCKED)
                .setSmallIcon(R.drawable.ic_stat_blocked)
                .setContentTitle(title)
                .setContentText(line(blocked.first()))
                .setStyle(style)
                .setContentIntent(openApp())
                .setOnlyAlertOnce(true)
                .setNumber(blocked.size)
                .build(),
        )
    }

    fun cancel(id: Int) = manager.cancel(id)

    private fun line(e: CallEvent): String {
        val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(e.timestamp))
        return "$time  ${e.number ?: "Hidden"} · ${e.stage.label}"
    }

    @SuppressLint("MissingPermission") // checked by canNotify
    private fun post(id: Int, notification: android.app.Notification) {
        if (Permissions.canNotify(context)) manager.notify(id, notification)
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun promptId(e164: String) = PROMPT_ID_BASE + (e164.hashCode() and 0xFFFFF)

    companion object {
        const val CHANNEL_BLOCKED = "blocked_calls"
        const val CHANNEL_PROMPTS = "spam_prompts"
        const val ACTION_BLOCK = "com.sheen.spamblocker.action.BLOCK"
        const val ACTION_NOT_SPAM = "com.sheen.spamblocker.action.NOT_SPAM"
        private const val BLOCKED_SUMMARY_ID_BASE = 1_000
        private const val PROMPT_ID_BASE = 10_000
        private const val INBOX_LINES = 6
    }
}
