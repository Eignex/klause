package com.eignex.klause.propagation

import kotlin.test.Test

internal class NativeCapGrowthTest {
    @Test
    @Suppress("FunctionNaming") // Native platform tests follow the common test naming convention.
    internal fun `growing an initialized native window should rank admissions under tiny grants`() {
        for (cap in listOf(4_000, 20_000)) {
            nativeCapGrowthScenario(cap)
        }
    }
}
