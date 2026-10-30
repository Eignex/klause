package com.eignex.klause.portfolio

import com.eignex.klause.backtrack.selector.MaxSd
import com.eignex.klause.solver.ProblemClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs

class BacktrackCatalogTest {
    @Test
    fun `small table satisfaction should admit support ordering beside ordinary recipes`() {
        val facts = ProblemFacts(ProblemFacts.assumed(Kind.CSP).profile, extensionalSupports = true) { false }

        val recipes = BacktrackCatalog.ranked(Kind.CSP, facts)

        assertEquals(listOf("satOptimized", "conflictDriven", "support-aware"), recipes.take(3).map { it.label })
        assertIs<MaxSd>(recipes[2].build(3L, null).valueSelector)
        assertEquals("propagation-guided", recipes[3].label)
        assertIs<MaxSd>(recipes[3].build(3L, null).valueSelector)
    }

    @Test
    fun `support ordering should require finite satisfaction with admitted tables`() {
        for ((kind, problemClass, tables) in listOf(
            Triple(Kind.CSP, ProblemClass.FiniteCp, false),
            Triple(Kind.COP, ProblemClass.FiniteCp, true),
            Triple(Kind.CSP, ProblemClass.Sat, true),
            Triple(Kind.CSP, ProblemClass.MixedInteger, true),
        )) {
            val facts = ProblemFacts(
                ProblemFacts.assumed(kind, problemClass).profile, extensionalSupports = tables,
            ) { false }

            val recipes = BacktrackCatalog.ranked(kind, facts)

            assertFalse(recipes.any { it.label == "support-aware" })
            assertFalse(recipes.any { it.label == "propagation-guided" })
        }
    }


    @Test
    fun `clausal satisfaction should lead with its clause only proof recipe`() {
        val facts = ProblemFacts.assumed(Kind.CSP, ProblemClass.Sat)

        val recipes = BacktrackCatalog.ranked(Kind.CSP, facts)

        assertEquals("clausal-balanced", recipes.first().label)
    }

    @Test
    fun `clausal optimization should keep the SAT guard first`() {
        val facts = ProblemFacts.assumed(Kind.COP, ProblemClass.Sat)

        val recipes = BacktrackCatalog.ranked(Kind.COP, facts)

        assertEquals("satOptimized", recipes.first().label)
    }

    @Test
    fun `non clausal models should exclude clause only recipes`() {
        for (problemClass in listOf(ProblemClass.PseudoBoolean, ProblemClass.FiniteCp, ProblemClass.Continuous)) {
            val facts = ProblemFacts.assumed(Kind.CSP, problemClass)

            val recipes = BacktrackCatalog.ranked(Kind.CSP, facts)

            assertFalse(recipes.any { it.label.startsWith("clausal-") })
        }
    }

    @Test
    fun `each arm should build by label with a matching label`() {
        for (label in BacktrackCatalog.labels(Kind.COP)) {
            assertEquals(label, BacktrackCatalog.byLabel(label).label, label)
        }
    }

    @Test
    fun `stratified core search should require an explicit optimization recipe`() {
        val recipe = BacktrackCatalog.byLabel("core-guided-stratified")

        assertEquals("core-guided-stratified", recipe.label)
        assertFalse(BacktrackCatalog.ranked(Kind.COP).any { it.label == recipe.label })
        assertFalse(BacktrackCatalog.labels(Kind.CSP).contains(recipe.label))
    }

    @Test
    fun `byLabel should reject an unknown arm`() {
        assertFailsWith<IllegalStateException> { BacktrackCatalog.byLabel("no-such-arm") }
    }
}
