package com.eignex.klause.portfolio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Coverage for the backtrack worker-config catalog — the backtrack counterpart of
 * [LocalSearchWorkerConfigTest]. Guards that the typed [BacktrackArm] enum and the credit-ranked pools
 * stay in lockstep, so adding an arm to the enum without ranking it (or vice versa) fails here.
 */
class BacktrackWorkerConfigTest {

    @Test
    fun `the COP pool covers every BacktrackArm`() {
        assertEquals(
            BacktrackArm.entries.map { it.label }.toSet(),
            BacktrackWorkerConfig.labels(Kind.COP).toSet(),
            "every BacktrackArm must be ranked in the COP pool (and vice versa)",
        )
    }

    @Test
    fun `each arm builds by label with a matching label`() {
        // Guards the BacktrackArm.label <-> produced config label lockstep the LP arms rely on.
        for (label in BacktrackWorkerConfig.labels(Kind.COP)) {
            assertEquals(label, BacktrackWorkerConfig.byLabel(label).label, "arm '$label' must build by label")
        }
    }

    @Test
    fun `the CSP pool is a subset of the COP pool`() {
        val cop = BacktrackWorkerConfig.labels(Kind.COP).toSet()
        for (label in BacktrackWorkerConfig.labels(Kind.CSP)) {
            assertTrue(label in cop, "CSP arm '$label' must also be a COP arm")
        }
    }

    @Test
    fun `byLabel rejects an unknown arm`() {
        assertFailsWith<IllegalStateException> { BacktrackWorkerConfig.byLabel("no-such-arm") }
    }
}
