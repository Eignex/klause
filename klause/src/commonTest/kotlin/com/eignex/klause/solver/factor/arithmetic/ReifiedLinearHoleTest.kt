package com.eignex.klause.solver.factor.arithmetic

import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.backtrack.selector.Vsids
import com.eignex.klause.solver.factor.arithmetic.LinearOp
import com.eignex.klause.solver.factor.arithmetic.ReifiedLinear
import com.eignex.klause.solver.factor.bool.Cardinality
import com.eignex.klause.solver.propagation.AtomKind
import com.eignex.klause.solver.propagation.PropagationState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Regression for #121: a forced-false `aux ↔ (x == v)` reification on an interior domain hole
 * used to leave the aux free with a bounds-only (hole-blind) reason; setting it true led to an
 * empty-domain conflict whose unsound learned clause could close the whole search (false UNSAT).
 */
class ReifiedLinearHoleTest {

    /** x has a hole domain; one `aux_v ↔ (x == v)` per candidate value. Enumeration must match
     *  the brute truth table exactly — aux false on the hole, and the channel matching x true. */
    @Test
    fun `eq reifications over a hole domain enumerate exactly the brute set`() {
        // domain {0, 2, 3}: value 1 is an interior hole.
        var dom = IntDomain(0, 3)
        dom = dom.excludeValue(1)
        val values = intArrayOf(0, 1, 2, 3)
        val factors = ArrayList<Factor>()
        for (k in values.indices) {
            factors.add(
                ReifiedLinear(
                    auxBoolVar = k,
                    coeffs = intArrayOf(1),
                    vars = intArrayOf(0),
                    op = LinearOp.EQ,
                    bound = values[k],
                ),
            )
        }
        val p = Problem(
            numBoolVars = values.size,
            numIntVars = 1,
            intDomains = arrayOf(dom),
            factors = factors.toTypedArray(),
        )
        val brute = HashSet<Pair<List<Boolean>, Int>>()
        for (x in intArrayOf(0, 2, 3)) {
            brute.add(values.map { it == x } to x)
        }
        val params = BacktrackParams(randomSeed = 1L, variableSelector = Vsids(), maxLearnedClauses = 1_000)
        val found = BacktrackSolver(p).enumerate(params).take(100_000)
            .map { it.bools.toList() to it.ints[0] }.toHashSet()
        assertEquals(brute, found, "reified eq over a hole domain must match brute enumeration")
    }

    /** The set_in shape that exposed the bug: several vars, each a batch of forced-false hole
     *  reifications OR-ed with one live value. Trivially SAT (every var takes the live value). */
    @Test
    fun `accumulated forced-false hole reifications stay satisfiable`() {
        val nVars = 6
        val perVar = 31 // values 1..31; 1..30 are holes, 31 is live
        val doms = Array(nVars) {
            var d = IntDomain(0, 34)
            for (v in 1..30) d = d.excludeValue(v)
            d
        }
        val factors = ArrayList<Factor>()
        for (i in 0 until nVars) {
            val chans = IntArray(perVar) { i * perVar + it }
            for (k in 0 until perVar) {
                factors.add(
                    ReifiedLinear(
                        auxBoolVar = chans[k],
                        coeffs = intArrayOf(1),
                        vars = intArrayOf(i),
                        op = LinearOp.EQ,
                        bound = 1 + k,
                    ),
                )
            }
            factors.add(Cardinality(IntArray(perVar) { Lit.make(chans[it], true) }, min = 1, max = perVar))
        }
        val p = Problem(nVars * perVar, nVars, doms, factors.toTypedArray())
        assertIs<SolveResult.Sat>(BacktrackSolver(p).solve(BacktrackParams(randomSeed = 1L)))
    }

    /**
     * Regression for #713: the conflict reason of a single-term `aux ↔ (x == k)` reification whose
     * target `k` is an interior hole carved *during search* (with `x`'s bounds unchanged from root —
     * the `eqTargetUnreachable` path with `aux` already true). The reason must cite the hole
     * (`[x == k]`), so it is satisfied by the feasible assignment `x = k` (where `k` is not a hole).
     * A bounds-only reason cites nothing and degenerates to the bare unit `¬aux`, which that
     * assignment violates — forbidding the reification globally and closing the search as a false
     * UNSAT. Driven directly on a hand-built state so it is independent of search order.
     */
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
        val reason = reif.asPropagator().conflictReason(state, 0) ?: error("expected a conflict reason")
        // The feasible witness aux = true, x = 2 must satisfy the reason clause (≥ 1 literal true).
        val nbv = problem.numBoolVars
        fun satUnderWitness(lit: Int): Boolean {
            val v = Lit.variable(lit)
            val holds = if (v < nbv) {
                true // aux = true
            } else {
                val a = v - nbv
                when (state.atomKind[a]) {
                    AtomKind.GE -> 2 >= state.atomThreshold[a]
                    AtomKind.LE -> 2 <= state.atomThreshold[a]
                    AtomKind.EQ -> 2 == state.atomThreshold[a]
                }
            }
            return holds == Lit.isPositive(lit)
        }
        assertTrue(
            reason.any { satUnderWitness(it) },
            "conflict reason must be satisfied by the feasible x=2 assignment, not a bare unit: ${reason.toList()}",
        )
    }

    /** Guard against over-correction: when every candidate value is a hole, the at-least-one
     *  channel constraint is genuinely unsatisfiable and must still be reported UNSAT. */
    @Test
    fun `all-hole reifications are correctly unsat`() {
        var dom = IntDomain(0, 10)
        for (v in 3..7) dom = dom.excludeValue(v)
        val values = intArrayOf(3, 4, 5, 6, 7) // every candidate is a hole
        val factors = ArrayList<Factor>()
        for (k in values.indices) {
            factors.add(
                ReifiedLinear(
                    auxBoolVar = k,
                    coeffs = intArrayOf(1),
                    vars = intArrayOf(0),
                    op = LinearOp.EQ,
                    bound = values[k],
                ),
            )
        }
        factors.add(Cardinality(IntArray(values.size) { Lit.make(it, true) }, min = 1, max = values.size))
        val p = Problem(values.size, 1, arrayOf(dom), factors.toTypedArray())
        assertIs<SolveResult.Unsat>(BacktrackSolver(p).solve(BacktrackParams(randomSeed = 1L)))
    }
}
