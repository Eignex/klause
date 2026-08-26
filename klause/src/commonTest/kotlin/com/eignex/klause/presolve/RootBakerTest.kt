package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.table.Table
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RootBakerTest {

    private fun bake(problem: Problem, config: BakeConfig): PropagationResult = RootBaker.bake(problem, config)

    @Test
    fun `failed-literal probing pins a forced literal`() {
        // (a ∨ b), (a ∨ c), (¬b ∨ ¬c). Base propagation forces nothing; probing a=false forces b and c,
        // then (¬b ∨ ¬c) fails, so a must be true.
        val p = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(0, true), Lit.make(2, true))),
                Clause(intArrayOf(Lit.make(1, false), Lit.make(2, false))),
            ),
        )
        val baked = assertIs<PropagationResult.Implied>(bake(p, BakeConfig(probeFailedLiterals = true)))
        assertEquals(true, baked.boolValueOrNull(0), "probing should have forced a=true")
    }

    @Test
    fun `failed-literal probing detects Unsat when both polarities fail`() {
        val p = Problem(
            numBoolVars = 1,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, true))),
                Clause(intArrayOf(Lit.make(0, false))),
            ),
        )
        assertIs<PropagationResult.Unsat>(bake(p, BakeConfig(probeFailedLiterals = true)))
    }

    @Test
    fun `bound SAC tightens an int min when its lowest value is locally infeasible`() {
        // x = y, x + y ≥ 2 over [0..3]^2. Bound-SAC probing x=0 finds x+y=0 < 2 infeasible → x.min ≥ 1.
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3)),
            factors = arrayOf<Factor>(
                Linear(coeffs = intArrayOf(1, -1), vars = intArrayOf(0, 1), op = LinearOp.EQ, bound = 0),
                Linear(coeffs = intArrayOf(1, 1), vars = intArrayOf(0, 1), op = LinearOp.GE, bound = 2),
            ),
        )
        val baked = assertIs<PropagationResult.Implied>(bake(p, BakeConfig(probeIntBounds = true)))
        assertEquals(1L, baked.intMinOrNullCompat(0), "bound SAC should have lifted x.min to 1")
        assertEquals(1L, baked.intMinOrNullCompat(1), "bound SAC should have lifted y.min to 1")
    }

    @Test
    fun `interior-hole SAC excludes an unreachable middle value`() {
        // Allowed tuples (0,0) and (3,3); interior values 1, 2 are excluded by interior-hole SAC.
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3)),
            factors = arrayOf<Factor>(
                Table(xs = intArrayOf(0, 1), tuples = longArrayOf(0, 0, 3, 3)),
            ),
        )
        val baked = assertIs<PropagationResult.Implied>(
            bake(p, BakeConfig(probeIntBounds = true, probeIntHoles = true)),
        )
        val xHoles = mutableSetOf<Long>()
        baked.forEachIntHole { id, v -> if (id == 0) xHoles.add(v) }
        assertEquals(setOf(1L, 2L), xHoles, "interior-hole SAC should mark x ≠ 1 and x ≠ 2")
    }

    @Test
    fun `per-var budget caps the probe count`() {
        // x = y, x + y ≤ 3 over [0..3]^2. Only the max probe deduces anything (x=3 and x=2 both fail),
        // so an unbounded budget lands x.max at 1 while a per-var budget of 1 is spent on the min probe.
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3)),
            factors = arrayOf<Factor>(
                Linear(coeffs = intArrayOf(1, -1), vars = intArrayOf(0, 1), op = LinearOp.EQ, bound = 0),
                Linear(coeffs = intArrayOf(1, 1), vars = intArrayOf(0, 1), op = LinearOp.LE, bound = 3),
            ),
        )
        val unbounded = assertIs<PropagationResult.Implied>(bake(p, BakeConfig(probeIntBounds = true)))
        assertEquals(1L, unbounded.intMaxOrNullCompat(0), "an unbounded budget probes x.max down to 1")
        val capped = assertIs<PropagationResult.Implied>(
            bake(p, BakeConfig(probeIntBounds = true, probeBudgetPerVar = 1)),
        )
        assertEquals(3L, capped.intMaxOrNullCompat(0) ?: 3L, "a budget of 1 leaves no call for the max probe")
    }

    @Test
    fun `cancellation mid-bake yields a sound partial bake`() {
        // A cancellation that has already fired stops probing before it enters any phase, so the bake is
        // just the base bake — sound (probing only ever tightens, so skipping it forgoes tightening only).
        val cancelled = Cancellation { true }
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3)),
            factors = arrayOf<Factor>(
                Linear(coeffs = intArrayOf(1, -1), vars = intArrayOf(0, 1), op = LinearOp.EQ, bound = 0),
                Linear(coeffs = intArrayOf(1, 1), vars = intArrayOf(0, 1), op = LinearOp.GE, bound = 2),
            ),
            cancellation = cancelled,
        )
        val baked = assertIs<PropagationResult.Implied>(bake(p, BakeConfig(probeIntBounds = true)))
        assertEquals(null, baked.intMinOrNullCompat(0), "a fired cancellation should skip SAC tightening")
    }

    @Test
    fun `no tier enabled returns the base bake unchanged`() {
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3)),
            factors = arrayOf<Factor>(
                Linear(coeffs = intArrayOf(1, -1), vars = intArrayOf(0, 1), op = LinearOp.EQ, bound = 0),
                Linear(coeffs = intArrayOf(1, 1), vars = intArrayOf(0, 1), op = LinearOp.GE, bound = 2),
            ),
        )
        assertEquals(p.baked, bake(p, BakeConfig.NONE))
    }
}
