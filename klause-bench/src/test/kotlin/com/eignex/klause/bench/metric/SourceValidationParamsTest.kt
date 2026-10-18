package com.eignex.klause.bench.metric

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SourceValidationParamsTest {
    @Test
    fun `source checking should be opt in`() {
        val params = SourceValidationParams(SolverInvocation.Settings(params = listOf("seed=7")))

        assertFalse(params.requested)
        assertEquals(listOf("seed=7"), params.solverSettings.params)
    }

    @Test
    fun `source checking should not forward its flag to the solver`() {
        val settings = SolverInvocation.Settings(params = listOf("source-validation=true", "bt-arm=satOptimized"))

        val params = SourceValidationParams(settings)

        assertTrue(params.requested)
        assertEquals(listOf("bt-arm=satOptimized"), params.solverSettings.params)
        assertEquals(2, settings.params.size)
    }

    @Test
    fun `an invalid source checking flag should be rejected`() {
        assertFailsWith<IllegalArgumentException> {
            SourceValidationParams(SolverInvocation.Settings(params = listOf("source-validation=maybe")))
        }
    }
    @Test
    fun `compressed source checking should consume both bench flags`() {
        val settings = SolverInvocation.Settings(params = listOf("source-validation=true",
            "source-validation-gzip=true", "bt-arm=satOptimized"))

        val params = SourceValidationParams(settings)

        assertTrue(params.requested)
        assertTrue(params.gzip)
        assertEquals(listOf("bt-arm=satOptimized"), params.solverSettings.params)
    }

    @Test
    fun `compressed checking should require explicit source validation`() {
        assertFailsWith<IllegalArgumentException> {
            SourceValidationParams(SolverInvocation.Settings(params = listOf("source-validation-gzip=true")))
        }
    }

    @Test
    fun `an invalid compression flag should be rejected`() {
        assertFailsWith<IllegalArgumentException> {
            SourceValidationParams(SolverInvocation.Settings(params = listOf("source-validation=true",
                "source-validation-gzip=maybe")))
        }
    }
}
