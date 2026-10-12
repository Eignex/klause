package com.eignex.klause.factor

import com.eignex.klause.brute.BruteForceParams
import com.eignex.klause.brute.BruteForceSolver
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.AtomKind
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.bake
import com.eignex.klause.propagation.factorAt
import com.eignex.klause.solver.Sample
import kotlin.test.assertTrue

/**
 * Exhaustively checks that every satisfying assignment of a factor satisfies its conflict clause.
 * [state] must already hold a conflict on [factorId]; its atom registry interprets the clause literals.
 */
object ConflictReasonOracle {

    fun assertEntailed(problem: Problem, state: PropagationState, factorId: Int, label: String = "reason") {
        val reason = state.factorAt(factorId).conflictReason(state, factorId) ?: return
        val solutions = BruteForceSolver(problem.bake()).enumerate(BruteForceParams(randomSeed = 0L)).toList()
        for (s in solutions) {
            val satisfied = reason.any { lit -> litTrueUnder(problem, state, lit, s) }
            assertTrue(
                satisfied,
                "$label: reason ${reason.toList()} is falsified by a solution (ints=${s.ints.toList()}, " +
                    "bools=${s.bools.toList()}) — unsound nogood",
            )
        }
    }

    internal fun litTrueUnder(problem: Problem, state: PropagationState, lit: Int, s: Sample): Boolean {
        val v = Lit.variable(lit)
        val pos = Lit.isPositive(lit)
        if (v < problem.numBoolVars) return if (pos) s.bools[v] else !s.bools[v]
        val atomId = v - problem.numBoolVars
        val value = s.ints[state.atoms.intVar[atomId]]
        val k = state.atoms.threshold[atomId]
        val holds = when (state.atoms.kind[atomId]) {
            AtomKind.GE -> value >= k
            AtomKind.LE -> value <= k
            AtomKind.EQ -> value == k
        }
        return if (pos) holds else !holds
    }
}
