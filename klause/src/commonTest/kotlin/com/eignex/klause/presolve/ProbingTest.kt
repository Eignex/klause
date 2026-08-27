package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.presolve.PresolveShared.withPassDelta
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Probing to fixpoint ([Presolve.probe]). Asserts the pass's observable output — the unit [Clause]s
 * and domain tightenings it derives — for failed literals, both-polarity bound tightening, the no-op
 * empty delta, and a one-sided-consequence soundness guard. The cap is large enough that every
 * fixture is fully probed.
 */
class ProbingTest {

    private val cap = 1024

    private fun units(problem: Problem): List<Int> =
        problem.factors.filterIsInstance<Clause>().filter { it.literals.size == 1 }.map { it.literals[0] }

    private fun probed(problem: Problem): Problem =
        problem.withPassDelta(Presolve.probe(problem, cap, Cancellation.Never), BakeConfig.NONE)

    /** Whether [ints] is feasible against [problem] (every int pinned, propagation not Unsat). */
    private fun isFeasible(problem: Problem, ints: LongArray, bools: BooleanArray): Boolean {
        var a = Assumptions.None
        for (v in 0 until problem.numIntVars) a = a.withInt(v, ints[v])
        for (v in 0 until problem.numBoolVars) a = a.withBool(v, bools[v])
        return problem.propagate(a) !is PropagationResult.Unsat
    }

    /** The set of feasible (bool-mask, int-tuple) assignments — used to assert probing changed
     *  neither the feasibility set nor (a fortiori) the optimum. */
    private fun feasibleAssignments(problem: Problem): Set<String> {
        val n = problem.numIntVars
        val nb = problem.numBoolVars
        val found = HashSet<String>()
        val ints = LongArray(n) { problem.requireFiniteIntDomains()[it].min }
        while (true) {
            for (mask in 0 until (1 shl nb)) {
                val bits = BooleanArray(nb) { (mask shr it) and 1 == 1 }
                if (isFeasible(problem, ints.copyOf(), bits)) found.add(ints.joinToString(",") + "|" + mask)
            }
            var i = 0
            while (i < n) {
                ints[i]++
                if (ints[i] <= problem.requireFiniteIntDomains()[i].max) break
                ints[i] = problem.requireFiniteIntDomains()[i].min
                i++
            }
            if (i == n) break
        }
        return found
    }

    @Test
    fun `a failed literal is emitted as a forcing unit`() {
        // Pinning b0 = true forces b1 = true (clause !b0 ∨ b1) and b1 = false (clause !b0 ∨ !b1):
        // a conflict, so b0 is false in every solution ⇒ the pass posts the unit !b0.
        val problem = Problem(
            numBoolVars = 2,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = listOf(
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false))),
            ),
        )
        val out = probed(problem)
        assertTrue(Lit.make(0, false) in units(out), "fixing b0 = true conflicts ⇒ b0 forced false")
        assertEquals(feasibleAssignments(problem), feasibleAssignments(out), "feasibility set changed")
    }

    @Test
    fun `a bound implied under both polarities is tightened`() {
        // b0 ↔ (x ≥ 2); the clause (b0 ∨ b1) and b1 ↔ (x ≥ 7) make the false polarity force x ≥ 7.
        // So x ≥ 2 under b0 = true and x ≥ 7 under b0 = false ⇒ the common lower bound x ≥ 2 holds
        // unconditionally and must be folded into x's domain. x starts at [0, 10].
        val problem = Problem(
            numBoolVars = 2,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 10)),
            factors = listOf(
                ReifiedLinear(
                    auxBoolVar = 0,
                    coeffs = intArrayOf(1),
                    vars = intArrayOf(0),
                    op = LinearOp.GE,
                    bound = 2,
                ),
                ReifiedLinear(
                    auxBoolVar = 1,
                    coeffs = intArrayOf(1),
                    vars = intArrayOf(0),
                    op = LinearOp.GE,
                    bound = 7,
                ),
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
            ),
        )
        val out = probed(problem)
        assertEquals(2L, out.requireFiniteIntDomains()[0].min, "the common lower bound x ≥ 2 is folded in")
        assertEquals(feasibleAssignments(problem), feasibleAssignments(out), "feasibility set changed")
    }

    @Test
    fun `an upper bound implied under both polarities is tightened`() {
        // b0 ↔ (x ≤ 8); the clause (b0 ∨ b1) and b1 ↔ (x ≤ 3) make the false polarity force x ≤ 3.
        // The common upper bound is the looser of the two, x ≤ 8; taking the tighter one instead would
        // cut off the feasible values 4..8. x starts at [0, 10].
        val problem = Problem(
            numBoolVars = 2,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 10)),
            factors = listOf(
                ReifiedLinear(
                    auxBoolVar = 0,
                    coeffs = intArrayOf(1),
                    vars = intArrayOf(0),
                    op = LinearOp.LE,
                    bound = 8,
                ),
                ReifiedLinear(
                    auxBoolVar = 1,
                    coeffs = intArrayOf(1),
                    vars = intArrayOf(0),
                    op = LinearOp.LE,
                    bound = 3,
                ),
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
            ),
        )
        val out = probed(problem)
        assertEquals(8L, out.requireFiniteIntDomains()[0].max, "the common upper bound x ≤ 8 is folded in")
        assertEquals(feasibleAssignments(problem), feasibleAssignments(out), "feasibility set changed")
    }

    @Test
    fun `the candidate cap stops probing before a later failed literal`() {
        // b0 is unconstrained; b1 = true conflicts against (!b1 ∨ b2), (!b1 ∨ !b2). A cap of 1 spends
        // the only candidate slot on b0, so !b1 is never derived; an uncapped run derives it.
        val problem = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = listOf(
                Clause(intArrayOf(Lit.make(1, false), Lit.make(2, true))),
                Clause(intArrayOf(Lit.make(1, false), Lit.make(2, false))),
            ),
        )
        assertTrue(Lit.make(1, false) in units(probed(problem)), "an uncapped run reaches b1 and derives !b1")
        assertTrue(Presolve.probe(problem, 1, Cancellation.Never).isEmpty, "a cap of 1 never reaches b1")
    }

    @Test
    fun `a consequence holding under only one polarity is not applied`() {
        // b0 ↔ (x ≥ 6): x ≥ 6 holds only under b0 = true; under b0 = false x stays in [0, 5]. A naive
        // probe that folded one polarity's bound would wrongly force x ≥ 6 and cut off x = 0..5 — the
        // pass must leave x's lower bound untouched.
        val problem = Problem(
            numBoolVars = 1,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 10)),
            factors = listOf(
                ReifiedLinear(
                    auxBoolVar = 0,
                    coeffs = intArrayOf(1),
                    vars = intArrayOf(0),
                    op = LinearOp.GE,
                    bound = 6,
                ),
            ),
        )
        val out = probed(problem)
        assertEquals(0L, out.requireFiniteIntDomains()[0].min, "a one-sided bound must not be applied")
        assertTrue(units(out).isEmpty(), "no literal failed")
        assertEquals(feasibleAssignments(problem), feasibleAssignments(out), "feasibility set changed")
    }

    @Test
    fun `a problem with nothing to derive is returned unchanged`() {
        // Two independent free booleans, neither polarity ever conflicts, no int coupling ⇒ identity.
        val problem = Problem(
            numBoolVars = 2,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = listOf(Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true)))),
        )
        assertTrue(
            Presolve.probe(problem, cap, Cancellation.Never).isEmpty,
            "nothing derivable is the pass's no-op signal",
        )
    }
}
