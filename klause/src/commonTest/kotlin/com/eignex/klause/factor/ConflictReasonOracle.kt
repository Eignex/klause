package com.eignex.klause.factor

import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.AtomKind
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.factorAt
import com.eignex.klause.solver.Assignment
import com.eignex.klause.solver.Sample
import kotlin.test.fail

object ConflictReasonOracle {

    // state must already hold a conflict on factorId; its atom registry interprets the clause literals.

    fun assertEntailed(problem: Problem, state: PropagationState, factorId: Int, label: String = "reason") {
        val reason = state.factorAt(factorId).conflictReason(state, factorId) ?: return
        val solutions = FactorPropagationOracle.enumerateSat(problem, label, null) { sample ->
            reason.none { lit -> litTrueUnder(problem, state, lit, sample) }
        }
        for (s in solutions) {
            val satisfied = reason.any { lit -> litTrueUnder(problem, state, lit, s) }
            if (!satisfied) fail(
                "$label: reason ${reason.toList()} is falsified by a solution (ints=${s.ints.toList()}, " +
                    "bools=${s.bools.toList()}) — unsound nogood",
            )
        }
    }

    internal fun litTrueUnder(problem: Problem, state: PropagationState, lit: Int, s: Assignment): Boolean =
        litTrueUnder(problem, state, lit, { s.boolValue(it) }, { s.intValue(it) })

    internal fun litTrueUnder(problem: Problem, state: PropagationState, lit: Int, s: Sample): Boolean =
        litTrueUnder(problem, state, lit, { s.boolValue(it) }, { s.intValue(it) })

    private inline fun litTrueUnder(
        problem: Problem,
        state: PropagationState,
        lit: Int,
        boolValue: (Int) -> Boolean,
        intValue: (Int) -> Long,
    ): Boolean {
        val v = Lit.variable(lit)
        val pos = Lit.isPositive(lit)
        if (v < problem.numBoolVars) return if (pos) boolValue(v) else !boolValue(v)
        val atomId = v - problem.numBoolVars
        val value = intValue(state.atoms.intVar[atomId])
        val k = state.atoms.threshold[atomId]
        val holds = when (state.atoms.kind[atomId]) {
            AtomKind.GE -> value >= k
            AtomKind.LE -> value <= k
            AtomKind.EQ -> value == k
        }
        return if (pos) holds else !holds
    }
}
