package com.eignex.klause.solver.presolve

import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.AllDifferent
import com.eignex.klause.solver.factor.Cardinality
import com.eignex.klause.solver.factor.Clause
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.propagation.PropagationResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Dual fixing (#448). Pinning a dominated variable to a bound must not change the **optimum** — each
 * test enumerates the whole assignment space, computes the minimum objective before and after, and
 * asserts they match (and that the expected variables were pinned).
 */
class DualFixTest {

    private fun isFeasible(problem: Problem, ints: IntArray): Boolean {
        var a = Assumptions.None
        for (v in 0 until problem.numIntVars) a = a.withInt(v, ints[v])
        return problem.propagate(a) !is PropagationResult.Unsat
    }

    /** Minimum of `Σ coeffs·x` over the feasible assignments of [problem], or `null` if infeasible. */
    private fun minObjective(problem: Problem, coeffs: Map<Int, Long>): Long? {
        val n = problem.numIntVars
        val ints = IntArray(n) { problem.intDomains[it].min }
        var best: Long? = null
        while (true) {
            if (isFeasible(problem, ints.copyOf())) {
                var obj = 0L
                for (v in 0 until n) obj += (coeffs[v] ?: 0L) * ints[v]
                if (best == null || obj < best) best = obj
            }
            var i = 0
            while (i < n) {
                ints[i]++
                if (ints[i] <= problem.intDomains[i].max) break
                ints[i] = problem.intDomains[i].min
                i++
            }
            if (i == n) break
        }
        return best
    }

    private fun checkDualFix(name: String, problem: Problem, coeffs: Map<Int, Long>, expectFixed: Set<Int>) {
        val out = Presolve.fixDominatedVariables(problem, coeffs)
        assertEquals(minObjective(problem, coeffs), minObjective(out, coeffs), "$name: optimum changed")
        for (v in expectFixed) {
            assertTrue(out.intDomains[v].min == out.intDomains[v].max, "$name: var $v should be pinned")
        }
        if (expectFixed.isEmpty()) assertSame(problem, out, "$name: expected no fixing")
    }

    @Test
    fun `non-objective down-safe variables are pinned to their lower bound`() {
        // x0, x1 ∈ [0,3], x0 + x1 <= 3, no objective: both appear only with +coeff in a ≤ row, so
        // lowering is always safe ⇒ both pinned to 0.
        val problem = Problem(
            0,
            2,
            Array(2) { IntDomain(0, 3) },
            listOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 3)),
        )
        checkDualFix("down-safe", problem, emptyMap(), setOf(0, 1))
        val out = Presolve.fixDominatedVariables(problem, emptyMap())
        assertEquals(0, out.intDomains[0].min)
        assertEquals(0, out.intDomains[0].max)
    }

    @Test
    fun `positive-cost down-safe variable is pinned to its lower bound`() {
        // min 2·x0, x0 + x1 <= 4. c0 = 2 ≥ 0 and lowering x0 is safe ⇒ pin x0 = 0.
        val problem = Problem(
            0,
            2,
            Array(2) { IntDomain(0, 5) },
            listOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 4)),
        )
        checkDualFix("pos-cost", problem, mapOf(0 to 2L), setOf(0))
    }

    @Test
    fun `negative-cost up-safe variable is pinned to its upper bound`() {
        // min −x0 (i.e. maximize x0), with −x0 + x1 <= 4 (x0 has a negative coeff in a ≤ row ⇒
        // raising x0 is safe). c0 = −1 ≤ 0 ⇒ pin x0 to its upper bound 5.
        val problem = Problem(
            0,
            2,
            Array(2) { IntDomain(0, 5) },
            listOf(Linear(intArrayOf(-1, 1), intArrayOf(0, 1), LinearOp.LE, 4)),
        )
        val out = Presolve.fixDominatedVariables(problem, mapOf(0 to -1L))
        assertEquals(minObjective(problem, mapOf(0 to -1L)), minObjective(out, mapOf(0 to -1L)), "optimum changed")
        assertEquals(5, out.intDomains[0].min)
        assertEquals(5, out.intDomains[0].max)
    }

    @Test
    fun `a variable that is neither up- nor down-safe is left free`() {
        // x0 appears with +coeff in both a ≤ row (lowering safe) and a ≥ row (raising safe) ⇒ neither
        // direction is globally safe, so x0 is not pinned. x1 / x2 sit only in their own row, so they
        // are pinned — assert x0 specifically stays free.
        val problem = Problem(
            0,
            3,
            Array(3) { IntDomain(0, 4) },
            listOf(
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 4),
                Linear(intArrayOf(1, 1), intArrayOf(0, 2), LinearOp.GE, 2),
            ),
        )
        val out = Presolve.fixDominatedVariables(problem, emptyMap())
        assertEquals(minObjective(problem, emptyMap()), minObjective(out, emptyMap()), "optimum changed")
        assertTrue(out.intDomains[0].min != out.intDomains[0].max, "x0 must stay free")
    }

    @Test
    fun `variables in a global constraint are excluded`() {
        // AllDifferent makes its variables' dual-fixing safety undecidable ⇒ nothing is pinned.
        val problem = Problem(
            0,
            3,
            Array(3) { IntDomain(0, 2) },
            listOf(AllDifferent(intArrayOf(0, 1, 2), domainMin = 0, domainSize = 3)),
        )
        checkDualFix("global-excluded", problem, emptyMap(), emptySet())
    }

    // ---- Boolean dual fixing (#469) ----

    private fun pos(v: Int) = Lit.make(v, true)

    /** Minimum of `Σ weights·b` over the feasible Boolean assignments, or `null` if infeasible. */
    private fun minObjectiveBools(problem: Problem, weights: Map<Int, Long>): Long? {
        val nb = problem.numBoolVars
        var best: Long? = null
        for (mask in 0 until (1 shl nb)) {
            var a = Assumptions.None
            val bits = BooleanArray(nb) { (mask shr it) and 1 == 1 }
            for (v in 0 until nb) a = a.withBool(v, bits[v])
            if (problem.propagate(a) is PropagationResult.Unsat) continue
            var obj = 0L
            for (v in 0 until nb) if (bits[v]) obj += weights[v] ?: 0L
            if (best == null || obj < best) best = obj
        }
        return best
    }

    private fun hasUnit(problem: Problem, lit: Int) =
        problem.factors.any { it is Clause && it.literals.size == 1 && it.literals[0] == lit }

    @Test
    fun `pure-positive boolean is fixed true`() {
        // b0, b1 appear only positively (in a single clause) ⇒ setting them true satisfies it and is
        // always safe ⇒ both pinned true with a unit clause; the optimum (no objective) is preserved.
        val problem = Problem(2, 0, emptyArray(), listOf(Clause(intArrayOf(pos(0), pos(1)))))
        val out = Presolve.fixDominatedVariables(problem, emptyMap(), emptyMap())
        assertEquals(minObjectiveBools(problem, emptyMap()), minObjectiveBools(out, emptyMap()), "optimum changed")
        assertTrue(hasUnit(out, Lit.make(0, true)), "b0 should be pinned true")
        assertTrue(hasUnit(out, Lit.make(1, true)), "b1 should be pinned true")
    }

    @Test
    fun `positive-cost pure-positive boolean stays free`() {
        // b0 is pure-positive (true is safe) but costs +2, and false is unsafe (the clause may need it)
        // ⇒ it cannot be pinned either way. b1 (zero cost) is still pinned true.
        val problem = Problem(2, 0, emptyArray(), listOf(Clause(intArrayOf(pos(0), pos(1)))))
        val coeffs = mapOf(0 to 2L)
        val out = Presolve.fixDominatedVariables(problem, emptyMap(), coeffs)
        assertEquals(minObjectiveBools(problem, coeffs), minObjectiveBools(out, coeffs), "optimum changed")
        assertTrue(!hasUnit(out, Lit.make(0, true)) && !hasUnit(out, Lit.make(0, false)), "b0 must stay free")
    }

    @Test
    fun `negative-cost pure-positive boolean is fixed true`() {
        // c0 = −1 (true is beneficial) and true is safe ⇒ pin b0 true.
        val problem = Problem(2, 0, emptyArray(), listOf(Clause(intArrayOf(pos(0), pos(1)))))
        val coeffs = mapOf(0 to -1L)
        val out = Presolve.fixDominatedVariables(problem, emptyMap(), coeffs)
        assertEquals(minObjectiveBools(problem, coeffs), minObjectiveBools(out, coeffs), "optimum changed")
        assertTrue(hasUnit(out, Lit.make(0, true)), "b0 should be pinned true")
    }

    @Test
    fun `booleans in a cardinality are excluded`() {
        // A cardinality constraint isn't monotone in a single literal ⇒ its bools can't be dual-fixed.
        val problem = Problem(2, 0, emptyArray(), listOf(Cardinality(intArrayOf(pos(0), pos(1)), min = 1, max = 2)))
        assertSame(problem, Presolve.fixDominatedVariables(problem, emptyMap(), emptyMap()), "expected no fixing")
    }
}
