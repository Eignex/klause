package com.eignex.klause.solver.localsearch.movesource

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests for the sources-axis registry [MoveSourceCatalog]: labels resolve to the right
 * default-configured sources, the `sources=` parser preserves order and skips blanks, and unknown
 * labels fail loudly.
 */
class MoveSourceCatalogTest {

    @Test
    fun `every label resolves to its source id`() {
        val expected = mapOf(
            "violated" to ViolatedRepairs.ID,
            "frontier" to Frontier.ID,
            "structured" to SatisfiedStructured.ID,
            "elected" to SatisfiedStructured.ID,
            "objective" to ObjectiveSeed.ID,
            "argmin" to ArgminJump.ID,
            "stall-swaps" to StallSwaps.ID,
            "ejection-chains" to EjectionChains.ID,
            "stall-kick" to StallKick.ID,
            "pair-swap" to PairSwap.ID,
        )
        assertEquals(expected.keys, MoveSourceCatalog.labels.toSet(), "catalog labels drifted")
        for ((label, id) in expected) {
            assertEquals(id, MoveSourceCatalog.configured(label).source.id, "label '$label' wrong source")
        }
    }

    @Test
    fun `parse preserves order and skips blanks`() {
        val sources = MoveSourceCatalog.parse(" violated, argmin ,, frontier ")
        assertEquals(
            listOf(ViolatedRepairs.ID, ArgminJump.ID, Frontier.ID),
            sources.map { it.source.id },
        )
        assertTrue(sources.all { it.enabled }, "configured sources default to enabled")
    }

    @Test
    fun `empty spec yields no sources`() {
        assertTrue(MoveSourceCatalog.parse("").isEmpty())
        assertTrue(MoveSourceCatalog.parse("  , ,").isEmpty())
    }

    @Test
    fun `unknown label fails loudly`() {
        val e = assertFailsWith<IllegalStateException> { MoveSourceCatalog.configured("nope") }
        assertTrue(e.message!!.contains("unknown move source 'nope'"), "message should name the bad label")
    }
}
