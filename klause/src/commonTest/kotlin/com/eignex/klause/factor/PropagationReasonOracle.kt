package com.eignex.klause.factor

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.AtomKind
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.factorAt
import com.eignex.klause.propagation.holeReasonFor
import com.eignex.klause.propagation.reasonOf
import com.eignex.klause.solver.Sample
import kotlin.test.fail

object PropagationReasonOracle {

    // narrow tightens at decision level 1 before factor 0 propagates; a failed propagation checks the
    // conflict clause instead, since an omitted premise there lets learning prune solutions.

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
        if (boolsBefore.indices.all { boolsBefore[it] == state.boolValues[it] } &&
            before.indices.all { before[it] == state.intDomains[it] }
        ) {
            // Keep the fixture bounds even when there is no implication to check.
            FactorPropagationOracle.sourceAssignments(problem)
            return
        }
        val implications = ArrayList<Pair<(Sample) -> Boolean, () -> String>>()
        for (b in 0 until problem.numBoolVars) {
            val value = state.boolValues[b] ?: continue
            if (boolsBefore[b] != null) continue
            val reason = state.reasonOf(state.boolAntecedents[b])?.copyOf()
            implications.add(
                { s: Sample ->
                    s.boolValue(b) == value || reason?.any { lit -> litTrueUnder(problem, state, lit, s) } == true
                } to {
                    "$label: pinning b$b=$value rests on ${reason?.map { describe(state, problem, it) }}, " +
                        "which is falsified"
                },
            )
        }
        for (v in 0 until problem.numIntVars) {
            val after = state.intDomains[v]
            for (k in values(before[v])) {
                if (k in after) continue
                val (recorded, holds) = when {
                    k < after.min -> {
                        if (k != before[v].min) continue
                        state.intMinAntecedents[v] to { s: Sample -> s.intValue(v) >= after.min }
                    }

                    k > after.max -> {
                        if (k != before[v].max) continue
                        state.intMaxAntecedents[v] to { s: Sample -> s.intValue(v) <= after.max }
                    }

                    else -> state.holeReasonFor(v, k) to { s: Sample -> s.intValue(v) != k }
                }
                val reason = state.reasonOf(recorded)?.copyOf()
                implications.add(
                    { s: Sample ->
                        holds(s) || reason?.any { lit -> litTrueUnder(problem, state, lit, s) } == true
                    } to {
                        "$label: removing $k from x$v rests on ${reason?.map { describe(state, problem, it) }}, " +
                            "which is falsified"
                    },
                )
            }
        }
        // An assignment satisfying every implication cannot refute a reason, regardless of feasibility.
        val solutions = FactorPropagationOracle.enumerateSat(problem, label, null) { sample ->
            implications.any { !it.first(sample) }
        }
        for (sample in solutions) {
            val rejected = implications.first { !it.first(sample) }
            fail("${rejected.second()} by a source solution (ints=${sample.ints.toList()}, " +
                "bools=${sample.bools.toList()})")
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
