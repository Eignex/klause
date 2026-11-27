package com.eignex.klause.lp.engine

import com.eignex.util.numbers.BigFraction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ExactLpModelTest {
    @Test
    fun `small exact integers should project and remain integer model values`() {
        for (value in listOf(-1L, 0L, 1L)) {
            for (number in listOf(ExactLpNumber.of(value), ExactLpNumber.of(BigFraction.ofLong(value)))) {
                assertEquals(BigFraction.ofLong(value), number.value)
                assertEquals(value.toDouble(), number.approximation)
                assertEquals(value, number.integerModelLong())
            }
        }
    }

    @Test
    fun `small IEEE values should retain raw bits and their input provenance`() {
        for (value in listOf(-1.0, -0.0, 0.0, 1.0)) {
            val number = ExactLpNumber.ofIeee(value)

            assertEquals(value.toRawBits(), number.ieeeBits)
            assertEquals(value.toRawBits(), number.approximation.toRawBits())
            assertNull(number.integerModelLong())
        }
    }

    @Test
    fun `invalid matrix row sequences should be rejected`() {
        val builder = LpBuilder()
        val variable = builder.addVar(0L, 10L)
        builder.addRow(intArrayOf(variable), longArrayOf(1L), Relation.LE, 4L)
        builder.addRow(intArrayOf(variable), longArrayOf(1L), Relation.LE, 5L)
        val base = assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel())

        for (indices in listOf(intArrayOf(-1), intArrayOf(2), intArrayOf(1, 0), intArrayOf(0, 0))) {
            assertFailsWith<IllegalArgumentException> {
                ExactLpModel(
                    matrix = listOf(indices.map { ExactLpEntry(it, ExactLpNumber.of(1L)) }),
                    rhs = List(base.m) { base.rhs(it) },
                    columns = List(base.numVars) { base.column(it) },
                    rows = List(base.m) { base.row(it) },
                    objective = base.objective,
                )
            }
        }
    }

    @Test
    fun `an appended row should reject duplicate structural coefficients`() {
        val builder = LpBuilder()
        val variable = builder.addVar(0L, 10L)
        val base = assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel())
        val one = ExactLpNumber.of(1L)
        val appended = LpScopedRow(
            0L, listOf(variable to one, variable to one), one, ExactLpColumn(ExactLpBounds()),
        )

        assertFailsWith<IllegalArgumentException> { base.appendScopedRow(appended) }
    }
}
