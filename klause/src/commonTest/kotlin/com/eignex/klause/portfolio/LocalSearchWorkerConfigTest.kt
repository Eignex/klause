package com.eignex.klause.portfolio

import com.eignex.klause.localsearch.strategy.FeasibleDescent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Coverage for the LS worker-config catalog (#699 schedule-diversity arms): the new schedule-based
 * SA arms build by label and are in the full pool, and the credit-ranked pool covers every [LocalSearchArm]
 * (so adding an arm to the enum without adding it to `ranked` fails here).
 */
class LocalSearchWorkerConfigTest {

    @Test
    fun `schedule-diversity SA arms build by label and are in the pool`() {
        val poolLabels = LocalSearchWorkerConfig.ranked(Kind.COP).map { it.label }.toSet()
        for (label in listOf("sa-reheat/fixed", "sa-phased/fixed")) {
            assertEquals(label, LocalSearchWorkerConfig.byLabel(label).label, "arm '$label' must build by label")
            assertTrue(label in poolLabels, "arm '$label' must be in the full pool")
        }
    }

    @Test
    fun `the credit-ranked pool covers every LocalSearchArm`() {
        assertEquals(
            LocalSearchArm.entries.map { it.label }.toSet(),
            LocalSearchWorkerConfig.ranked(Kind.COP).map { it.label }.toSet(),
            "every LocalSearchArm must be in `ranked` (and vice versa)",
        )
    }

    @Test
    fun `the CSP pool is the COP pool minus the objective-only arm`() {
        val cop = LocalSearchCatalog.labels(Kind.COP).toSet()
        val csp = LocalSearchCatalog.labels(Kind.CSP).toSet()
        assertTrue(csp.all { it in cop }, "the CSP pool must be a subset of the COP pool")
        assertEquals(cop - "cbls-hotpair/fixed", csp, "the CSP pool drops only the objective-hot-spot arm")
    }

    @Test
    fun `each arm family declares an explicit feasible-descent mode`() {
        // On a COP `materialize` optimizes every arm per its declared FeasibleDescent — no arm falls into
        // a default descent. Pin the mode each family declares so a regression that silently changes how
        // an arm optimizes (e.g. an SA arm reverting to a plain finder) fails here.
        val byLabel = LocalSearchCatalog.ranked(Kind.COP).associateBy { it.label }
        // CBLS and the SA family both self-own their feasible walk (CBLS descends greedily on its
        // sources, SA anneals); nothing relies on an engine-side descent.
        for (label in listOf("cbls/fixed", "sa/fixed", "sa-reheat/fixed", "sa-phased/fixed")) {
            assertEquals(FeasibleDescent.SelfOwned, byLabel.getValue(label).feasibleDescent, "'$label'")
        }
        // Violation-native finders: ratcheted as a constraint on a COP, pure finders on a CSP.
        assertEquals(FeasibleDescent.RatchetAsConstraint, byLabel.getValue("adaptive-probsat/fixed").feasibleDescent)
        assertEquals(FeasibleDescent.RatchetAsConstraint, byLabel.getValue("walksat-cc/luby").feasibleDescent)
    }
}
