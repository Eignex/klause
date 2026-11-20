package com.eignex.klause.lp.engine

import com.eignex.klause.util.ExecutionControl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class LpComponentStagingTest {
    @Test
    fun `construction reservations should narrow the live child grant`() {
        val staging = LpComponentStaging(20L, ExecutionControl.Never)
        staging.reserveConstruction(6L)

        val allowance = staging.beginConstruction()

        assertEquals(14L, allowance)
        assertEquals(6L, staging.work)
    }

    @Test
    fun `an exhausted construction reservation should not offer an unbounded grant`() {
        val staging = LpComponentStaging(6L, ExecutionControl.Never)
        staging.reserveConstruction(6L)

        val allowance = staging.beginConstruction()

        assertNull(allowance)
        assertEquals(6L, staging.reservedConstructionWork)
    }

    @Test
    fun `a failed constructor without metrics should retain its reserved charge`() {
        val staging = LpComponentStaging(20L, ExecutionControl.Never)
        staging.reserveConstruction(6L)
        staging.beginConstruction()

        staging.recordConstruction(null)

        assertEquals(6L, staging.metrics.workOps - staging.reservedConstructionWork)
    }

    @Test
    fun `a default preparation report should retain its reserved charge`() {
        val staging = LpComponentStaging(20L, ExecutionControl.Never)
        staging.reserveConstruction(6L)
        staging.beginConstruction()

        staging.recordConstruction(LpSolveMetrics())

        assertEquals(6L, staging.metrics.workOps - staging.reservedConstructionWork)
    }

    @Test
    fun `reported construction should replace its reservation exactly once`() {
        val staging = LpComponentStaging(20L, ExecutionControl.Never)
        staging.reserveConstruction(6L)
        staging.beginConstruction()

        staging.recordConstruction(LpSolveMetrics(workOps = 3L, preparationOps = 2L))

        assertEquals(5L, staging.metrics.workOps - staging.reservedConstructionWork + staging.metrics.preparationOps)
    }

    @Test
    fun `unstarted workspace initialization should release its reservation`() {
        val staging = LpComponentStaging(9L, ExecutionControl.Never)
        staging.reserveConstruction(6L, workspaceWork = 3L)

        val allowance = staging.beginConstruction()

        assertNull(allowance)
        assertEquals(0L, staging.metrics.workOps - staging.reservedConstructionWork)
    }

    @Test
    fun `started workspace initialization should remain charged after a scaling report`() {
        val staging = LpComponentStaging(20L, ExecutionControl.Never)
        staging.reserveConstruction(6L, workspaceWork = 3L)
        staging.beginConstruction()

        staging.recordConstruction(LpSolveMetrics(workOps = 3L, preparationOps = 2L))

        assertEquals(8L, staging.metrics.workOps - staging.reservedConstructionWork + staging.metrics.preparationOps)
    }

    @Test
    fun `later reservations should spend previously reported numerical work`() {
        val staging = LpComponentStaging(20L, ExecutionControl.Never)
        staging.reserveConstruction(6L)
        staging.beginConstruction()
        staging.recordConstruction(LpSolveMetrics(workOps = 10L))

        assertFailsWith<LpProjectionStop> { staging.reserveConstruction(5L) }

        assertEquals(16L, staging.work)
    }
}
