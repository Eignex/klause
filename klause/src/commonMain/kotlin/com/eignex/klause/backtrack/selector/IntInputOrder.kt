package com.eignex.klause.backtrack.selector

import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.search.VarRef
import kotlin.random.Random

internal object IntInputOrder : VariableSelector {
    override fun fresh() = this

    override fun pick(session: PropagationSession, rng: Random): VarRef? {
        val problem = session.problem
        for (v in 0 until problem.numIntVars) {
            if (!session.intDomain(v).isFixed) return VarRef.IntVar(v)
        }
        for (v in 0 until problem.numBoolVars) {
            if (session.boolValue(v) == null) return VarRef.Bool(v)
        }
        return null
    }
}
