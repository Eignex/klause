package com.eignex.klause.localsearch

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.randomValue
import com.eignex.klause.ir.values
import kotlin.random.Random

// A domain past 2^31 values cannot be walked, and one step needs only a few candidates: its bounds and random draws.
internal inline fun IntDomain.forEachCandidate(rng: Random, action: (Long) -> Unit) {
    if (spanOrNull() != null) {
        val enumerated = values
        for (i in 0 until enumerated.size) action(enumerated.valueAt(i))
        return
    }
    action(min)
    action(max)
    repeat(WIDE_DOMAIN_DRAWS) { action(randomValue(rng)) }
}

internal const val WIDE_DOMAIN_DRAWS: Int = 16
