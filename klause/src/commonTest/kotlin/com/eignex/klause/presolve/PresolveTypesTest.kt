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

    /** One column open above, declared `0..` , and one declared `0..9`. */
    private fun openModel() = Problem(
        numBoolVars = 0,
        numIntVars = 2,
        intDomains = arrayOf(IntDomain(0, 0), IntDomain(0, 9)),
        factors = arrayOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 0)),
        openIntHi = booleanArrayOf(true, false),
    )

    /** A column declaring `{0, 3}` — a value set a proved range must intersect, not replace. */
    private fun holedModel() = Problem(
        numBoolVars = 0,
        numIntVars = 1,
        intDomains = arrayOf(IntDomain(0, 3).excludeValues(longArrayOf(1, 2))!!),
        factors = arrayOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 0)),
    )

    private fun provedRange(problem: Problem, build: IntBounds.Tightening.() -> Unit) =
        SourceDelta(bounds = problem.intBounds.tightening().apply(build).build())

    @Test
    fun `a proved range intersects a declared value set rather than replacing it with its hull`() {
        val model = holedModel()

        val next = model.withSourcePassDelta(provedRange(model) { atLeast(0, 1) })

        val declared = next!!.declaredIntDomains.declaredOrNull(0)
        assertEquals(3, declared!!.min, "only the value the range admits remains")
        assertEquals(3, declared.max)
    }

    @Test
    fun `a proved range that empties a declared column refutes the model`() {
        val model = holedModel()

        // The endpoints 1..2 cross nothing, but the declaration admits neither value.
        val proved = provedRange(model) {
            atLeast(0, 1)
            atMost(0, 2)
        }

        assertNull(model.withSourcePassDelta(proved))
    }

    @Test
    fun `an infeasible source delta refutes when applied directly`() {
        val model = openModel()

        assertNull(model.withSourcePassDelta(SourceDelta(infeasible = true)))
    }

    @Test
    fun `a proved range does not narrow the root domains it was handed`() {
        val model = openModel()
        val roots = arrayOf(IntDomain(0, 100), IntDomain(0, 9))

        provedRange(model) { atMost(0, 40) }.asPassDelta(roots)

        assertEquals(100, roots[0].max, "the finite lane's own array is only read")
    }

    @Test
    fun `a delta that proves a range but rewrites no factor is not empty`() {
        val model = openModel()

        val delta = provedRange(model) { atMost(0, 40) }

        // An empty delta is reported unchanged and never applied, so a range-only delta counting as
        // empty would drop the bound the pass proved.
        assertEquals(false, delta.isEmpty)
    }
}
