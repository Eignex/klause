package com.eignex.klause.portfolio

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
}
