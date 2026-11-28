package com.eignex.klause.backtrack.selector

import com.eignex.klause.ir.values
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.search.VarRef
import kotlin.random.Random

// Objective-aware value selection (`indomain_best`): the value minimising the variable's objective term
// first. With a zero coefficient this is ascending order.
internal class IndomainBest(private val objective: LinearObjective) : ValueSelector {

    override fun fresh() = this
    override fun values(session: PropagationSession, varRef: VarRef, rng: Random): Sequence<Long> = when (varRef) {
        is VarRef.Bool -> {
            val w = if (varRef.varId < objective.boolWeightValues.size) objective.boolWeightValues[varRef.varId] else 0L
            if (w >= 0L) sequenceOf(0L, 1L) else sequenceOf(1L, 0L)
        }

        is VarRef.IntVar -> {
            val c = if (varRef.varId <
                objective.intCoefficientValues.size
            ) {
                objective.intCoefficientValues[varRef.varId]
            } else {
                0L
            }
            val d = session.intDomain(varRef.varId)
            if (c >= 0L) {
                sequence { for (i in 0 until d.values.size) yield(d.values.valueAt(i)) }
            } else {
                sequence { for (v in d.max downTo d.min) if (v in d) yield(v) }
            }
        }
    }
}
