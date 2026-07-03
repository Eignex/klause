package com.eignex.klause.portfolio

import com.eignex.klause.localsearch.strategy.LsArm
import com.eignex.klause.localsearch.strategy.LsCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Coverage for the LS worker-config catalog (#699 schedule-diversity arms): the new schedule-based
 * SA arms build by label and are in the full pool, and the credit-ranked pool covers every [LsArm]
 * (so adding an arm to the enum without adding it to `ranked` fails here).
 */
class LocalSearchPortfolioTest {

    @Test
    fun `schedule-diversity SA arms build by label and are in the pool`() {
        val poolLabels = LocalSearchWorkerConfig.pool().map { it.label }.toSet()
        for (label in listOf("sa-reheat/fixed", "sa-phased/fixed")) {
            assertEquals(label, LocalSearchWorkerConfig.byLabel(label).label, "arm '$label' must build by label")
            assertTrue(label in poolLabels, "arm '$label' must be in the full pool")
        }
    }

    @Test
    fun `the credit-ranked pool covers every LsArm`() {
        assertEquals(
            LsArm.entries.map { it.label }.toSet(),
            LocalSearchWorkerConfig.pool().map { it.label }.toSet(),
            "every LsArm must be in `ranked` (and vice versa)",
        )
    }

    @Test
    fun `the SA family drives objective descent so it optimizes a COP rather than bailing`() {
        // On a COP `materialize` optimizes every arm: descent-driving recipes as-is, the rest via the
        // objective-bound ratchet. SA must be in the descent-driving set (annealing), else it would be
        // ratcheted — still optimizing, but the intent is Metropolis on the objective. This guards
        // against an SA arm silently reverting to the plain feasibility-finder form.
        val byLabel = LsCatalog.auto().associateBy { it.label }
        for (label in listOf("sa/fixed", "sa-reheat/fixed", "sa-phased/fixed")) {
            assertTrue(byLabel.getValue(label).drivesObjectiveDescent, "'$label' must drive objective descent")
        }
        // The violation-native arms don't drive descent — they are the ratchet's targets on a COP.
        assertEquals(false, byLabel.getValue("adaptive-probsat/fixed").drivesObjectiveDescent)
        assertEquals(false, byLabel.getValue("walksat-cc/luby").drivesObjectiveDescent)
    }
}
