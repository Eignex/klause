package com.eignex.klause.solver.backtrack

import com.eignex.klause.solver.propagation.PropagationSession
import kotlin.random.Random

/**
 * "First-fail": smallest current domain wins. Bools count as size 2 when unpinned. Tied
 * candidates are broken by variable id (bools precede ints). The classic CSP default.
 */
object SmallestDomain : VariableSelector {
    override fun pick(session: PropagationSession, rng: Random): VarRef? {
        var best: VarRef? = null
        var bestSize = Int.MAX_VALUE
        val problem = session.problem
        for (v in 0 until problem.numBoolVars) {
            if (session.boolValue(v) == null && 2 < bestSize) {
                best = VarRef.Bool(v)
                bestSize = 2
            }
        }
        for (v in 0 until problem.numIntVars) {
            val size = session.intDomain(v).size
            if (size > 1 && size < bestSize) {
                best = VarRef.IntVar(v)
                bestSize = size
            }
        }
        return best
    }
}
