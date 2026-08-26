package com.eignex.klause.formats.mps

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.ObjectiveSense
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class MpsCompiledTest {

    private fun lower(vars: List<MpsVar>, row: MpsConstraint): Linear {
        val compiled = MpsModel(
            "m",
            ObjectiveSense.MINIMIZE,
            MpsObjective("", IntArray(0), DoubleArray(0), 0.0),
            vars,
            listOf(row),
        ).toProblem()
        return compiled.model.factors.single() as Linear
    }

    private fun boundsOf(v: MpsVar): Pair<Long, Long> {
        val compiled = MpsModel(
            "m",
            ObjectiveSense.MINIMIZE,
            MpsObjective("", IntArray(0), DoubleArray(0), 0.0),
            listOf(v),
            emptyList(),
        ).toProblem()
        return compiled.model.intBounds.lower(0) to compiled.model.intBounds.upper(0)
    }

    private val twoFinite = listOf(
        MpsVar("x", integer = true, lower = 0.0, upper = 10.0),
        MpsVar("y", integer = true, lower = 0.0, upper = 10.0),
    )

    @Test
    fun `a fractional row lowers onto the least common denominator of its decimals`() {
        val row = lower(twoFinite, MpsConstraint("c", intArrayOf(0, 1), doubleArrayOf(0.5, 0.125), null, 1.0))

        val constants = assertNotNull(row.integerConstants)
        assertEquals(listOf(500L, 125L), row.vars.indices.map { constants.coeff(it) })
        assertEquals(1000L, constants.bound)
    }

    @Test
    fun `a coefficient finer than a millionth keeps its term in the row`() {
        val row = lower(twoFinite, MpsConstraint("c", intArrayOf(0, 1), doubleArrayOf(1e-7, 1.0), null, 1.0))

        val constants = assertNotNull(row.integerConstants)
        assertEquals(listOf(1L, 10_000_000L), row.vars.indices.map { constants.coeff(it) })
    }

    @Test
    fun `a fractional upper bound on an integer column tightens to the last value it admits`() {
        assertEquals(2L, boundsOf(MpsVar("x", integer = true, lower = 0.0, upper = 2.7)).second)
    }

    @Test
    fun `a fractional lower bound on an integer column tightens to the first value it admits`() {
        assertEquals(3L, boundsOf(MpsVar("x", integer = true, lower = 2.3, upper = 9.0)).first)
    }
}
