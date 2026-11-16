package com.eignex.klause.backtrack

import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test

@RunWith(Parameterized::class)
class LearnedMemoryProductionTest(private val cap: Int, private val native: Boolean) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "cap={0}, native={1}")
        fun parameters(): List<Array<Any>> = listOf(4000, 20_000).flatMap { cap ->
            listOf(false, true).map { native -> arrayOf<Any>(cap, native) }
        }
    }

    @Test
    fun `production cap grants should release growing tails beside protected floors`() {
        learnedMemoryProductionScenario(cap, native)
    }
}
