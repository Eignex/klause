package com.eignex.klause.factor.arithmetic

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.lowering.wideDivModRows
import com.eignex.klause.propagation.bake
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.bigIntOf
import kotlin.test.Test
import kotlin.test.assertEquals

class WideDivModTest {
    @Test
    fun `aliased affine roles agree with private equivalent rows`() {
        val source = WideDivMod(DivModExpression(mapOf(0 to BIG_ONE), bigIntOf(7)), bigIntOf(3),
            DivModExpression(mapOf(1 to BIG_ONE), BIG_ZERO), DivModExpression(mapOf(2 to BIG_ONE), BIG_ZERO))
        val mapped = source.remap(VarRemap(intArrayOf(), intArrayOf(0, 0, 1))) as WideDivMod
        val domains = arrayOf(IntDomain(-3, 3), IntDomain(0, 2))
        for (factors in listOf(arrayOf<Factor>(mapped), wideDivModRows(mapped).toTypedArray<Factor>())) {
            val problem = Problem(0, 2, domains, factors)
            val assignments = BacktrackSolver(problem.bake()).enumerate(BacktrackParams(maxDecisions = 100))
                .map { it.intValue(0) to it.intValue(1) }.toSet()

            assertEquals(setOf(3L to 1L), assignments)
        }
    }
}
