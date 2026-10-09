package com.eignex.klause.bench.metric

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SourceValidationParamsTest {
    @Test
    fun `source checking is opt in`() {
        val params = SourceValidationParams(SolverInvocation.Settings(params = listOf("seed=7")))

        assertFalse(params.requested)
        assertEquals(listOf("seed=7"), params.solverSettings.params)
    }

    @Test
    fun `source checking does not forward its flag to the solver`() {
        val settings = SolverInvocation.Settings(params = listOf("source-validation=true", "bt-arm=satOptimized"))

        val params = SourceValidationParams(settings)

        assertTrue(params.requested)
        assertEquals(listOf("bt-arm=satOptimized"), params.solverSettings.params)
        assertEquals(2, settings.params.size)
    }

    @Test
    fun `an invalid source checking flag is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            SourceValidationParams(SolverInvocation.Settings(params = listOf("source-validation=maybe")))
        }
    }
}
