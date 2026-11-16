package com.eignex.klause.backtrack

import kotlin.test.Test

internal class LearnedMemoryContinuationTest {
    @Test
    @Suppress("FunctionNaming") // Native platform tests follow the common test naming convention.
    internal fun `partial observations should permit bounded physical cleanup across unchanged roots`() {
        for (permanentCount in listOf(4_000, 20_000)) {
            learnedMemoryContinuationScenario(permanentCount)
        }
    }
}
