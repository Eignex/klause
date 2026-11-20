package com.eignex.klause.bench.profile

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals

class CliPreparationCaptureTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `a model view requires ordered factor identity instead of equivalent rows`(identical: Boolean) {
        val row = Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 1)
        val first = Problem.of(0, 1, arrayOf(IntDomain(0, 2)), listOf(row))
        val otherRow = if (identical) row else Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 1)
        val second = Problem.of(0, 1, arrayOf(IntDomain(0, 2)), listOf(otherRow))

        assertEquals(identical, CliPreparationCapture.sameView(first, second))
    }
}
