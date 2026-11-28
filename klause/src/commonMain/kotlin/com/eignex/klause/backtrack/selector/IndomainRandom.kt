package com.eignex.klause.backtrack.selector

import com.eignex.klause.ir.randomValue
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.search.VarRef
import com.eignex.util.collections.MutableIntIntMap
import kotlin.random.Random

// Larger domains use the lazy Fisher-Yates so an unconsumed tail, the common case, costs nothing.
private const val INDOMAIN_EAGER_MAX = 32

/** Uniformly random shuffle of the domain (`indomain_random`). */
object IndomainRandom : ValueSelector {
    override fun fresh() = this

    override fun values(session: PropagationSession, varRef: VarRef, rng: Random): Sequence<Long> = when (varRef) {
        is VarRef.Bool -> if (rng.nextBoolean()) sequenceOf(1L, 0L) else sequenceOf(0L, 1L)

        is VarRef.IntVar -> {
            val d = session.intDomain(varRef.varId)
            // Bounded so a wide domain declines here instead of being counted first and rejected.
            val eager = d.spanOrNull(INDOMAIN_EAGER_MAX.toLong())
            val full = d.spanOrNull()
            val n = full?.size ?: 0
            when {
                d.isFixed -> sequenceOf(d.min)

                eager != null -> {
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
                    // A positional shuffle cannot index past 2^31 values, so draw the head, the value a
                    // bound split consumes, uniformly from the bounds.
                    yield(d.randomValue(rng))
                }

                else -> sequence {
                    // Lazy Fisher-Yates: O(consumed) work, since branch nodes typically read only the first
                    // value. `swap` records only the touched index slots.
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
