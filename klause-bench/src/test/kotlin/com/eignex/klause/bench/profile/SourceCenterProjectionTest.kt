package com.eignex.klause.bench.profile

import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.ir.DefinitionHint
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.localsearch.LocalSearchModel
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Sample
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SourceCenterProjectionTest {
    @Test
    fun `fresh definition networks retain a complete mixed center`() {
        val problem = Problem(1, 1, arrayOf(IntDomain(0, 1)),
            arrayOf(ReifiedLinear(0, longArrayOf(1), intArrayOf(0), LinearOp.EQ, 1)))
        val sweep = assertNotNull(DefinitionalSweep.fromSource(problem,
            listOf(DefinitionHint.BooleanValue(0, Lit.make(0, true)))))
        val center = Sample(booleanArrayOf(true), longArrayOf(1))

        val (projected, decline) = projectedSourceCenter(center, problem.bake(),
            VarRemap(intArrayOf(0), intArrayOf(0)), sweep, { it })

        assertNull(decline)
        assertEquals(center, projected)
    }

    @Test
    fun `complete centers follow reordered Boolean and integer maps`() {
        val problem = Problem(2, 2, Array(2) { IntDomain(0, 9) }, emptyArray())
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(0))
        val source = Sample(booleanArrayOf(true, false), longArrayOf(3, 7))
        val reconstruct = { point: Sample ->
            Sample(booleanArrayOf(point.boolValue(1), point.boolValue(0)),
                longArrayOf(point.intValue(1), point.intValue(0)))
        }

        val decline = loadSourceCenter(source, state, VarRemap(intArrayOf(1, 0), intArrayOf(1, 0)),
            reconstruct) { }

        assertNull(decline)
        assertEquals(source, reconstruct(state.assignment.snapshot()))
    }

    @Test
    fun `conflicting aliases decline rather than selecting the last source value`() {
        val state = LocalSearchState(LocalSearchModel.open(
            Problem(0, 1, arrayOf(IntDomain(0, 9)), emptyArray())), Random(0))

        val decline = loadSourceCenter(Sample(booleanArrayOf(), longArrayOf(3, 7)), state,
            VarRemap(intArrayOf(), intArrayOf(0, 0)), { it }) { }

        assertEquals("integer-alias", decline)
    }

    @Test
    fun `an unmapped searched coordinate declines instead of receiving a default value`() {
        val state = LocalSearchState(LocalSearchModel.open(
            Problem(1, 1, arrayOf(IntDomain(0, 9)), emptyArray())), Random(0))

        val decline = loadSourceCenter(Sample(booleanArrayOf(), longArrayOf(3)), state,
            VarRemap(intArrayOf(), intArrayOf(0)), { it }) { }

        assertEquals("unmapped-searched-boolean", decline)
    }

    @Test
    fun `a center conflicting with a pin declines before search`() {
        val state = LocalSearchState(LocalSearchModel.open(
            Problem(0, 1, arrayOf(IntDomain(0, 9)), emptyArray())), Random(0),
            Assumptions(ints = mapOf(0 to 4L)))

        val decline = loadSourceCenter(Sample(booleanArrayOf(), longArrayOf(3)), state,
            VarRemap(intArrayOf(), intArrayOf(0)), { it }) { }

        assertEquals("target-pin", decline)
    }
}
