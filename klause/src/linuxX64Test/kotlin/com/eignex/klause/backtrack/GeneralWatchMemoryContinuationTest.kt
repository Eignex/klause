package com.eignex.klause.backtrack

import kotlin.test.Test

internal class GeneralWatchMemoryContinuationTest {
    @Test
    @Suppress("FunctionNaming") // Native platform tests follow the common test naming convention.
    internal fun `partial observations should permit bounded watch cleanup across unchanged roots`() {
        for (permanentCount in listOf(4_000, 20_000)) {
            generalWatchMemoryContinuationScenario(permanentCount)
        }
    }
}
