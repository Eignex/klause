package com.eignex.klause.solver.backtrack.selector

import com.eignex.klause.solver.propagation.PropagationSession
import kotlin.random.Random

/** Largest upper bound first (MiniZinc's `largest`). Free bools count as maximum 1. */
object LargestUpperBound : VariableSelector {
    override fun pick(session: PropagationSession, rng: Random): VarRef? {
        var best: VarRef? = null
        var bestUb = Int.MIN_VALUE
        val problem = session.problem
        for (v in 0 until problem.numBoolVars) {
            if (session.boolValue(v) == null && 1 > bestUb) {
                best = VarRef.Bool(v)
                bestUb = 1
            }
        }
        for (v in 0 until problem.numIntVars) {
            val d = session.intDomain(v)
            if (d.size > 1 && d.max > bestUb) {
                best = VarRef.IntVar(v)
                bestUb = d.max
            }
        }
        return best
    }
}
