package com.twentygx.tgxcode.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import com.twentygx.tgxcode.MainActivity
import com.twentygx.tgxcode.R
import com.twentygx.tgxcode.data.model.Ask
import com.twentygx.tgxcode.data.model.AskKind
import com.twentygx.tgxcode.data.model.Decision
import com.twentygx.tgxcode.data.model.SudoAsk

/**
 * Notifications for asks, and the ongoing notice for the connection.
 *
 * The design question that matters here is *which asks can be answered without
 * opening the app*, and the answer is: only a tool ask.
 *
 * - A **tool** ask is complete as `{requestId, decision}` — there is nothing to read
 *   first, so Allow and Deny go inline.
 * - A **plan** approval must carry `mode`, because the session is *in* plan mode
 *   while the card is up. (`web/sw.js:54` sends one without, which `docs/api.md`
 *   says is wrong — a bug not to reproduce.) A *deny* needs no mode and is always
 *   safe, so "Keep planning" is offered and approval is not.
 * - A **question** answer is keyed by exact question text and cannot fit on a
 *   notification at all.
 */
object AskNotifications {

    private const val ASK_ID_BASE = 1000
    const val SERVICE_ID = 1

    fun idFor(sessionId: String): Int = ASK_ID_BASE + sessionId.hashCode().and(0xFFFF)

    @SuppressLint("MissingPermission")
    /**
     * @param quiet a worker's ask its orchestrator is handling: posted without sound or
     *   heads-up, still answerable, and said aloud later if it gets stuck.
     */
    fun show(context: Context, ask: Ask, sessionTitle: String?, quiet: Boolean = false) {
        val sessionId = ask.sessionId ?: return
        val manager = context.getSystemService<NotificationManager>() ?: return

        val title = buildString {
            append(sessionTitle?.takeIf { it.isNotBlank() } ?: "A session")
            append(" — ")
            append(
                when (ask.askKind) {
                    AskKind.Plan -> "a plan to approve"
                    AskKind.Question -> "a question for you"
                    else -> "${ask.toolLabel} needs permission"
                },
            )
            if (quiet) append(" (its orchestrator has it)")
        }

        val builder = NotificationCompat.Builder(context, Channels.ASKS)
            .setSmallIcon(R.drawable.ic_stat_ask)
            .setContentTitle(title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body(ask)))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(if (quiet) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
            .setSilent(quiet)
            // Not auto-cancel: if you tap in, read it, and back out without
            // answering, the ask is still blocked and the notification should still
            // be there. Mirrors `requireInteraction: true` on the desktop.
            .setAutoCancel(false)
            .setOngoing(false)
            .setContentIntent(openIntent(context, sessionId))
            // askedAt as a live counter. api.md keeps this field precisely so a
            // client can say how long something has been blocked, which is the
            // number that matters when nobody is at the desk.
            .apply {
                ask.askedAt?.takeIf { it > 0 }?.let {
                    setWhen(it)
                    setShowWhen(true)
                    setUsesChronometer(true)
                }
            }

        when (ask.askKind) {
            AskKind.Tool -> {
                builder.addAction(action(context, ask, Decision.ALLOW, "Allow"))
                builder.addAction(action(context, ask, Decision.DENY, "Deny"))
            }
            AskKind.Plan -> {
                builder.addAction(action(context, ask, Decision.DENY, "Keep planning"))
            }
            else -> Unit
        }

        manager.notify(idFor(sessionId), builder.build())
    }

    /** Re-post without actions while a decision is in flight, so a double tap cannot double-answer. */
    @SuppressLint("MissingPermission")
    fun showSending(context: Context, ask: Ask, sessionTitle: String?) {
        val sessionId = ask.sessionId ?: return
        val manager = context.getSystemService<NotificationManager>() ?: return
        manager.notify(
            idFor(sessionId),
            NotificationCompat.Builder(context, Channels.ASKS)
                .setSmallIcon(R.drawable.ic_stat_ask)
                .setContentTitle(sessionTitle?.takeIf { it.isNotBlank() } ?: "A session")
                .setContentText("Sending your answer…")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build(),
        )
    }

    @SuppressLint("MissingPermission")
    fun showFailed(context: Context, sessionId: String, text: String) {
        val manager = context.getSystemService<NotificationManager>() ?: return
        manager.notify(
            idFor(sessionId),
            NotificationCompat.Builder(context, Channels.ASKS)
                .setSmallIcon(R.drawable.ic_stat_ask)
                .setContentTitle("Couldn't answer")
                .setContentText(text)
                .setContentIntent(openIntent(context, sessionId))
                .setAutoCancel(true)
                .build(),
        )
    }

    /**
     * The tag that keeps a sudo ask's notification apart from a permission ask's in the
     * same session — same id, different tag, so both can be up, as both asks can.
     */
    private const val SUDO_TAG = "sudo"

    /**
     * A `sudo -A` waiting on your password, loud: nothing else can answer it, an
     * orchestrator included.
     *
     * **No actions.** An action cannot take a password, and must never be made to carry
     * one; even Deny is left to the card, as the desktop's notification leaves it. A tap
     * opens the conversation, where the card is. The lock screen gets the public version,
     * which does not show the command.
     */
    @SuppressLint("MissingPermission")
    fun showSudo(context: Context, ask: SudoAsk, sessionTitle: String?) {
        val sessionId = ask.sessionId ?: return
        val manager = context.getSystemService<NotificationManager>() ?: return
        val session = sessionTitle?.takeIf { it.isNotBlank() } ?: "A session"
        val title = "$session — sudo needs your password"
        val public = NotificationCompat.Builder(context, Channels.ASKS)
            .setSmallIcon(R.drawable.ic_stat_ask)
            .setContentTitle("sudo needs your password")
            .build()

        val builder = NotificationCompat.Builder(context, Channels.ASKS)
            .setSmallIcon(R.drawable.ic_stat_ask)
            .setContentTitle(title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(sudoBody(ask)))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setAutoCancel(false)
            .setContentIntent(openIntent(context, sessionId))
            .apply {
                ask.askedAt?.takeIf { it > 0 }?.let {
                    setWhen(it)
                    setShowWhen(true)
                    setUsesChronometer(true)
                }
            }
        manager.notify(SUDO_TAG, idFor(sessionId), builder.build())
    }

    fun cancelSudo(context: Context, sessionId: String) {
        context.getSystemService<NotificationManager>()?.cancel(SUDO_TAG, idFor(sessionId))
    }

    /** `web/notifications.js`'s line: the command, and the shell warning. */
    fun sudoBody(ask: SudoAsk): String = buildString {
        append("sudo ")
        append(ask.command.ifBlank { ask.line.removePrefix("sudo ") })
        if (ask.runsShell) append(" — runs a shell")
        if (ask.user != null && ask.user != "root") append(" (as ${ask.runsAs})")
        if (ask.attempt > 1) append(" · try ${ask.attempt}")
    }.take(160)

    private const val STALLED_ID_BASE = 3000

    private fun stalledIdFor(sessionId: String): Int = STALLED_ID_BASE + sessionId.hashCode().and(0x3FF)

    /**
     * A busy turn has gone silent. On the activity channel, not asks: nothing is waiting
     * on you, it is only worth knowing — and the bridge sends `stalled: false` when it
     * recovers or ends, which takes this down again.
     */
    @SuppressLint("MissingPermission")
    fun showStalled(context: Context, sessionId: String, sessionTitle: String?, quietMinutes: Long?) {
        val manager = context.getSystemService<NotificationManager>() ?: return
        manager.notify(
            stalledIdFor(sessionId),
            NotificationCompat.Builder(context, Channels.ACTIVITY)
                .setSmallIcon(R.drawable.ic_stat_ask)
                .setContentTitle(sessionTitle ?: "A session")
                .setContentText(
                    "Stalled — nothing from it" +
                        (quietMinutes?.let { " for $it min" } ?: " for a while") +
                        ". Open it to stop it.",
                )
                .setContentIntent(openIntent(context, sessionId))
                .setAutoCancel(true)
                .build(),
        )
    }

    fun cancelStalled(context: Context, sessionId: String) {
        context.getSystemService<NotificationManager>()?.cancel(stalledIdFor(sessionId))
    }

    fun cancel(context: Context, sessionId: String) {
        context.getSystemService<NotificationManager>()?.cancel(idFor(sessionId))
    }

    fun cancelAll(context: Context, keep: Set<String>) {
        // Nothing to enumerate from the platform, so the caller passes what should
        // survive and the service tracks the rest.
        val manager = context.getSystemService<NotificationManager>() ?: return
        manager.activeNotifications
            .filter { it.id >= ASK_ID_BASE }
            .filterNot { keep.map(::idFor).contains(it.id) }
            .forEach { manager.cancel(it.id) }
    }

    private fun body(ask: Ask): String = when (ask.askKind) {
        AskKind.Plan -> ask.planMarkdown.orEmpty().take(180).ifBlank { "A plan is ready." }
        AskKind.Question ->
            ask.questions().firstOrNull()?.question ?: "There is a question waiting."
        else -> com.twentygx.tgxcode.ui.conversation.toolBody(ask.input).take(180)
    }

    private fun openIntent(context: Context, sessionId: String): PendingIntent =
        PendingIntent.getActivity(
            context,
            sessionId.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse("tgxcode://session/$sessionId")
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /**
     * The trap this guards against: **`PendingIntent` equality ignores extras.**
     * Two asks in one session share a notification id, so a reused PendingIntent
     * would answer the *previous* `requestId`. The requestCode makes them distinct,
     * and FLAG_UPDATE_CURRENT keeps the extras fresh.
     */
    private fun action(
        context: Context,
        ask: Ask,
        decision: String,
        label: String,
    ): NotificationCompat.Action {
        val intent = Intent(context, AskActionReceiver::class.java).apply {
            action = AskActionReceiver.ACTION_ANSWER
            putExtra(AskActionReceiver.EXTRA_SESSION_ID, ask.sessionId)
            putExtra(AskActionReceiver.EXTRA_REQUEST_ID, ask.requestId)
            putExtra(AskActionReceiver.EXTRA_DECISION, decision)
        }
        val pending = PendingIntent.getBroadcast(
            context,
            (ask.requestId + decision).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action.Builder(0, label, pending).build()
    }

    /**
     * The ongoing notice. Says what it is *for*, and says plainly when it is not
     * working — "not connected" is the state in which asks are being auto-denied,
     * and the user should be able to see that at a glance rather than discover it
     * from a session that gave up.
     */
    fun serviceNotification(context: Context, state: ServiceState): Notification =
        NotificationCompat.Builder(context, Channels.CONNECTION)
            .setSmallIcon(R.drawable.ic_stat_connected)
            .setContentTitle(state.title)
            .setContentText(state.detail)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .addAction(
                0,
                "Stop",
                PendingIntent.getBroadcast(
                    context,
                    1,
                    Intent(context, AskActionReceiver::class.java)
                        .setAction(AskActionReceiver.ACTION_STOP_SERVICE),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()

    data class ServiceState(val title: String, val detail: String)
}
