package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.PresolveShared.withSourcePassDelta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PresolveTypesTest {
    private fun openModel() = Problem(
        numBoolVars = 0,
        numIntVars = 2,
        intDomains = arrayOf(IntDomain(0, 0), IntDomain(0, 9)),
        factors = arrayOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 0)),
        openIntHi = booleanArrayOf(true, false),
    )

    private fun holedModel() = Problem(
        numBoolVars = 0,
        numIntVars = 1,
        intDomains = arrayOf(IntDomain(0, 3).excludeValues(longArrayOf(1, 2))!!),
        factors = arrayOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 0)),
    )

    private fun provedRange(problem: Problem, build: IntBounds.Tightening.() -> Unit) =
        SourceDelta(bounds = problem.intBounds.tightening().apply(build).build())

    @Test
    fun `a proved range should intersect a declared value set rather than replace it with its hull`() {
        val model = holedModel()

        val next = model.withSourcePassDelta(provedRange(model) { atLeast(0, 1) })

        val declared = next!!.declaredIntDomains.declaredOrNull(0)
        assertEquals(3, declared!!.min)
        assertEquals(3, declared.max)
    }

    @Test
    fun `a proved range that empties a declared column should refute the model`() {
        val model = holedModel()

        val proved = provedRange(model) {
            atLeast(0, 1)
            atMost(0, 2)
        }

        assertNull(model.withSourcePassDelta(proved))
    }

    @Test
    fun `an infeasible source delta should refute when applied directly`() {
        val model = openModel()

        assertNull(model.withSourcePassDelta(SourceDelta(infeasible = true)))
    }

    @Test
    fun `a proved range should not narrow the root domains it was handed`() {
        val model = openModel()
        val roots = arrayOf(IntDomain(0, 100), IntDomain(0, 9))

        provedRange(model) { atMost(0, 40) }.asPassDelta(roots)

        assertEquals(100, roots[0].max)
    }

    @Test
    fun `a delta that proves a range but rewrites no factor should not be empty`() {
        val model = openModel()

        val delta = provedRange(model) { atMost(0, 40) }

        assertEquals(false, delta.isEmpty)
    }
}
