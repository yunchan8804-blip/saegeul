/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.data.points

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * On-device, non-transferable, non-cash point balance earned by
 * opted-in rewarded ads and spent on cosmetic unlocks.
 * Append-only ledger so every grant and purchase stays auditable.
 */
@Serializable
data class PointEvent(
    val epochMs: Long,
    val type: String,
    val amount: Int,
    val venueId: String,
    val detail: String = ""
) {
    companion object {
        const val EARN = "EARN"
        const val SPEND = "SPEND"
    }
}

object PointLedgerMath {
    fun balance(events: List<PointEvent>): Int {
        var total = 0L
        for (event in events) {
            if (event.amount <= 0) continue
            when (event.type) {
                PointEvent.EARN -> total = (total + event.amount).coerceAtMost(Int.MAX_VALUE.toLong())
                PointEvent.SPEND -> total = (total - event.amount).coerceAtLeast(0L)
            }
        }
        return total.toInt()
    }
}

object PointPricing {
    const val POINTS_PER_AD = 1
    const val POINTS_PER_LEVEL_UP = 10
    const val NORMAL_THEME_PRICE = 10
    const val PREMIUM_THEME_PRICE = 50
}

class PointLedger(context: Context) {
    private val file: File = File(context.filesDir, "points/ledger.jsonl")
    private val json = Json { encodeDefaults = true }

    fun balance(): Int = PointLedgerMath.balance(events())

    fun events(): List<PointEvent> {
        if (!file.exists()) return emptyList()
        return runCatching {
            file.readLines().filter { it.isNotBlank() }.mapNotNull { line ->
                runCatching {
                    json.decodeFromString(PointEvent.serializer(), line)
                }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    fun earn(amount: Int, venueId: String, nowEpochMs: Long, detail: String = ""): Boolean {
        return record(PointEvent(nowEpochMs, PointEvent.EARN, amount, venueId, detail))
    }

    fun spend(amount: Int, nowEpochMs: Long, detail: String = ""): Boolean {
        if (balance() < amount) return false
        return record(PointEvent(nowEpochMs, PointEvent.SPEND, amount, "", detail))
    }

    private fun record(event: PointEvent): Boolean {
        if (amountInvalid(event)) return false
        return runCatching {
            file.parentFile?.mkdirs()
            file.appendText(json.encodeToString(PointEvent.serializer(), event) + "\n")
            true
        }.getOrDefault(false)
    }

    private fun amountInvalid(event: PointEvent): Boolean {
        return event.amount <= 0
    }
}
