package com.eignex.klause.backtrack.selector

import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.random.Random

/**
 * Objective-aware value selection — `indomain_best` / `intDomainBest`. For an int var with
 * coefficient `c` in the linear objective, returns the domain ascending (best minimum first)
 * when `c ≥ 0` and descending when `c < 0`. For a bool var with weight `w`, returns the
 * polarity minimising `w` first (false first when `w ≥ 0`, true first when `w < 0`).
 *
 * Pairs naturally with [MaxRegret] (variable side) and a B&B-style optimisation loop —
 * each successful pin moves the partial assignment as close to the global optimum as the
 * variable-level coefficients allow, so the incumbent improves fast and pruning kicks in
 * early. For a satisfiability problem, falls through to [IndomainMin] (every coefficient
 * is zero so ascending order is preserved).
 */
internal class IndomainBest(private val objective: LinearObjective) : ValueSelector {
    override fun values(session: PropagationSession, varRef: VarRef, rng: Random): Sequence<Int> = when (varRef) {
        is VarRef.Bool -> {
            val w = if (varRef.varId < objective.boolWeights.size) objective.boolWeights[varRef.varId] else 0L
            // false contributes 0; true contributes w. Lower-contribution-first.
            if (w >= 0L) sequenceOf(0, 1) else sequenceOf(1, 0)
        }

        is VarRef.IntVar -> {
            val c = if (varRef.varId <
                objective.intCoefficients.size
            ) {
                objective.intCoefficients[varRef.varId]
            } else {
                0L
            }
            val d = session.intDomain(varRef.varId)
            if (c >= 0L) {
                sequence { for (i in 0 until d.size) yield(d.valueAt(i)) }
            } else {
                sequence { for (v in d.max downTo d.min) if (v in d) yield(v) }
            }
        }
    }
}
