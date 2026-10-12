package com.eignex.klause.factor

import com.eignex.klause.brute.BruteForceParams
import com.eignex.klause.brute.BruteForceSolver
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.AtomKind
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.bake
import com.eignex.klause.propagation.factorAt
import com.eignex.klause.propagation.holeReasonFor
import com.eignex.klause.propagation.reasonOf
import com.eignex.klause.solver.Sample
import kotlin.test.assertTrue

/**
 * Checks that every deduction's recorded reason entails it on all satisfying assignments.
 * [narrow] tightens at decision level 1, then factor 0 propagates. A failed propagation checks
 * the conflict clause instead, because an omitted premise can make learning prune solutions.
 */
object PropagationReasonOracle {

    fun assertReasonsImply(problem: Problem, label: String, narrow: (PropagationState) -> Boolean) {
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        state.currentLevel = 1
        if (!narrow(state)) return
        val before = Array(problem.numIntVars) { state.intDomains[it] }
        val boolsBefore = Array(problem.numBoolVars) { state.boolValues[it] }
        state.currentFactor = 0
        if (!state.factorAt(0).propagate(state, 0)) {
            ConflictReasonOracle.assertEntailed(problem, state, 0, label)
            return
        }
        val solutions = BruteForceSolver(problem.bake()).enumerate(BruteForceParams(randomSeed = 0L)).toList()
        for (b in 0 until problem.numBoolVars) {
            val value = state.boolValues[b] ?: continue
            if (boolsBefore[b] != null) continue
            val reason = state.reasonOf(state.boolAntecedents[b])
            for (s in solutions) {
                val implied = s.bools[b] == value ||
                    (reason ?: IntArray(0)).any { lit -> litTrueUnder(problem, state, lit, s) }
                assertTrue(
                    implied,
                    "$label: pinning b$b=$value rests on ${reason?.map { describe(state, problem, it) }}, " +
                        "which a solution ${s.bools.toList()} ${s.ints.toList()} satisfies with b$b=${s.bools[b]}",
                )
            }
        }
        for (v in 0 until problem.numIntVars) {
            val after = state.intDomains[v]
            for (k in values(before[v])) {
                if (k in after) continue
                val (recorded, holds) = when {
                    k < after.min -> state.intMinAntecedents[v] to { s: Sample -> s.ints[v] >= after.min }
                    k > after.max -> state.intMaxAntecedents[v] to { s: Sample -> s.ints[v] <= after.max }
                    else -> state.holeReasonFor(v, k) to { s: Sample -> s.ints[v] != k }
                }
                val reason = state.reasonOf(recorded)
                for (s in solutions) {
                    val implied = holds(s) ||
                        (reason ?: IntArray(0)).any { lit -> litTrueUnder(problem, state, lit, s) }
                    assertTrue(
                        implied,
                        "$label: removing $k from x$v rests on ${reason?.map { describe(state, problem, it) }}, " +
                            "which a solution ${s.ints.toList()} satisfies with x$v = ${s.ints[v]}",
                    )
                }
            }
        }
    }

    private fun values(d: IntDomain): List<Long> = (d.min..d.max).filter { it in d }

    private fun litTrueUnder(problem: Problem, state: PropagationState, lit: Int, s: Sample): Boolean =
        ConflictReasonOracle.litTrueUnder(problem, state, lit, s)

    private fun describe(state: PropagationState, problem: Problem, lit: Int): String {
        val v = Lit.variable(lit)
        if (v < problem.numBoolVars) return "b$v=${Lit.isPositive(lit)}"
        val atom = v - problem.numBoolVars
        val op = when (state.atoms.kind[atom]) {
            AtomKind.GE -> ">="
            AtomKind.LE -> "<="
            AtomKind.EQ -> "="
        }
        val text = "x${state.atoms.intVar[atom]} $op ${state.atoms.threshold[atom]}"
        return if (Lit.isPositive(lit)) text else "not($text)"
    }
}
