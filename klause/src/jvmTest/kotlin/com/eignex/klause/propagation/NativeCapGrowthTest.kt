package com.eignex.klause.propagation

import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test

@RunWith(Parameterized::class)
class NativeCapGrowthTest(private val cap: Int) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "cap={0}")
        fun parameters(): List<Array<Any>> = listOf(arrayOf<Any>(4_000), arrayOf<Any>(20_000))
    }

    @Test
    fun `growing an initialized native window should rank admissions under tiny grants`() {
        nativeCapGrowthScenario(cap)
    }
}
