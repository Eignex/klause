package com.eignex.klause.backtrack

import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test

@RunWith(Parameterized::class)
class GeneralMixedMemoryProductionTest(private val cap: Int) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "cap={0}")
        fun parameters(): List<Array<Any>> = listOf(arrayOf<Any>(4000), arrayOf<Any>(20_000))
    }

    @Test
    fun `production grants should release growing mixed tails while retaining every live owner`() {
        generalMixedMemoryProductionScenario(cap)
    }
}
