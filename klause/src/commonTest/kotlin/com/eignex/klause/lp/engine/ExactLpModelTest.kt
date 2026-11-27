package com.eignex.klause.lp.engine

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class ExactLpModelTest {
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
