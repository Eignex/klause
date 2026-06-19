package com.eignex.klause.solver.factor

import com.eignex.klause.model.PbOp
import com.eignex.klause.solver.*
import com.eignex.klause.solver.factor.arithmetic.LinearOp
import com.eignex.klause.solver.factor.arithmetic.ReifiedLinear
import com.eignex.klause.solver.factor.arithmetic.ReifiedPseudoBoolean
import com.eignex.klause.solver.propagation.PropagationResult
import com.eignex.klause.solver.propagation.PropagationSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ReifiedEqNegationTest {

    @Test
    fun `ReifiedLinear aux=false on EQ tightens single-free-term boundary`() {
        // ReifiedLinear: aux ↔ (x + y = 5). aux=false → x + y ≠ 5.
        // Pin y=2, the body becomes "x ≠ 3". If x's domain has 3 at an endpoint, it gets shaved.
        val p = Problem(
            numBoolVars = 1,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(3, 6), IntDomain(0, 5)),
            factors = arrayOf<Factor>(
                ReifiedLinear(
                    auxBoolVar = 0,
                    coeffs = intArrayOf(1, 1),
                    vars = intArrayOf(0, 1),
                    op = LinearOp.EQ,
                    bound = 5,
                ),
            ),
        )
        val session = PropagationSession(p)
        assertIs<PropagationResult.Implied>(
            session.seed(Assumptions(bools = mapOf(0 to false), ints = mapOf(1 to 2))),
        )
        // x=3 forbidden at the min boundary of [3..6] → x.min tightens to 4.
        val dxAfter = session.intDomain(0)
        assertEquals(4, dxAfter.min, "x=3 should be shaved off the min boundary; got $dxAfter")
        assertEquals(6, dxAfter.max, "x.max untouched; got $dxAfter")
    }

    @Test
    fun `ReifiedLinear aux=false on EQ detects Unsat when body must equal bound`() {
        // x + y = 5, x in {3..3}, y in {2..2}. Both pinned → x+y=5 forced.
        // aux=false → x+y ≠ 5 must hold → Unsat.
        val p = Problem(
            numBoolVars = 1,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(3, 3), IntDomain(2, 2)),
            factors = arrayOf<Factor>(
                ReifiedLinear(
                    auxBoolVar = 0,
                    coeffs = intArrayOf(1, 1),
                    vars = intArrayOf(0, 1),
                    op = LinearOp.EQ,
                    bound = 5,
                ),
            ),
        )
        // Without aux pinning: bake-time propagation forces aux=true (since sum is exactly 5).
        val bake = p.propagate(Assumptions.None)
        val impl = assertIs<PropagationResult.Implied>(bake)
        assertEquals(true, impl.bools[0])
        // Now pin aux=false explicitly: should be Unsat.
        val r = p.propagate(Assumptions(bools = mapOf(0 to false)))
        assertIs<PropagationResult.Unsat>(r)
    }

    @Test
    fun `ReifiedPseudoBoolean aux=false on EQ rejects forced-sum-equals-bound`() {
        // sum(weights·lits) with weights {2, 3}, lits {0, 1}, both pinned true → sum = 5.
        // aux ↔ (sum = 5). With both lits true, sum=5 forced → aux must be true.
        // Pinning aux=false should yield Unsat.
        val p = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                ReifiedPseudoBoolean(
                    auxBoolVar = 2,
                    weights = intArrayOf(2, 3),
                    literals = intArrayOf(Lit.make(0, true), Lit.make(1, true)),
                    op = PbOp.EQ,
                    bound = 5,
                ),
            ),
        )
        val r = p.propagate(Assumptions(bools = mapOf(0 to true, 1 to true, 2 to false)))
        assertIs<PropagationResult.Unsat>(r)
    }

    @Test
    fun `ReifiedPseudoBoolean aux=false on EQ allows feasible non-equal sum`() {
        // weights {2, 3}, lits {0, 1}, aux=false → sum ≠ 5.
        // Pin lit0=true, lit1=false → sum = 2. aux=false is consistent.
        val p = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                ReifiedPseudoBoolean(
                    auxBoolVar = 2,
                    weights = intArrayOf(2, 3),
                    literals = intArrayOf(Lit.make(0, true), Lit.make(1, true)),
                    op = PbOp.EQ,
                    bound = 5,
                ),
            ),
        )
        val r = p.propagate(Assumptions(bools = mapOf(0 to true, 1 to false, 2 to false)))
        assertIs<PropagationResult.Implied>(r)
    }

    @Test
    fun `ReifiedPseudoBoolean aux=false on EQ prunes single-free literal at unique-sum boundary`() {
        // weights {2, 3}, lits {0, 1}. With lit0=true (contribution 2) and aux=false,
        // we need 2 + 3*lit1 ≠ 5. So 3*lit1 ≠ 3 → lit1 ≠ true → lit1 = false.
        val p = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                ReifiedPseudoBoolean(
                    auxBoolVar = 2,
                    weights = intArrayOf(2, 3),
                    literals = intArrayOf(Lit.make(0, true), Lit.make(1, true)),
                    op = PbOp.EQ,
                    bound = 5,
                ),
            ),
        )
        val r = p.propagate(Assumptions(bools = mapOf(0 to true, 2 to false)))
        val impl = assertIs<PropagationResult.Implied>(r)
        assertEquals(false, impl.bools[1], "lit1=true would force sum=5; must be false")
    }
}
