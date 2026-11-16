package com.eignex.klause.backtrack

import kotlin.test.Test

internal class GeneralMixedMemoryProductionTest {
    @Test
    @Suppress("FunctionNaming") // Native platform tests follow the common test naming convention.
    internal fun `production grants should release growing mixed tails while retaining every live owner`() {
        for (cap in listOf(4000, 20_000)) {
            generalMixedMemoryProductionScenario(cap)
        }
    }
}
