package com.eignex.klause.lp.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LpLayoutStorageTest {
    @Test
    fun `compaction weighs live nonzeros instead of row counts`() {
        val one = ExactLpNumber.of(1L)
        val zero = ExactLpNumber.of(0L)
        val rows = LpScopedRows.initial(16).deactivate((1 until 16).toSet())
        val weights = (0..1).map { wideRow ->
            val model = ExactLpModel(
                List(16) { column ->
                    if (column == 0) List(16) { ExactLpEntry(it, one) } else listOf(ExactLpEntry(wideRow, one))
                },
                List(16) { zero },
                List(32) { ExactLpColumn(ExactLpBounds()) },
                List(16) { ExactLpRow() },
                ExactLpObjective(List(32) { zero }),
            )
            rows.storageWeight(model.layoutStorage)
        }

        assertEquals(weights[0].retained + weights[0].retired, weights[1].retained + weights[1].retired)
        assertFalse(weights[0].warrantsCompaction())
        assertTrue(weights[1].warrantsCompaction())
    }

    @Test
    fun `retained row premises delay reclamation of equal numeric layouts`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val rows = LpScopedRows.initial(16).deactivate((1 until 16).toSet())
        for (premiseCount in listOf(0, 300)) {
            val model = ExactLpModel(
                listOf(List(16) { ExactLpEntry(it, one) }),
                List(16) { zero },
                List(17) { ExactLpColumn(ExactLpBounds()) },
                List(16) { row ->
                    ExactLpRow(premises = if (row == 0) ExactLpPremises(
                        emptyList(),
                        List(premiseCount) { it },
                    ) else null)
                },
                ExactLpObjective(List(17) { zero }),
            )

            assertEquals(premiseCount == 0, rows.storageWeight(model.layoutStorage).warrantsCompaction())
        }
    }

    @Test
    fun `suspended ancestor storage remains live through restoration`() {
        val model = assertNotNull(LpBuilder().apply {
            val x = addVar(0L, 4L)
            addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 1L)
            addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 2L)
        }.build(Sense.MINIMIZE).authoritativeModel())
        val active = LpScopedRows(listOf(LpRowIdentity(0L, null), LpRowIdentity(1L, 1, active = false)), 1L)
        val suspended = active.suspend(setOf(0), 1)

        val storage = model.layoutStorage

        assertEquals(active.storageWeight(storage), suspended.storageWeight(storage))
        assertEquals(active.storageWeight(storage), suspended.popped(0).storageWeight(storage))
        assertTrue(suspended.deactivate(setOf(0)).storageWeight(storage).warrantsCompaction())
    }

}
