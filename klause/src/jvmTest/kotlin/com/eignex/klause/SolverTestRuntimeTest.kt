package com.eignex.klause

import kotlin.test.Test
import kotlin.test.assertTrue

class SolverTestRuntimeTest : RuntimeInitializedTest() {
    @Test
    fun `shared runtime should be initialized before a behavior test starts`() {
        assertTrue(SolverTestRuntime.isInitialized)
    }
}
