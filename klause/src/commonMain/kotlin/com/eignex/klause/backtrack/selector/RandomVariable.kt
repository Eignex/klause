package com.eignex.klause.backtrack.selector

import com.eignex.klause.propagation.PropagationSession
import kotlin.random.Random

/** Uniformly random among undetermined variables. */
object RandomVariable : VariableSelector {
    override fun fresh() = this

    override fun pick(session: PropagationSession, rng: Random): VarRef? {
        val problem = session.problem
        val candidates = ArrayList<VarRef>()
        for (v in 0 until problem.numBoolVars) {
            if (session.boolValue(v) == null) candidates.add(VarRef.Bool(v))
        }
        for (v in 0 until problem.numIntVars) {
            if (session.intDomain(v).size > 1) candidates.add(VarRef.IntVar(v))
        }
        if (candidates.isEmpty()) return null
        return candidates[rng.nextInt(candidates.size)]
    }
}
