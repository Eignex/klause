package com.eignex.klause.backtrack

import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test

@RunWith(Parameterized::class)
class LearnedMemoryContinuationTest(private val permanentCount: Int) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "permanentCount={0}")
        fun parameters(): List<Array<Any>> = listOf(arrayOf<Any>(4_000), arrayOf<Any>(20_000))
    }

    @Test
    fun `partial observations should permit bounded physical cleanup across unchanged roots`() {
        learnedMemoryContinuationScenario(permanentCount)
    }
}
