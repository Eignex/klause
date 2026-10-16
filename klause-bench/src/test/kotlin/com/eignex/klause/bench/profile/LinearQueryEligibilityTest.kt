package com.eignex.klause.bench.profile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinearQueryEligibilityTest {
    @Test
    fun `free known definitions should leave the enforced row and its implicit logical entry`() {
        val columns = List(2) { QuerySourceColumn(true, true, true, false, 0) }
        val rows = List(200) { index ->
            QueryDefinitionRow(index != 199, emptySet(), intArrayOf(0, 1), 0, 0L)
        }

        val result = LinearQueryEligibility.inspect(QueryEligibilityInput(columns, rows, true, 32, true))

        assertEquals(199, result.removedRows)
        assertEquals(1, result.retainedRows)
        assertEquals(2, result.retainedStructural)
        assertEquals(3L, result.reconstructionEntries)
        assertEquals(5L, result.continuationCells)
        assertEquals("static-input-lead-only", result.status)
        assertTrue(result.unprovedRequirements.isNotEmpty())
    }

    @Test
    fun `each bound fraction should consume both admission work steps`() {
        val columns = List(2) { QuerySourceColumn(false, true, true, true, 2) }

        val result = LinearQueryEligibility.inspect(QueryEligibilityInput(columns, emptyList(), true, 4, true))

        assertEquals(43L, result.admissionWorkUpperBound)
        assertEquals(520L, result.admissionAllocationUpperBound)
    }

    @Test
    fun `each unsafe logical row property should retain its definition`() {
        for (blocker in QueryRowBlocker.entries) {
            val row = QueryDefinitionRow(true, setOf(blocker), intArrayOf(0), 0, 0L)
            val input = QueryEligibilityInput(listOf(QuerySourceColumn(true, true, true, false, 0)),
                listOf(row), true, 1, true)

            val result = LinearQueryEligibility.inspect(input)

            assertEquals(0, result.removedRows, blocker.name)
            assertEquals(1, result.retainedStructural, blocker.name)
        }
    }

    @Test
    fun `bounded costly shifted and finite owned isolated columns should remain`() {
        for (column in listOf(
            QuerySourceColumn(false, true, true, false, 1),
            QuerySourceColumn(true, false, true, false, 0),
            QuerySourceColumn(true, true, false, false, 0),
            QuerySourceColumn(true, true, true, true, 0),
        )) {
            val result = LinearQueryEligibility.inspect(QueryEligibilityInput(listOf(column), emptyList(),
                true, 8, true))

            assertEquals(1, result.retainedStructural)
            assertEquals(0, result.restoredZeroColumns)
        }
    }

    @Test
    fun `an isolated unbounded zero origin column may be restored at zero`() {
        val column = QuerySourceColumn(true, true, true, false, 0)

        val result = LinearQueryEligibility.inspect(QueryEligibilityInput(listOf(column), emptyList(), true, 8, true))

        assertEquals(0, result.retainedStructural)
        assertEquals(1, result.restoredZeroColumns)
    }

    @Test
    fun `an inconsistent source mapping should withhold a static eligibility lead`() {
        val result = LinearQueryEligibility.inspect(QueryEligibilityInput(emptyList(), emptyList(), false, 1, true))

        assertFalse(result.staticGuards.getValue("sourceAndDefinitionMapping"))
        assertEquals("no-positive-static-eligibility-evidence", result.status)
    }

    @Test
    fun `unknown rows should remain subject to the original continuation row guard`() {
        for (count in listOf(128, 129)) {
            val rows = List(count) { QueryDefinitionRow(false, emptySet(), intArrayOf(), 0, 0L) }

            val result = LinearQueryEligibility.inspect(QueryEligibilityInput(emptyList(), rows, true, 1, true))

            assertEquals(count <= 128, result.staticGuards.getValue("continuationRows128"))
            assertEquals(count, result.retainedRows)
        }
    }

    @Test
    fun `dense continuation cells should include both logical columns and extra row storage`() {
        val columns = List(256) { QuerySourceColumn(true, true, true, true, 0) }
        val rows = List(128) { QueryDefinitionRow(false, emptySet(), intArrayOf(), 0, 0L) }

        val result = LinearQueryEligibility.inspect(QueryEligibilityInput(columns, rows, true, 1, true))

        assertEquals(128L * (384L + 128L + 1L), result.continuationCells)
        assertFalse(result.staticGuards.getValue("continuationCells65536"))
    }

    @Test
    fun `unknown premise scalars should withhold reconstruction capture bounds`() {
        val result = LinearQueryEligibility.inspect(QueryEligibilityInput(emptyList(), emptyList(), true, 1, false))

        assertNull(result.reconstructionCaptureWorkUpperBound)
        assertNull(result.reconstructionCaptureAllocationUpperBound)
        assertFalse(result.staticGuards.getValue("premiseScalarBoundsKnown"))
    }

    @Test
    fun `oversized original input bits should fail the unchanged scalar guard`() {
        for (bits in listOf(4096, 4097)) {
            val result = LinearQueryEligibility.inspect(QueryEligibilityInput(emptyList(), emptyList(), true, bits, true))

            assertEquals(bits <= 4096, result.staticGuards.getValue("inputBits4096"))
        }
    }

    @Test
    fun `retained coordinates should obey the unchanged column guard`() {
        for (count in listOf(4096, 4097)) {
            val columns = List(count) { QuerySourceColumn(true, true, true, true, 0) }

            val result = LinearQueryEligibility.inspect(QueryEligibilityInput(columns, emptyList(), true, 1, true))

            assertEquals(count <= 4096, result.staticGuards.getValue("continuationColumns4096"))
            assertEquals(count <= 4096, result.staticGuards.getValue("reconstructionCoordinates4096"))
        }
    }

    @Test
    fun `reconstruction entry admission should include implicit logical entries at its boundary`() {
        val columns = List(4096) { QuerySourceColumn(true, true, true, true, 0) }
        for (extra in listOf(0, 1)) {
            val rows = List(16) { row -> QueryDefinitionRow(false, emptySet(),
                IntArray(4095 + if (row == 0) extra else 0) { it }, 0, 0L) }

            val result = LinearQueryEligibility.inspect(QueryEligibilityInput(columns, rows, true, 1, true))

            assertEquals(65536L + extra, result.reconstructionEntries)
            assertEquals(extra == 0,
                result.staticGuards.getValue("reconstructionEntries65536IncludingLogicals"))
        }
    }

}
