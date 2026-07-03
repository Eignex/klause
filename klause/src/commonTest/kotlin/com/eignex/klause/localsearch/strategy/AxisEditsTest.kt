package com.eignex.klause.localsearch.strategy

import com.eignex.klause.localsearch.acceptance.AcceptanceRule
import com.eignex.klause.localsearch.movesource.MoveSourceCatalog
import com.eignex.klause.localsearch.scoring.MoveScoring
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AxisEditsTest {

    @Test
    fun `tokens parse bare plus minus and scoped forms`() {
        val tokens = AxisEdits.tokens("break, +frontier, cbls.-violated")
        assertEquals(3, tokens.size)
        assertNull(tokens[0].selector)
        assertEquals(AxisToken.Op.SET, tokens[0].op)
        assertEquals("break", tokens[0].value)
        assertEquals(AxisToken.Op.ADD, tokens[1].op)
        assertEquals("frontier", tokens[1].value)
        assertEquals("cbls", tokens[2].selector)
        assertEquals(AxisToken.Op.REMOVE, tokens[2].op)
        assertEquals("violated", tokens[2].value)
    }

    @Test
    fun `selector matches arms by label prefix`() {
        val token = AxisEdits.tokens("cbls.break").single()
        assertTrue(token.appliesTo("cbls/fixed"))
        assertTrue(token.appliesTo("cbls-plateau/ils-basin"))
        assertTrue(!token.appliesTo("sa/fixed"))
    }

    @Test
    fun `a bare sources list force-selects exactly those sources`() {
        val current = MoveSourceCatalog.parse("violated,frontier,objective")
        val edited = AxisEdits.applySources(current, AxisEdits.tokens("argmin,frontier"))
        assertEquals(
            listOf(MoveSourceCatalog.idOf("argmin"), MoveSourceCatalog.idOf("frontier")),
            edited.map { it.source.id },
        )
    }

    @Test
    fun `plus and minus add and remove against the current sources`() {
        val current = MoveSourceCatalog.parse("violated,frontier")
        val edited = AxisEdits.applySources(current, AxisEdits.tokens("-violated,+argmin"))
        assertEquals(
            listOf(MoveSourceCatalog.idOf("frontier"), MoveSourceCatalog.idOf("argmin")),
            edited.map { it.source.id },
        )
    }

    @Test
    fun `adding a source already present is a no-op`() {
        val current = MoveSourceCatalog.parse("violated,frontier")
        val edited = AxisEdits.applySources(current, AxisEdits.tokens("+frontier"))
        assertEquals(current.map { it.source.id }, edited.map { it.source.id })
    }

    @Test
    fun `mixing a force-exactly list with plus-minus is rejected`() {
        val current = MoveSourceCatalog.parse("violated")
        assertFailsWith<IllegalArgumentException> {
            AxisEdits.applySources(current, AxisEdits.tokens("frontier,+argmin"))
        }
    }

    @Test
    fun `recipe axis edits rewrite both the satisfy and optimize strategies`() {
        val recipe = LsCatalog.byLabel("cbls/fixed")
        assertTrue(recipe.optimizeStrategy != null, "the cbls arm carries an optimize strategy")

        val rescored = recipe.withScoring(MoveScoring.Raw)
        assertEquals(MoveScoring.Raw, rescored.strategy.scoring)
        assertEquals(MoveScoring.Raw, rescored.optimizeStrategy?.scoring)

        val reaccepted = recipe.withAcceptance(AcceptanceRule.Greedy)
        assertEquals(AcceptanceRule.Greedy, reaccepted.strategy.acceptance)
        assertEquals(AcceptanceRule.Greedy, reaccepted.optimizeStrategy?.acceptance)
    }

    @Test
    fun `removing a source from a recipe drops it from the sources axis`() {
        val recipe = LsCatalog.byLabel("cbls/fixed")
        val violatedId = MoveSourceCatalog.idOf("violated")
        assertTrue(recipe.strategy.sources.any { it.source.id == violatedId }, "cbls draws violated repairs")

        val edited = recipe.withSources { AxisEdits.applySources(it, AxisEdits.tokens("-violated")) }
        assertTrue(edited.strategy.sources.none { it.source.id == violatedId })
        assertTrue(edited.optimizeStrategy?.sources?.none { it.source.id == violatedId } == true)
    }
}
