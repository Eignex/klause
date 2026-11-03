package com.eignex.klause.backtrack

import com.eignex.klause.backtrack.selector.InputOrder
import com.eignex.klause.backtrack.selector.VariableSelector
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.search.VarRef
import kotlin.random.Random

internal class PrivateRebuildFreeSelector(private val columns: IntArray) : VariableSelector {
    override fun fresh(): VariableSelector = this

    override fun pick(session: PropagationSession, rng: Random): VarRef? {
        for (column in columns) if (session.needsBoolDecision(column)) return VarRef.Bool(column)
        return InputOrder.pick(session, rng)
    }
}
