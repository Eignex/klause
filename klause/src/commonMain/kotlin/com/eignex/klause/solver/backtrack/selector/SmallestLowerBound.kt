package com.eignex.klause.solver.backtrack.selector

import com.eignex.klause.solver.propagation.PropagationSession
import kotlin.random.Random

/**
 * Smallest lower bound first (MiniZinc's `smallest`): the free variable whose domain
 * minimum is lowest. Free bools count as minimum 0. Ties broken by variable id, bools
 * before ints. The scheduling staple — branching on the task that can start earliest.
 */
object SmallestLowerBound : VariableSelector {
    override fun pick(session: PropagationSession, rng: Random): VarRef? {
        var best: VarRef? = null
        var bestLb = Int.MAX_VALUE
        val problem = session.problem
        for (v in 0 until problem.numBoolVars) {
            if (session.boolValue(v) == null && 0 < bestLb) {
                best = VarRef.Bool(v)
                bestLb = 0
            }
        }
        for (v in 0 until problem.numIntVars) {
            val d = session.intDomain(v)
            if (d.size > 1 && d.min < bestLb) {
                best = VarRef.IntVar(v)
                bestLb = d.min
            }
        }
        return best
    }
}
