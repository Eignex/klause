package com.eignex.klause.solver.presolve

import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.AllDifferent
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
}
