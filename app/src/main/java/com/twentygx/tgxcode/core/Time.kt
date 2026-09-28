package com.twentygx.tgxcode.core

import java.time.Instant
import java.time.format.DateTimeParseException
import kotlin.math.roundToLong

/**
 * The two time formatters from mobile.js, ported exactly. They are small, but they
 * are the only place the phone shows time at all, so the wording matters.
 */

/** Parses the ISO-8601 timestamps the bridge puts on events and summaries. */
fun parseIso(ts: String?): Instant? =
    ts?.takeIf { it.isNotBlank() }?.let {
        try {
            Instant.parse(it)
        } catch (_: DateTimeParseException) {
            null
        }
    }

/**
 * `4m`, `3h`, `2d` — the meta line on a Sessions row.
 *
 * Blank for anything unparseable, matching mobile.js's `if (!ts) return ''`.
 */
fun ago(ts: String?, now: Instant = Instant.now()): String {
    val then = parseIso(ts) ?: return ""
    return ago(then.toEpochMilli(), now)
}

fun ago(epochMillis: Long?, now: Instant = Instant.now()): String {
    if (epochMillis == null || epochMillis <= 0) return ""
    val seconds = (now.toEpochMilli() - epochMillis) / 1000.0
    if (!seconds.isFinite()) return ""
    return when {
        seconds < 60 -> "${seconds.roundToLong().coerceAtLeast(0)}s"
        seconds < 3600 -> "${(seconds / 60).roundToLong()}m"
        seconds < 172_800 -> "${(seconds / 3600).roundToLong()}h"
        else -> "${(seconds / 86_400).roundToLong()}d"
    }
}

/**
 * How long an ask has been waiting — counting **up**, not down.
 *
 * Asks no longer expire, and that is deliberate: you should be able to read a plan
 * without a clock running. What is still worth knowing on a phone is that something
 * has been blocked for twenty minutes.
 */
fun waitingFor(askedAt: Long?, now: Instant = Instant.now()): String {
    if (askedAt == null || askedAt <= 0) return ""
    val seconds = (now.toEpochMilli() - askedAt) / 1000.0
    return when {
        seconds < 45 -> "just now"
        seconds < 3600 -> "waiting ${(seconds / 60).roundToLong()} min"
        else -> "waiting ${(seconds / 3600).roundToLong()}h"
    }
}
