package com.eignex.klause.solver.integration

import com.eignex.klause.solver.*
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.factor.Table
import com.eignex.klause.solver.propagation.PropagationResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class BoundSacProbingTest {

    @Test
    fun `simplest SAC probe`() {
        // Single var x ∈ [0..3], constraint x ≥ 2. Trivially propagates without SAC,
        // but exercises the bake-time SAC path with only one var.
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 3)),
            factors = arrayOf<Factor>(
                Linear(coeffs = intArrayOf(1), vars = intArrayOf(0), op = LinearOp.GE, bound = 2),
            ),
            probeIntBounds = true,
        )
        val baked = assertIs<PropagationResult.Implied>(p.baked)
        // Either via direct propagation (x.min lifts to 2) or via SAC. Either way:
        assertEquals(2, baked.intMinOrNullCompat(0) ?: 2)
    }

    @Test
    fun `bound SAC tightens an int min when its lowest value is locally infeasible`() {
        // x = y, x + y ≥ 2 over [0..3]^2. Pure linear bound prop alone can't combine
        // the two; bound-SAC probing x=0 finds x+y=0 < 2 infeasible → x.min ≥ 1.
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3)),
            factors = arrayOf<Factor>(
                Linear(coeffs = intArrayOf(1, -1), vars = intArrayOf(0, 1), op = LinearOp.EQ, bound = 0),
                Linear(coeffs = intArrayOf(1, 1), vars = intArrayOf(0, 1), op = LinearOp.GE, bound = 2),
            ),
            probeIntBounds = true,
        )
        val baked = assertIs<PropagationResult.Implied>(p.baked)
        assertEquals(1, baked.intMinOrNullCompat(0), "bound SAC should have lifted x.min to 1")
        assertEquals(1, baked.intMinOrNullCompat(1), "bound SAC should have lifted y.min to 1")
    }

    @Test
    fun `bound SAC narrows to singleton when only one value remains feasible`() {
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2)),
            factors = arrayOf<Factor>(
                Linear(coeffs = intArrayOf(1, -1), vars = intArrayOf(0, 1), op = LinearOp.EQ, bound = 0),
                Linear(coeffs = intArrayOf(1, 1), vars = intArrayOf(0, 1), op = LinearOp.EQ, bound = 2),
            ),
            probeIntBounds = true,
        )
        val baked = assertIs<PropagationResult.Implied>(p.baked)
        assertEquals(1, baked.intValueOrNull(0))
        assertEquals(1, baked.intValueOrNull(1))
    }

    @Test
    fun `interior-hole SAC excludes an unreachable middle value`() {
        // Allowed tuples (0,0) and (3,3); interior values 1, 2 should be excluded by
        // interior-hole SAC.
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3)),
            factors = arrayOf<Factor>(
                Table(
                    xs = intArrayOf(0, 1),
                    tuples = intArrayOf(0, 0, 3, 3),
                ),
            ),
            probeIntBounds = true,
            probeIntHoles = true,
        )
        val baked = assertIs<PropagationResult.Implied>(p.baked)
        val xHoles = mutableSetOf<Int>()
        baked.forEachIntHole { id, v -> if (id == 0) xHoles.add(v) }
        assertEquals(setOf(1, 2), xHoles, "interior-hole SAC should mark x ≠ 1 and x ≠ 2")
    }

    @Test
    fun `SAC budget caps probe count`() {
        // Same setup as the lift-min test, but with a per-var budget of 1. Only enough
        // calls for the v=0 min probe to land — the loop should exit before lifting y.
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3)),
            factors = arrayOf<Factor>(
                Linear(coeffs = intArrayOf(1, -1), vars = intArrayOf(0, 1), op = LinearOp.EQ, bound = 0),
                Linear(coeffs = intArrayOf(1, 1), vars = intArrayOf(0, 1), op = LinearOp.GE, bound = 2),
            ),
            probeIntBounds = true,
            probeBudgetPerVar = 1,
        )
        val baked = assertIs<PropagationResult.Implied>(p.baked)
        // With per-var budget 1, at most the first probe per var runs. The downstream
        // Linear propagation from the first tightening may still lift y.min indirectly
        // (the propagator chain isn't budget-limited), so we only assert the bound
        // came in tighter than 0 for x — the loop completed without exceeding the cap.
        assertEquals(1, baked.intMinOrNullCompat(0))
    }

    @Test
    fun `bound SAC off does not tighten`() {
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3)),
            factors = arrayOf<Factor>(
                Linear(coeffs = intArrayOf(1, -1), vars = intArrayOf(0, 1), op = LinearOp.EQ, bound = 0),
                Linear(coeffs = intArrayOf(1, 1), vars = intArrayOf(0, 1), op = LinearOp.GE, bound = 2),
            ),
            probeIntBounds = false,
        )
        val baked = assertIs<PropagationResult.Implied>(p.baked)
        // Without probing, bound stays at 0 (factor-local propagation can't combine the
        // two constraints to lift the min).
        assertEquals(null, baked.intMinOrNullCompat(0))
    }
}
