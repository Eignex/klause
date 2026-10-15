package com.eignex.klause.localsearch.movesource

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MoveSourceCatalogTest {

    @Test
    fun `parse should preserve order and skip blanks`() {
        val sources = MoveSourceCatalog.parse(" violated, argmin ,, frontier ")
        assertEquals(
            listOf(ViolatedRepairs.ID, ArgminJump.ID, Frontier.ID),
            sources.map { it.source.id },
        )
        assertTrue(sources.all { it.enabled }, "configured sources default to enabled")
    }

    @Test
    fun `an unknown label should fail`() {
        val e = assertFailsWith<IllegalStateException> { MoveSourceCatalog.configured("nope") }
        assertTrue(e.message!!.contains("unknown move source 'nope'"))
    }
}
