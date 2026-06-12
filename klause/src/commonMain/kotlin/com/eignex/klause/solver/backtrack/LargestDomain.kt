package com.eignex.klause.solver.backtrack

import com.eignex.klause.solver.propagation.PropagationSession
import kotlin.random.Random

/** Largest current domain. Useful as a contrast / for `solve` annotations that ask for it. */
object LargestDomain : VariableSelector {
    override fun pick(session: PropagationSession, rng: Random): VarRef? {
        var best: VarRef? = null
        var bestSize = 1
        val problem = session.problem
        for (v in 0 until problem.numBoolVars) {
            if (session.boolValue(v) == null && 2 > bestSize) {
                best = VarRef.Bool(v)
                bestSize = 2
            }
        }
        for (v in 0 until problem.numIntVars) {
            val size = session.intDomain(v).size
            if (size > bestSize) {
                best = VarRef.IntVar(v)
                bestSize = size
            }
        }
        return best
    }
}
