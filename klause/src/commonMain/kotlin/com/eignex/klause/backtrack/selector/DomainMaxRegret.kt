package com.eignex.klause.backtrack.selector

import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.search.VarRef
import com.eignex.util.numbers.saturatedSub
import kotlin.random.Random

/**
 * Largest regret first (MiniZinc's `max_regret`): the free variable with the greatest gap between the two
 * smallest values in its domain. Free bools have regret `1`. Ties keep the earliest variable (bools
 * precede ints). Domain-only, unlike the objective-weighted [MaxRegret], so it applies to satisfaction too.
 */
object DomainMaxRegret : VariableSelector {
    override fun fresh() = this

    override fun pick(session: PropagationSession, rng: Random): VarRef? {
        var best: VarRef? = null
        var bestRegret = Long.MIN_VALUE
        val problem = session.problem
        for (v in 0 until problem.numBoolVars) {
            if (session.needsBoolDecision(v) && 1L > bestRegret) {
                best = VarRef.Bool(v)
                bestRegret = 1L
            }
        }
        for (v in 0 until problem.numIntVars) {
            val d = session.intDomain(v)
            if (!session.needsIntDecision(v)) continue
            val regret = saturatedSub(d.higher(d.min), d.min)
            if (regret > bestRegret) {
                best = VarRef.IntVar(v)
                bestRegret = regret
            }
        }
        return best
    }
}
