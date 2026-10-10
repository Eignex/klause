package com.eignex.klause.solver.integration

import com.eignex.klause.factor.arithmetic.ReifiedCardinality
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.AtomKind
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.pinBoolAsDecision
import com.eignex.klause.propagation.propagate
import com.eignex.klause.propagation.propagatorProjection
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ArithmeticPropagatorReifiedTest {

    @Test
    fun `aux false conflicts with body-must-hold via definitelyIn`() {
        // Body must hold under current pins (count ∈ [min, max] forced) AND aux is
        // pinned false. ReifiedCardinality's `definitelyIn` check pins aux=true, which
        // conflicts with the prior aux=false pin → Unsat surfaced via revertAndUnsat.
        val problem = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, true))),
                Clause(intArrayOf(Lit.make(1, true))),
                ReifiedCardinality(
                    auxBoolVar = 2,
                    literals = intArrayOf(Lit.make(0, true), Lit.make(1, true)),
                    min = 1,
                    max = 2,
                ),
            ),
        )
        val session = PropagationSession(problem)
        val r = session.pinBool(2, false)
        assertIs<PropagationResult.Unsat>(r)
    }

    @Test
    fun `body conflict reason is a sound witness containing the indicator literal`() {
        // aux ↔ (v0 ≥ 5). Decide aux=true at level 1, then squeeze v0 ≤ 4 → the body must hold
        // (GE 5) but cannot, so body propagation wipes v0's domain and propagate returns false.
        val factor = ReifiedLinear(
            auxBoolVar = 0,
            coeffs = intArrayOf(1),
            vars = intArrayOf(0),
            op = LinearOp.GE,
            bound = 5,
        )
        val problem = Problem(
            numBoolVars = 1,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 9)),
            factors = arrayOf<Factor>(factor),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        state.currentLevel = 1
        state.pinBoolAsDecision(0, true)
        assertTrue(state.tightenIntMax(0, 4), "squeeze v0 ≤ 4")
        assertFalse(
            problem.propagators[0].propagate(state, 0),
            "body GE 5 must be infeasible under v0 ≤ 4 with aux true",
        )

        val reason = problem.propagators[0].conflictReason(state, 0)
        assertTrue(reason != null && reason.isNotEmpty(), "must yield a non-empty clause-form reason")
        for (lit in reason) {
            assertTrue(state.litFalse(lit), "every reason literal must be false at conflict time, lit=$lit")
        }
        assertTrue(
            Lit.make(0, false) in reason.toSet(),
            "reason must thread the indicator literal ¬[aux=true], got ${reason.toList()}",
        )
    }

    @Test
    fun `eq reification conflict reason over a search-time hole is sound`() {
        val problem = Problem(
            numBoolVars = 1,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 3)),
            factors = arrayOf(),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        // aux = true at level 1, then carve 2 out of x at level 2 — a search-time interior
        // hole with x's bounds still [0, 3]: the eqTargetUnreachable conflict state.
        state.currentLevel = 1
        check(state.pinBool(0, true)) { "pin aux failed" }
        state.currentLevel = 2
        check(state.excludeIntValue(0, 2, null)) { "carve hole failed" }
        val reif = ReifiedLinear(
            auxBoolVar = 0,
            coeffs = intArrayOf(1),
            vars = intArrayOf(0),
            op = LinearOp.EQ,
            bound = 2,
        )
        val reason = reif.propagatorProjection().conflictReason(state, 0) ?: error("expected a conflict reason")
        // The feasible witness aux = true, x = 2 must satisfy the reason clause (≥ 1 literal true).
        val nbv = problem.numBoolVars
        fun satUnderWitness(lit: Int): Boolean {
            val v = Lit.variable(lit)
            val holds = if (v < nbv) {
                true // aux = true
            } else {
                val a = v - nbv
                when (state.atoms.kind[a]) {
                    AtomKind.GE -> 2 >= state.atoms.threshold[a]
                    AtomKind.LE -> 2 <= state.atoms.threshold[a]
                    AtomKind.EQ -> 2L == state.atoms.threshold[a]
                }
            }
            return holds == Lit.isPositive(lit)
        }
        assertTrue(
            reason.any { satUnderWitness(it) },
            "conflict reason must be satisfied by the feasible x=2 assignment, not a bare unit: ${reason.toList()}",
        )
    }
}
