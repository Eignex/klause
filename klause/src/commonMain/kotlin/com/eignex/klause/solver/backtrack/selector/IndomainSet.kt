package com.eignex.klause.solver.backtrack.selector

import com.eignex.klause.solver.propagation.PropagationSession
import kotlin.random.Random

/**
 * Allow-list value selection: tries only [allowedValues] (in order) intersected with the
 * current domain. Sparse-aware via the `in d` membership check.
 */
internal class IndomainSet(private val allowedValues: IntArray) : ValueSelector {
    override fun values(session: PropagationSession, varRef: VarRef, rng: Random): Sequence<Int> = when (varRef) {
        is VarRef.Bool -> allowedValues.asSequence().filter { it == 0 || it == 1 }

        is VarRef.IntVar -> {
            val d = session.intDomain(varRef.varId)
            allowedValues.asSequence().filter { it in d }
        }
    }
}
