package com.eignex.klause.propagation

import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test

@RunWith(Parameterized::class)
class NativeMetadataContinuationTest(private val permanentCount: Int) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "permanentCount={0}")
        fun parameters(): List<Array<Any>> = listOf(arrayOf<Any>(4_000), arrayOf<Any>(20_000))
    }

    @Test
    fun `growing permanent floors should not delay funded metadata release`() {
        nativeMetadataContinuationScenario(permanentCount)
    }
}
