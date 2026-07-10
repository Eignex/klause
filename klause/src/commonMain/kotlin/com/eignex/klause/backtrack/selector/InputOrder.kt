package com.eignex.klause.backtrack.selector

import com.eignex.klause.propagation.PropagationSession
import kotlin.random.Random

/** First unpinned bool, else first int with domain size > 1, in variable-id order. */
object InputOrder : VariableSelector {
    override fun fresh() = this

    override fun pick(session: PropagationSession, rng: Random): VarRef? {
        val problem = session.problem
        for (v in 0 until problem.numBoolVars) {
            if (session.boolValue(v) == null) return VarRef.Bool(v)
        }
        for (v in 0 until problem.numIntVars) {
            if (session.intDomain(v).size > 1) return VarRef.IntVar(v)
        }
        return null
    }
}
