package com.eignex.klause.cli

import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AllowanceSettingTest {
    @Test
    fun `automatic unlimited and zero settings remain distinct`() {
        val settings = listOf("auto", "unlimited", "0").map {
            takeAllowanceSetting(mutableListOf("node-limit=$it"), "node-limit")
        }

        assertIs<AllowanceSetting.Automatic>(settings[0])
        assertIs<AllowanceSetting.Unlimited>(settings[1])
        assertEquals(AllowanceSetting.Remaining(0L), settings[2])
    }

    @Test
    fun `duplicate equal settings are consumed once`() {
        val params = mutableListOf("node-limit=7", "seed=3", "node-limit=7")

        assertEquals(AllowanceSetting.Remaining(7L), takeAllowanceSetting(params, "node-limit"))
        assertEquals(listOf("seed=3"), params)
    }

    @Test
    fun `conflicting settings are rejected`() {
        assertFailsWith<CliUsageException> {
            takeAllowanceSetting(mutableListOf("node-limit=0", "node-limit=unlimited"), "node-limit")
        }
    }

    @Test
    fun `negative remaining allowance is rejected`() {
        assertFailsWith<CliUsageException> {
            takeAllowanceSetting(mutableListOf("node-limit=-1"), "node-limit")
        }
    }

    @Test
    fun `zero preparation allowance stops routing and stays exhausted for presolve`() {
        val common = CommonOptions().apply { engineParams.add("presolve-work-limit=0") }

        assertTrue(common.routingCancellation()())
        val (stop, budget) = SolveCore.presolveAllowance(common, Cancellation.Never)

        assertTrue(stop())
        assertNotNull(budget)
        assertEquals(0L, budget.remaining())
        assertSame(common.presolveBudget, budget)
        assertTrue(common.engineParams.isEmpty())
    }

    @Test
    fun `unlimited preparation work retains its separate phase deadline`() {
        val common = CommonOptions().apply { engineParams.add("presolve-work-limit=unlimited") }

        val (stop, budget) = SolveCore.presolveAllowance(common, Cancellation.Never)

        assertNull(budget)
        assertNotNull(stop.deadline())
    }
}
