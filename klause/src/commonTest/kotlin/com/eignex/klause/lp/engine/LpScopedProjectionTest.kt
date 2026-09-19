package com.eignex.klause.lp.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LpScopedProjectionTest {
    @Test
    fun `projection exhausts setup budget before a numerical owner is allocated`() {
        val zero = ExactLpNumber.of(0L)
        val source = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds())),
            emptyList(),
            ExactLpObjective(listOf(zero)),
        )
        LpScopedSolver(LpExactState(source), workLimit = source.keySize + 2L).use { owner ->
            assertNull(owner.solve())

            assertEquals(0L, owner.metrics.createdOwners)
            assertTrue(owner.metrics.preparationWork > 0L)
        }
    }
}
