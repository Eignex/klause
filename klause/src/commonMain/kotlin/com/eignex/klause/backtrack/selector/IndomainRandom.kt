package com.eignex.klause.backtrack.selector

import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.util.MutableIntIntMap
import kotlin.random.Random

/** Domains at or below this size use the eager shuffle; larger ones use the lazy Fisher-Yates so
 *  an unconsumed tail (the common case) costs nothing. */
private const val INDOMAIN_EAGER_MAX = 32

/** Uniformly random shuffle of the domain (`indomain_random`). */
object IndomainRandom : ValueSelector {
    override fun fresh() = this

    override fun values(session: PropagationSession, varRef: VarRef, rng: Random): Sequence<Long> = when (varRef) {
        is VarRef.Bool -> if (rng.nextBoolean()) sequenceOf(1L, 0L) else sequenceOf(0L, 1L)

        is VarRef.IntVar -> {
            val d = session.intDomain(varRef.varId)
            // Ask only for as many values as the eager path would shuffle: a wider domain declines
            // here and takes the bounds draw below, instead of being counted first and rejected.
            val eager = d.spanOrNull(INDOMAIN_EAGER_MAX.toLong())
            val full = d.spanOrNull()
            val n = full?.size ?: 0
            when {
                d.isFixed -> sequenceOf(d.min)

                eager != null -> {
                    // Small domain: materialise the non-hole values and Fisher-Yates shuffle in
                    // place (cheaper than the lazy coroutine + map for a handful of values).
                    val arr = LongArray(eager.size) { eager.valueAt(it) }
                    for (i in arr.size - 1 downTo 1) {
                        val j = rng.nextInt(i + 1)
                        val tmp = arr[i]
                        arr[i] = arr[j]
                        arr[j] = tmp
                    }
                    arr.asSequence()
                }

                full == null -> sequence {
                    // A positional shuffle cannot reach values past index 2^31 on a domain with more
                    // values than an index addresses, so draw the head — the value a bound split
                    // actually consumes — uniformly from the bounds.
                    yield(d.clamp(randomInBounds(d, rng)))
                }

                else -> sequence {
                    // Lazy Fisher-Yates over domain indices: emit a uniform random permutation
                    // doing O(consumed) work, not O(n). Branch nodes typically read only the first
                    // value (IntNode bound-splits around it), so eagerly shuffling a large domain
                    // is almost pure waste — it dominates large-domain CSP profiles (e.g. gbac at
                    // ~77%). `swap` records only the touched index slots (≈ O(consumed)).
                    val swap = MutableIntIntMap()
                    for (k in 0 until n) {
                        val j = k + rng.nextInt(n - k)
                        val ak = swap.getOrDefault(k, k)
                        val aj = if (j == k) ak else swap.getOrDefault(j, j)
                        if (j != k) swap.put(j, ak)
                        yield(full.valueAt(aj))
                    }
                }
            }
        }
    }
}
