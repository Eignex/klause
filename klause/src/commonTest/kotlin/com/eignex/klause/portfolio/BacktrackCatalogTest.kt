package com.eignex.klause.portfolio

import com.eignex.klause.solver.ProblemClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * Coverage for the backtrack worker-config catalog — the backtrack counterpart of
 * [LocalSearchWorkerConfigTest]. Guards that the typed [BacktrackArm] enum and the credit-ranked pools
 * stay in lockstep, so adding an arm to the enum without ranking it (or vice versa) fails here.
 */
class BacktrackCatalogTest {

    @Test
    fun `clausal satisfaction leads with its clause only proof recipe`() {
        val facts = ProblemFacts.assumed(Kind.CSP, ProblemClass.Sat)

        val recipes = BacktrackCatalog.ranked(Kind.CSP, facts)

        assertEquals("clausal-balanced", recipes.first().label)
    }

    @Test
    fun `clausal optimization keeps the SAT guard first`() {
        val facts = ProblemFacts.assumed(Kind.COP, ProblemClass.Sat)

        val recipes = BacktrackCatalog.ranked(Kind.COP, facts)

        assertEquals("satOptimized", recipes.first().label)
    }

    @Test
    fun `non clausal models exclude clause only recipes`() {
        for (problemClass in listOf(ProblemClass.PseudoBoolean, ProblemClass.FiniteCp, ProblemClass.Continuous)) {
            val facts = ProblemFacts.assumed(Kind.CSP, problemClass)

            val recipes = BacktrackCatalog.ranked(Kind.CSP, facts)

            assertFalse(recipes.any { it.label.startsWith("clausal-") })
        }
    }

    @Test
    fun `each arm builds by label with a matching label`() {
        // Guards the BacktrackArm.label <-> produced config label lockstep the LP arms rely on.
        for (label in BacktrackCatalog.labels(Kind.COP)) {
            assertEquals(label, BacktrackCatalog.byLabel(label).label, "arm '$label' must build by label")
        }
    }

    @Test
    fun `byLabel rejects an unknown arm`() {
        assertFailsWith<IllegalStateException> { BacktrackCatalog.byLabel("no-such-arm") }
    }
}
