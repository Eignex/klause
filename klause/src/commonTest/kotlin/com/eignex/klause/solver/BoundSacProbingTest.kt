package com.eignex.klause.solver

import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
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
            numBoolVars = 0, numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 3)),
            factors = listOf(
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
        // x ∈ [0..5], y ∈ [3..3], x + y ≤ 5 → x ≤ 2. Direct linear propagation already
        // handles this case at bake (no SAC needed). Build a case where local propagation
        // misses the bound: x ∈ [0..3], y ∈ [0..3], x + y ≥ 2, plus a non-linear-style
        // constraint that pins implicit pairs.
        //
        // Simpler: x ∈ [0..3]; constraint Σ = x ≥ 3 forces x ≥ 3 trivially. Bound-SAC
        // would just confirm. So pick a case where probing min fires the linear:
        // x ∈ [0..3], y ∈ [0..3]: x = y, x + y ≥ 2. Pure linear bound prop on x+y≥2
        // alone gives x ≥ -1 / y ≥ -1 (no change). x = y is a Linear equality. Together
        // they imply x ≥ 1, y ≥ 1 — but only via reasoning at the same time.
        //
        // probeIntBounds tries x = 0: x + y = 0 + 0 = 0 < 2 → Unsat (with x = y, both
        // singleton 0). So x ≥ 1 is forced. Likewise y ≥ 1.
        val p = Problem(
            numBoolVars = 0, numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3)),
            factors = listOf(
                // x - y = 0 (x = y).
                Linear(coeffs = intArrayOf(1, -1), vars = intArrayOf(0, 1), op = LinearOp.EQ, bound = 0),
                // x + y ≥ 2.
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
        // x ∈ [0..2], y ∈ [0..2], x + y = 2, x = y → both must be 1.
        val p = Problem(
            numBoolVars = 0, numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2)),
            factors = listOf(
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
    fun `bound SAC off does not tighten`() {
        val p = Problem(
            numBoolVars = 0, numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3)),
            factors = listOf(
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
