package com.eignex.klause.lp.engine

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class ExactLpModelTest {
    @Test
    fun `invalid matrix row indices should be rejected`() {
        val one = ExactLpNumber.of(1L)
        val column = ExactLpColumn(ExactLpBounds())
        val rows = List(2) { ExactLpRow() }
        val rhs = List(rows.size) { one }
        val columns = List(rows.size + 1) { column }
        val objective = ExactLpObjective(List(columns.size) { one })

        for (indices in listOf(listOf(-1), listOf(2), listOf(1, 0), listOf(0, 0))) {
            val matrix = listOf(indices.map { row -> ExactLpEntry(row, one) })

            assertFailsWith<IllegalArgumentException>("row indices $indices") {
                ExactLpModel(matrix, rhs, columns, rows, objective)
            }
        }
    }

    @Test
    fun `an appended row should reject duplicate structural coefficients`() {
        val builder = LpBuilder()
        val variable = builder.addVar(0L, 10L)
        val model = assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel())
        val one = ExactLpNumber.of(1L)
        val appended = LpScopedRow(
            0L, listOf(variable to one, variable to one), one, ExactLpColumn(ExactLpBounds()),
        )

        assertFailsWith<IllegalArgumentException> { model.appendScopedRow(appended) }
    }
}
