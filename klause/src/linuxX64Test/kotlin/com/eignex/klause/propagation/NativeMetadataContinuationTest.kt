package com.eignex.klause.propagation

import kotlin.test.Test

internal class NativeMetadataContinuationTest {
    @Test
    @Suppress("FunctionNaming") // Native platform tests follow the common test naming convention.
    internal fun `growing permanent floors should not delay funded metadata release`() {
        for (permanentCount in listOf(4_000, 20_000)) {
            nativeMetadataContinuationScenario(permanentCount)
        }
    }
}
