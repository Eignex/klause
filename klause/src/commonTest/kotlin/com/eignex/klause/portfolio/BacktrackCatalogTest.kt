package com.eignex.klause.portfolio

import com.eignex.klause.solver.ProblemClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class BacktrackCatalogTest {

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
    fun `byLabel should reject an unknown arm`() {
        assertFailsWith<IllegalStateException> { BacktrackCatalog.byLabel("no-such-arm") }
    }
}
