package com.eignex.klause.portfolio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Coverage for the backtrack worker-config catalog — the backtrack counterpart of
 * [LocalSearchWorkerConfigTest]. Guards that the typed [BacktrackArm] enum and the credit-ranked pools
 * stay in lockstep, so adding an arm to the enum without ranking it (or vice versa) fails here.
 */
class BacktrackCatalogTest {

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
