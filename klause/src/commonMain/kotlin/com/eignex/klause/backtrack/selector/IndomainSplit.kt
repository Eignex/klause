package com.eignex.klause.backtrack.selector

import com.eignex.klause.ir.values
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.search.VarRef
import kotlin.random.Random

/**
 * Domain bisection (`indomain_split`): branch `v ≤ mid` first, then `v ≥ mid+1`, with `mid` the floor
 * midpoint of the current interval. Int decisions bound-split around the first value, so yielding the
 * midpoint gives dichotomic search.
 */
object IndomainSplit : ValueSelector {
    override fun fresh() = this

    override fun values(session: PropagationSession, varRef: VarRef, rng: Random): Sequence<Long> = when (varRef) {
        is VarRef.Bool -> sequenceOf(0L, 1L)

        is VarRef.IntVar -> {
            val d = session.intDomain(varRef.varId)
            val mid = boundsMidpoint(d)
            // The ascending tail keeps the sequence complete for a consumer that enumerates past the
            // midpoint, which may sit in a hole.
            sequenceOf(mid) + sequence {
                for (i in 0 until d.values.size) {
                    val v = d.values.valueAt(i)
                    if (v != mid) yield(v)
                }
            }
        }
    }
}
