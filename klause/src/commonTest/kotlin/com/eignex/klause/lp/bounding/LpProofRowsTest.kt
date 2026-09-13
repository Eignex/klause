package com.eignex.klause.lp.bounding

import com.eignex.klause.lp.engine.LpBuilder
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.lp.engine.Sense
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LpProofRowsTest {
    @Test
    fun `a callback mutation of a copied value is detected before success`() {
        val builder = LpBuilder()
        builder.addVar(0, 10)
        repeat(2) { builder.addRow(intArrayOf(0), longArrayOf(1), Relation.LE, 5) }
        val model = builder.build(Sense.MINIMIZE)
        var visits = 0
        val index = LpProofRowIndex.create(model, LpProofRowShape(0, 2, 76), Cancellation {
            if (++visits == 5) model.csc.colVal[0] = 2
            false
        })

        assertFalse(index.unchanged())
        model.csc.colVal[0] = 1
        assertTrue(index.unchanged())
    }

    @Test
    fun `empty rows and columns retain empty coefficients`() {
        for (columns in listOf(0, 2)) {
            val builder = LpBuilder()
            repeat(columns) { builder.addVar(0, 10) }
            builder.addRow(intArrayOf(), longArrayOf(), Relation.LE, 0)
            val model = builder.build(Sense.MINIMIZE)
            val rows = LpProofRows.create(model, model, Cancellation.Never)

            assertEquals(emptyMap(), assertNotNull(rows.source).coefficients(0, Cancellation.Never))
            assertTrue(rows.unchanged(Cancellation.Never))
        }
        val empty = LpBuilder().build(Sense.MINIMIZE)
        assertTrue(LpProofRows.create(empty, empty, Cancellation.Never).unchanged(Cancellation.Never))
    }

    @Test
    fun `support changes after accepted preflight decline instead of selecting fallback`() {
        val builder = LpBuilder()
        builder.addVar(0, 10)
        builder.addRow(intArrayOf(0), longArrayOf(1), Relation.LE, 5)
        val model = builder.build(Sense.MINIMIZE)
        val shape = LpProofRowShape(0, 1, 44)

        for (change in listOf("pointer", "row")) {
            var changed = false
            assertFailsWith<LpProofRowsInvalidated> {
                LpProofRowIndex.create(model, shape, Cancellation {
                    if (!changed) {
                        changed = true
                        if (change == "pointer") model.csc.colPtr[1] = 0 else model.csc.rowIdx[0] = 1
                    }
                    false
                })
            }
            model.csc.colPtr[1] = 1
            model.csc.rowIdx[0] = 0
            assertTrue(LpProofRows.create(model, model, Cancellation.Never).unchanged(Cancellation.Never))
        }
    }

    @Test
    fun `indexed rows preserve scanner overwrites with duplicates zeros and padding`() {
        val builder = LpBuilder()
        repeat(3) { builder.addVar(0, 10) }
        repeat(3) { builder.addRow(intArrayOf(0, 1, 2), longArrayOf(1, 1, 1), Relation.LE, 10) }
        val model = builder.build(Sense.MINIMIZE)
        intArrayOf(1, 4, 4, 8).copyInto(model.csc.colPtr)
        intArrayOf(-1, 2, 0, 2, 1, 0, 0, 2, -1).copyInto(model.csc.rowIdx)
        longArrayOf(99, 7, 3, 0, 4, 2, 0, 8, 99).copyInto(model.csc.colVal)
        val rows = LpProofRows.create(model, model, Cancellation.Never)
        val index = assertNotNull(rows.source)

        for (row in 0 until model.m) {
            val expected = HashMap<Int, Long>()
            for (column in 0 until model.n) {
                model.forEachInColumn(column) { entryRow, value ->
                    if (entryRow == row) expected[column] = value
                }
            }
            assertEquals(expected, index.coefficients(row, Cancellation.Never))
            val visited = ArrayList<Pair<Int, Long>>()
            assertTrue(index.forEachCoefficient(row, Cancellation.Never) { column, value ->
                visited.add(column to value)
                true
            })
            assertEquals(expected.filterValues { it != 0L }.toList().sortedBy { it.first }, visited)
        }
        model.csc.colVal[0]++
        model.csc.rowIdx[8]--
        assertTrue(rows.unchanged(Cancellation.Never))
    }

    @Test
    fun `combined primitive budget falls back before exceeding its limit`() {
        val builder = LpBuilder()
        builder.addVar(0, 10)
        builder.addRow(intArrayOf(0), longArrayOf(1), Relation.LE, 5)
        val model = builder.build(Sense.MINIMIZE)
        val bytes = 2L * (24 + 4 * 2 + 8 + 4)

        for (budget in listOf(bytes - 1, bytes)) {
            val rows = LpProofRows.create(model, model, Cancellation.Never, budget)
            if (budget < bytes) {
                assertNull(rows.source)
                assertNull(rows.transformed)
            } else {
                assertNotNull(rows.source)
                assertNotNull(rows.transformed)
            }
            assertTrue(rows.unchanged(Cancellation.Never))
        }
    }

    @Test
    fun `unsupported support selects the scanner without indexing it`() {
        val builder = LpBuilder()
        builder.addVar(0, 10)
        builder.addRow(intArrayOf(0), longArrayOf(1), Relation.LE, 5)
        val model = builder.build(Sense.MINIMIZE)

        for (pointer in listOf(-1, 2)) {
            model.csc.colPtr[1] = pointer
            val rows = LpProofRows.create(model, model, Cancellation.Never)
            assertNull(rows.source)
            assertNull(rows.transformed)
        }
        model.csc.colPtr[1] = 1
        model.csc.rowIdx[0] = 1
        assertNull(LpProofRows.create(model, model, Cancellation.Never).source)
    }

    @Test
    fun `mutations during consumption invalidate the whole batch and later calls read afresh`() {
        val builder = LpBuilder()
        builder.addVar(0, 10)
        builder.addRow(intArrayOf(0), longArrayOf(2), Relation.LE, 5)
        val model = builder.build(Sense.MINIMIZE)

        for (change in listOf("value", "support", "pointer")) {
            val rows = LpProofRows.create(model, model, Cancellation.Never)
            assertTrue(assertNotNull(rows.source).forEachCoefficient(0, Cancellation.Never) { _, _ ->
                when (change) {
                    "value" -> model.csc.colVal[0] = 3
                    "support" -> model.csc.rowIdx[0] = 1
                    "pointer" -> model.csc.colPtr[1] = 0
                }
                true
            })
            assertFalse(rows.unchanged(Cancellation.Never), change)
            model.csc.colVal[0] = 2
            model.csc.rowIdx[0] = 0
            model.csc.colPtr[1] = 1
            assertTrue(rows.unchanged(Cancellation.Never), change)
        }
        model.csc.colVal[0] = 7
        val fresh = LpProofRows.create(model, model, Cancellation.Never)
        val coefficients = assertNotNull(fresh.source).coefficients(0, Cancellation.Never).toMutableMap()
        assertEquals(mapOf(0 to 7L), coefficients)
        coefficients[0] = 99
        assertEquals(mapOf(0 to 7L), fresh.source.coefficients(0, Cancellation.Never))
    }

    @Test
    fun `combined final comparison has no callback after checking the source`() {
        val builder = LpBuilder()
        builder.addVar(0, 10)
        builder.addRow(intArrayOf(0), longArrayOf(1), Relation.LE, 5)
        val source = builder.build(Sense.MINIMIZE)
        val transformed = builder.build(Sense.MINIMIZE)
        val rows = LpProofRows.create(source, transformed, Cancellation.Never)
        var callbacks = 0

        assertTrue(rows.unchanged(Cancellation {
            if (++callbacks > 1) source.csc.colVal[0]++
            false
        }))
        assertEquals(1, callbacks)
        assertFalse(rows.unchanged(Cancellation {
            source.csc.colVal[0]++
            false
        }))
    }

    @Test
    fun `cancellation interrupts each construction boundary without retaining a partial index`() {
        val builder = LpBuilder()
        builder.addVar(0, 10)
        builder.addRow(intArrayOf(0), longArrayOf(1), Relation.LE, 5)
        val model = builder.build(Sense.MINIMIZE)
        var calls = 0
        LpProofRows.create(model, model, Cancellation { calls++; false })

        for (stop in 1..calls) {
            var seen = 0
            assertFailsWith<LpProofRowsCancelled> {
                LpProofRows.create(model, model, Cancellation { ++seen >= stop })
            }
        }
        assertTrue(LpProofRows.create(model, model, Cancellation.Never).unchanged(Cancellation.Never))
    }
}
