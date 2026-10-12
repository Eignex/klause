package com.eignex.klause.portfolio

import com.eignex.klause.localsearch.strategy.FeasibleDescent
import com.eignex.klause.solver.ProblemClass
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalSearchWorkerConfigTest {

    @Test
    fun `clausal satisfaction starts with the fixed break walk`() {
        val recipes = LocalSearchCatalog.diverse(Kind.CSP, 1, ProblemClass.Sat)

        assertEquals("probsat/plain", recipes.single().label)
    }

    @Test
    fun `clausal optimization retains its general local search leader`() {
        val recipes = LocalSearchCatalog.diverse(Kind.COP, 1, ProblemClass.Sat)

        assertEquals("cbls/fixed", recipes.single().label)
    }

    @Test
    fun `each arm family declares an explicit feasible-descent mode`() {
        val byLabel = LocalSearchCatalog.ranked(Kind.COP).associateBy { it.label }
        // CBLS and the SA family both self-own their feasible walk (CBLS descends greedily on its
        // sources, SA anneals); nothing relies on an engine-side descent.
        for (label in listOf("cbls/fixed", "sa/fixed", "sa-reheat/fixed", "sa-phased/fixed")) {
            assertEquals(FeasibleDescent.SelfOwned, byLabel.getValue(label).feasibleDescent, "'$label'")
        }
        // Violation-native finders: ratcheted as a constraint on a COP, pure finders on a CSP.
        assertEquals(FeasibleDescent.RatchetAsConstraint, byLabel.getValue("adaptive-probsat/fixed").feasibleDescent)
        assertEquals(FeasibleDescent.RatchetAsConstraint, byLabel.getValue("probsat/plain").feasibleDescent)
        assertEquals(FeasibleDescent.RatchetAsConstraint, byLabel.getValue("walksat-cc/luby").feasibleDescent)
    }
}
