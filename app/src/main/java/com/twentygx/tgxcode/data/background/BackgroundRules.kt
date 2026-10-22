package com.twentygx.tgxcode.data.background

import com.twentygx.tgxcode.data.install.Claim
import com.twentygx.tgxcode.data.install.PhoneInstallRules
import com.twentygx.tgxcode.data.model.PhoneInstall
import com.twentygx.tgxcode.data.model.PhoneInstallState
import com.twentygx.tgxcode.data.repo.ConnState
import com.twentygx.tgxcode.data.repo.isRefused
import kotlin.random.Random

/**
 * The decisions behind installs that happen with the app closed, kept apart so they
 * can be pinned without a device: when to reconnect and how long to wait, when a
 * stream that says it is live has gone quiet, what the fallback check does with the
 * queue it reads, and what the Settings card says is wrong.
 */
object BackgroundRules {

    // ------------------------------------------------------------- reconnecting

    /**
     * How long to wait before reconnect attempt [attempt]. Aggressive rather than
     * polite, because every second disconnected is a second in which an arriving ask
     * is auto-denied. Full jitter so several clients do not synchronise on a bridge
     * restart.
     */
    fun backoffMillis(attempt: Int, random: Random = Random): Long {
        if (attempt <= 0) return 0
        val base = minOf(MAX_BACKOFF_MILLIS, 250L * (1L shl minOf(attempt, 7)))
        return base / 2 + random.nextLong(base / 2 + 1)
    }

    /**
     * The attempt count after one run of the stream ends. A run that got as far as
     * `hello` starts over — an hour-long connection dropping once should reconnect
     * instantly, not wait thirty seconds. In buffered mode `hello` never comes, so the
     * socket opening is the success there.
     */
    fun nextAttempt(attempt: Int, sawHello: Boolean, buffered: Boolean, sawOpen: Boolean): Int =
        if (sawHello || (buffered && sawOpen)) 0 else attempt + 1

    /**
     * Whether a change of default network is worth dropping the stream for.
     *
     * The callback fires once on registering, for the network already in use, and that
     * one is not news. Losing the network is not either: there is nothing to reconnect
     * on until another arrives. Anything else — Wi-Fi to cellular, cellular back to
     * Wi-Fi, a network coming back after none — leaves the old socket bound to an
     * interface that is gone, and it would sit there until the read timeout (which
     * does not count while the phone sleeps) or a backoff (which does not either).
     */
    fun reconnectOnNetwork(previous: Long?, next: Long?, first: Boolean): Boolean =
        !first && next != null && next != previous

    /**
     * A stream the repository thinks is live but that has not delivered a byte, ping
     * comments included, for longer than three of the bridge's 25s pings.
     *
     * The 40s read timeout would catch it, but only while the CPU is awake: the
     * socket's timer runs on the monotonic clock, which stops in deep sleep, and a
     * dead socket is exactly the one that never wakes the phone. [lastByteAt] and
     * [now] are `elapsedRealtime`, which counts the sleep, so this sees it the moment
     * anything wakes the phone — the pulse alarm, the fallback job, a network change.
     */
    fun streamStale(state: ConnState, lastByteAt: Long, now: Long): Boolean =
        state == ConnState.Live && lastByteAt > 0 && now - lastByteAt > STALE_AFTER_MILLIS

    /** What the pulse alarm should do about the connection. */
    enum class Pulse { Nothing, Reconnect }

    /**
     * Only for a connection somebody wants (a lease is held). Offline or Connecting
     * means a backoff `delay` is pending, and that delay does not run while the phone
     * sleeps either, so the pulse cuts it short. A refusal is left alone: nothing about
     * the next attempt would differ.
     */
    fun pulse(state: ConnState, lastByteAt: Long, now: Long, wanted: Boolean): Pulse = when {
        !wanted || state.isRefused || state == ConnState.Unconfigured -> Pulse.Nothing
        state == ConnState.Offline || state == ConnState.Connecting -> Pulse.Reconnect
        streamStale(state, lastByteAt, now) -> Pulse.Reconnect
        else -> Pulse.Nothing
    }

    /**
     * Whether the service should keep the CPU awake right now: while it is getting the
     * connection back, for the first [WAKE_DURING_OUTAGE_MILLIS] of an outage only.
     * Without it the backoff `delay` stops with the CPU and a dropped stream stays
     * dropped until something else wakes the phone. Bounded, because a bridge that is
     * down for the night should not cost a night of battery; past that the pulse alarm
     * and the network callback take over.
     */
    fun holdAwake(state: ConnState, outageStartedAt: Long?, now: Long): Boolean =
        (state == ConnState.Offline || state == ConnState.Connecting) &&
            outageStartedAt != null && now - outageStartedAt < WAKE_DURING_OUTAGE_MILLIS

    // ------------------------------------------------------------- fallback check

    /** What the 15-minute check found, and so what it does. */
    sealed interface Fallback {
        /** Not paired, or the bridge did not answer: nothing to act on this time. */
        data object Unreachable : Fallback

        /** Installs are switched off on this phone. */
        data object Disabled : Fallback

        /** Nothing for this phone. */
        data object Idle : Fallback

        /**
         * Work to start or resume, by id: [pickUp] are records to claim or resume,
         * [settle] are this phone's claims with something still to say (a report that
         * did not get through, a self-update to confirm, a grant that arrived).
         */
        data class Work(val pickUp: List<String>, val settle: List<String>) : Fallback

        /** One line for the Settings card. */
        val summary: String
            get() = when (this) {
                Unreachable -> "bridge not reachable"
                Disabled -> "installs are off"
                Idle -> "nothing waiting"
                is Work -> buildList {
                    if (pickUp.isNotEmpty()) add("${pickUp.size} to install")
                    if (settle.isNotEmpty()) add("${settle.size} to settle")
                }.joinToString(", ")
            }
    }

    /**
     * Decide from one read of `GET /api/phone-installs` ([installs], null when it
     * failed) and this phone's persisted [claims].
     *
     * The pick-up rule is [PhoneInstallRules.toPickUp]'s, the same one the stream's
     * catch-up uses, so the fallback never claims anything the live path would not.
     * [inHand] is work this process already has.
     */
    fun fallback(
        installs: List<PhoneInstall>?,
        claims: List<Claim>,
        enabled: Boolean,
        canInstall: Boolean,
        inHand: Set<String> = emptySet(),
    ): Fallback {
        if (installs == null) return Fallback.Unreachable
        val byId = installs.associateBy { it.id }
        val settle = claims.filter { claim ->
            val record = byId[claim.id]
            when {
                claim.id in inHand -> false
                claim.outbox != null -> true
                record == null -> false
                claim.waitingForPermission ->
                    enabled && canInstall && record.state == PhoneInstallState.NEEDS_ACTION
                else -> record.state == PhoneInstallState.INSTALLING
            }
        }.map { it.id }
        if (!enabled) return if (settle.isEmpty()) Fallback.Disabled else Fallback.Work(emptyList(), settle)
        val own = claims.filter { it.outbox == null && !it.waitingForPermission }.map { it.id }.toSet()
        val pickUp = PhoneInstallRules.toPickUp(installs, own, inHand).map { it.id }
        return if (pickUp.isEmpty() && settle.isEmpty()) Fallback.Idle else Fallback.Work(pickUp, settle)
    }

    // ------------------------------------------------------------- the Settings card

    /** What a row's button does. */
    enum class Fix { StayConnected, Reconnect, BatteryExempt, InstallPermission, Notifications, CheckNow }

    data class Row(val label: String, val detail: String, val ok: Boolean, val fix: Fix? = null, val fixLabel: String? = null)

    /**
     * The card's rows, in the order they matter for an install arriving with the app
     * closed. Times are epoch millis; [ago] renders a duration in the app's style.
     */
    fun rows(status: BackgroundStatus, now: Long, ago: (Long) -> String): List<Row> = buildList {
        add(
            when {
                !status.stayConnected -> Row(
                    "Stay connected",
                    "Off. Installs arrive only while the app is open, or at the next check.",
                    ok = false,
                    fix = Fix.StayConnected,
                    fixLabel = "Turn on",
                )
                status.serviceRunning -> Row("Stay connected", "Running", ok = true)
                else -> Row(
                    "Stay connected",
                    "On, but the service is not running. Android stopped it.",
                    ok = false,
                    fix = Fix.StayConnected,
                    fixLabel = "Start",
                )
            },
        )

        val heard = status.lastEventAt?.let { "last event ${ago(it)} ago" } ?: "no events yet"
        add(
            when (status.connection) {
                ConnState.Live -> Row("Connection", "Live, $heard", ok = true)
                ConnState.Polling -> Row("Connection", "Checking every few seconds (no live stream), $heard", ok = true)
                else -> Row(
                    "Connection",
                    (status.lastConnectedAt?.let { "Last connected ${ago(it)} ago" } ?: "Not connected since the app started") +
                        ", $heard",
                    ok = false,
                    fix = Fix.Reconnect.takeIf { status.connection != ConnState.Unconfigured },
                    fixLabel = "Reconnect",
                )
            },
        )

        add(
            if (status.batteryExempt) {
                Row("Battery optimisation", "Not optimised: Android lets it run", ok = true)
            } else {
                Row(
                    "Battery optimisation",
                    "Optimised: Android may freeze the app and drop its connection",
                    ok = false,
                    fix = Fix.BatteryExempt,
                    fixLabel = "Allow",
                )
            },
        )

        add(
            if (status.canInstall) {
                Row("Install unknown apps", "Allowed", ok = true)
            } else {
                Row(
                    "Install unknown apps",
                    "Not allowed: installs wait for it",
                    ok = false,
                    fix = Fix.InstallPermission,
                    fixLabel = "Allow",
                )
            },
        )

        add(
            if (status.notificationsAllowed) {
                Row("Notifications", "Allowed", ok = true)
            } else {
                Row(
                    "Notifications",
                    "Off: an install that needs a tap waits silently",
                    ok = false,
                    fix = Fix.Notifications,
                    fixLabel = "Turn on",
                )
            },
        )

        val check = status.lastCheckAt
        add(
            Row(
                "Fallback check",
                when {
                    check == null -> "Has not run yet. It runs about every 15 minutes."
                    else -> "Ran ${ago(check)} ago" + (status.lastCheckResult?.let { ": $it" } ?: "")
                },
                // Two missed periods and then some: the job is being held back.
                ok = check != null && now - check < CHECK_LATE_AFTER_MILLIS,
                fix = Fix.CheckNow,
                fixLabel = "Run now",
            ),
        )
    }

    /**
     * Whether to ask, once, for the battery exemption as an install arrives. Asking
     * again after a "no" would be nagging; Settings and the Installs screen keep the
     * button for anyone who changes their mind.
     */
    fun promptForBattery(exempt: Boolean, alreadyAsked: Boolean): Boolean = !exempt && !alreadyAsked

    /** Three pings of 25s, missed. */
    const val STALE_AFTER_MILLIS = 80_000L

    const val MAX_BACKOFF_MILLIS = 30_000L

    const val WAKE_DURING_OUTAGE_MILLIS = 2 * 60_000L

    /** The fallback is a 15-minute job; three periods without a run means it is being held back. */
    const val CHECK_LATE_AFTER_MILLIS = 45 * 60_000L
}

/** What the Settings card shows. Times are epoch millis, null for never. */
data class BackgroundStatus(
    val stayConnected: Boolean = false,
    val serviceRunning: Boolean = false,
    val connection: ConnState = ConnState.Unconfigured,
    val lastConnectedAt: Long? = null,
    val lastEventAt: Long? = null,
    val batteryExempt: Boolean = false,
    val canInstall: Boolean = false,
    val notificationsAllowed: Boolean = false,
    val lastCheckAt: Long? = null,
    val lastCheckResult: String? = null,
)
