package com.eignex.klause.localsearch

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.FunctionalObjective
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DefinitionalSweepInferTest {

    // Vars: x0=0, x1=1 (decisions); y=2 (=x0·x1); c=3 (=Σy, hinted); p=4 (=c·c, objective term).
    private fun labsShapedFactors(): Array<Factor> = arrayOf(
        Product(a = 0, b = 1, result = 2),
        Linear(intArrayOf(1, -1), intArrayOf(2, 3), LinearOp.EQ, 0),
        Product(a = 3, b = 3, result = 4),
    )

    @Test
    fun `functional objective over a product-capped sum descends the decision vars`() {
        val sweep = assertNotNull(
            DefinitionalSweep.infer(labsShapedFactors(), numIntVars = 5, definedHints = intArrayOf(3)),
        )
        val obj = assertNotNull(
            sweep.functionalObjective(intArrayOf(4), longArrayOf(1L), constant = 0L, minimize = true),
        )
        // The objective's only leaves are the decision vars, not the derived y / c / p.
        assertEquals(setOf(0, 1), (obj as FunctionalObjective).leafVars.toSet())
        // p = (x0·x1)²: for x0=1, x1=-1 the objective is 1.
        assertEquals(1.0, obj.evaluate(Sample(BooleanArray(0), longArrayOf(1, -1, -1, -1, 1))))
    }

    @Test
    fun `a hinted sum over raw decision vars is left searched`() {
        // s = x0 + x1 over raw decision vars — not the objective-decomposition shape, so deriving it
        // (excluding it from search) would only stall repair. The hint must not claim it.
        val factors = arrayOf<Factor>(Linear(intArrayOf(1, 1, -1), intArrayOf(0, 1, 2), LinearOp.EQ, 0))
        assertNull(DefinitionalSweep.infer(factors, numIntVars = 3, definedHints = intArrayOf(2)))
    }
}
