package com.eignex.klause.factor

import com.eignex.klause.brute.BruteForceParams
import com.eignex.klause.brute.BruteForceSolver
import com.eignex.klause.propagation.AtomKind
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import kotlin.test.assertTrue

/**
 * Brute-force soundness check for [com.eignex.klause.propagation.Propagator.conflictReason]. A conflict
 * reason is a learned clause, so it must be *entailed* by the constraint: every satisfying
 * assignment of the factor must satisfy the clause. If some solution falsifies every literal the
 * reason is an unsound nogood — exactly the defect that produces false UNSAT / wrong optima, which
 * the propagation oracle ([FactorPropagationOracle]) cannot see because it only checks deductions.
 *
 * Call with a [state] already driven to a conflict on [factorId] (its `propagate` returned false);
 * the reason's atom literals are interpreted against that state's atom registry.
 */
object ConflictReasonOracle {

    fun assertEntailed(problem: Problem, state: PropagationState, factorId: Int, label: String = "reason") {
        val reason = problem.propagators[factorId].conflictReason(state, factorId) ?: return
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

    private fun litTrueUnder(problem: Problem, state: PropagationState, lit: Int, s: Sample): Boolean {
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
