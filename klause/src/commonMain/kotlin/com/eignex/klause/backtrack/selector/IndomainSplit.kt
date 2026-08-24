package com.eignex.klause.backtrack.selector

import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.values
import kotlin.random.Random

/**
 * Domain bisection (`indomain_split`): branch `v ≤ mid` first, then `v ≥ mid+1`, with
 * `mid` the floor midpoint of the current interval. The engine's int decisions are bound
 * splits around the heuristic's first value (see `IntNode`), so yielding
 * the midpoint produces exactly the dichotomic search the annotation asks for — log-depth
 * on wide domains where value enumeration is linear.
 */
object IndomainSplit : ValueSelector {
    override fun fresh() = this

    override fun values(session: PropagationSession, varRef: VarRef, rng: Random): Sequence<Long> = when (varRef) {
        is VarRef.Bool -> sequenceOf(0L, 1L)

        is VarRef.IntVar -> {
            val d = session.intDomain(varRef.varId)
            val mid = boundsMidpoint(d)
            // The midpoint may sit in a hole; the bound split doesn't care, but the
            // trailing ascending walk keeps the sequence complete for any consumer that
            // enumerates past the first value.
            sequenceOf(mid) + sequence {
                for (i in 0 until d.values.size) {
                    val v = d.values.valueAt(i)
                    if (v != mid) yield(v)
                }
            }
        }
    }
}
