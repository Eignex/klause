package com.eignex.klause.backtrack

import kotlin.test.Test

internal class LearnedMemoryProductionTest {
    @Test
    @Suppress("FunctionNaming") // Native platform tests follow the common test naming convention.
    internal fun `production cap grants should release growing tails beside protected floors`() {
        for (cap in listOf(4000, 20_000)) for (native in listOf(false, true)) {
            learnedMemoryProductionScenario(cap, native)
        }
    }
}
