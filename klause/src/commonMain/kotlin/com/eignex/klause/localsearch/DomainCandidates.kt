package com.eignex.klause.localsearch

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.randomValue
import com.eignex.klause.ir.values
import kotlin.random.Random

/**
 * Calls [action] on the values of this domain a repair or seed step chooses among: every value while the domain can
 * be enumerated, else its bounds and [WIDE_DOMAIN_DRAWS] random draws. A domain past 2^31 values cannot be walked,
 * and a single step needs only a few candidates from it.
 */
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

/** Random values drawn from a domain too wide to enumerate, beside its bounds. */
internal const val WIDE_DOMAIN_DRAWS: Int = 16
