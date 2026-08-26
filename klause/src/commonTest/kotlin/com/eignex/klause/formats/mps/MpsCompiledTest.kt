package com.eignex.klause.formats.mps

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.ObjectiveSense
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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
    fun `an underflowing bounded objective term carries its maximum error`() {
        val compiled = MpsModel(
            "m",
            ObjectiveSense.MINIMIZE,
            MpsObjective("cost", intArrayOf(0, 1), doubleArrayOf(1e15, 0.25), 0.0),
            listOf(
                MpsVar("x", integer = true, lower = 0.0, upper = 1.0),
                MpsVar("y", integer = true, lower = -2.0, upper = 3.0),
            ),
            emptyList(),
        ).toProblem()

        assertEquals(0.75, compiled.objectiveErrorBound)
    }

    @Test
    fun `an underflowing objective term on an unbounded column is rejected`() {
        val error = assertFailsWith<MpsFormatException> {
            MpsModel(
                "m",
                ObjectiveSense.MINIMIZE,
                MpsObjective("cost", intArrayOf(0, 1), doubleArrayOf(1e15, 0.25), 0.0),
                listOf(
                    MpsVar("x", integer = true, lower = 0.0, upper = 1.0),
                    MpsVar("y", integer = true, lower = 0.0, upper = null),
                ),
                emptyList(),
            ).toProblem()
        }

        assertTrue("unbounded column 'y'" in error.message.orEmpty())
    }

    @Test
    fun `an underflowing integer constraint remains rejected`() {
        val error = assertFailsWith<MpsFormatException> {
            lower(
                twoFinite,
                MpsConstraint("c", intArrayOf(0, 1), doubleArrayOf(1e15, 0.25), null, 1.0),
            )
        }

        assertTrue("row 'c' spans" in error.message.orEmpty())
    }

    @Test
    fun `an underflowing indicated integer constraint remains rejected`() {
        val error = assertFailsWith<MpsFormatException> {
            MpsModel(
                "m",
                ObjectiveSense.MINIMIZE,
                MpsObjective("", IntArray(0), DoubleArray(0), 0.0),
                listOf(
                    MpsVar("guard", integer = true, lower = 0.0, upper = 1.0),
                    MpsVar("x", integer = true, lower = 0.0, upper = 1.0),
                    MpsVar("y", integer = true, lower = 0.0, upper = 1.0),
                ),
                listOf(
                    MpsConstraint(
                        "c",
                        intArrayOf(1, 2),
                        doubleArrayOf(1e15, 0.25),
                        null,
                        1.0,
                        MpsIndicator(column = 0, whenOne = true),
                    ),
                ),
            ).toProblem()
        }

        assertTrue("row 'c' spans" in error.message.orEmpty())
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
