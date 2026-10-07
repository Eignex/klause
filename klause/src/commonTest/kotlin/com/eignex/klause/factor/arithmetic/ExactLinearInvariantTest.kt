package com.eignex.klause.factor.arithmetic

import com.eignex.klause.factor.DegreeConsistencyOracle
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.propagation.bake
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.parseBigInt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertIs

class ExactLinearInvariantTest {

    private val w = parseBigInt("18446744073709551616")

    private fun problem(factor: Factor, numBoolVars: Int = 0): Problem =
        Problem(numBoolVars, 2, arrayOf(IntDomain(-4, 4), IntDomain(-4, 4)), arrayOf(factor))

    @Test
    fun `a wide row keeps its degree and deltas consistent`() {
        for (op in LinearOp.entries) {
            val row = Linear(intArrayOf(0, 1), arrayOf(w, -w * bigIntOf(3)), op, w * bigIntOf(2))
            DegreeConsistencyOracle.assertConsistent(problem(row), label = "wide $op", exactProbe = true)
        }
    }

    @Test
    fun `a reified wide row keeps its degree and deltas consistent`() {
        for (op in LinearOp.entries) {
            val row = ReifiedLinear(0, intArrayOf(0, 1), arrayOf(w, w), op, w)
            DegreeConsistencyOracle.assertConsistent(problem(row, 1), label = "reified wide $op", exactProbe = true)
        }
    }

    @Test
    fun `a row whose sum can outrun a Long is kept exactly`() {
        val wide = 1L shl 40
        val row = Linear(longArrayOf(wide, wide), intArrayOf(0, 1), LinearOp.LE, 0L)
        val p = Problem(0, 2, arrayOf(IntDomain(-wide, wide), IntDomain(-wide, wide)), arrayOf<Factor>(row))

        val state = LocalSearchState(p.bake(), Random(0))

        assertIs<ExactLinearInvariant>(state.factors[0])
    }

    @Test
    fun `a row whose sum fits a Long keeps the Long invariant`() {
        val row = Linear(longArrayOf(3, -2), intArrayOf(0, 1), LinearOp.LE, 5L)

        val state = LocalSearchState(problem(row).bake(), Random(0))

        assertIs<LinearInvariant>(state.factors[0])
    }
}
