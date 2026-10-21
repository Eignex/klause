package com.eignex.klause.lowering

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.table.Element
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.factorAt
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BinaryArithmeticSubstitutionTest {
    @Test
    fun `constant selection differences beyond signed words should retain exact branch values`() {
        for (value in 0L..1L) for (result in listOf(Long.MIN_VALUE, Long.MAX_VALUE)) {
            val factors = mutableListOf<Factor>(
                ReifiedLinear(0, longArrayOf(1), intArrayOf(0), LinearOp.EQ, 1),
                Element(0, 1, longArrayOf(Long.MIN_VALUE, Long.MAX_VALUE), arrIsVars = false, indexOffset = 0),
            )
            substituteBinaryArithmetic(factors, setOf(0), setOf(0), emptyMap(), Cancellation.Never)
            val state = PropagationState(Problem(1, 2,
                arrayOf(IntDomain(0, 0), IntDomain(result, result)), factors.toTypedArray()), Assumptions.None)
            state.pinBool(0, value == 1L)

            val consistent = factors.indices.all { state.factorAt(it).propagate(state, it) }

            assertEquals(result == if (value == 0L) Long.MIN_VALUE else Long.MAX_VALUE, consistent)
        }
    }

    @Test
    fun `substituted mixed rows should preserve both channel polarities and truth directions`() {
        for (channelTarget in 0L..1L) for (coefficient in listOf(-3L, 3L)) for (relation in LinearOp.entries) {
            for (value in 0L..1L) for (integer in 2L..4L) for (indicator in listOf(false, true)) {
                val factors = mutableListOf<Factor>(
                    ReifiedLinear(0, longArrayOf(1), intArrayOf(0), LinearOp.EQ, channelTarget),
                    ReifiedLinear(1, longArrayOf(coefficient, 2), intArrayOf(0, 1), relation, 7),
                )
                val selected = substituteBinaryArithmetic(factors, setOf(0), setOf(0), emptyMap(), Cancellation.Never)
                val state = PropagationState(Problem(2, 2,
                    arrayOf(IntDomain(0, 0), IntDomain(integer, integer)), factors.toTypedArray()), Assumptions.None)
                state.pinBool(0, value == channelTarget)
                state.pinBool(1, indicator)
                val activity = coefficient * value + 2 * integer
                val holds = when (relation) {
                    LinearOp.LE -> activity <= 7
                    LinearOp.GE -> activity >= 7
                    LinearOp.EQ -> activity == 7L
                    LinearOp.NE -> activity != 7L
                }

                val consistent = factors.indices.all { state.factorAt(it).propagate(state, it) }

                assertEquals(setOf(0), selected)
                assertEquals(holds == indicator, consistent,
                    "$channelTarget $coefficient $relation $value $integer $indicator")
            }
        }
    }

    @Test
    fun `a variable selection reader should retain its binary integer channel`() {
        val channel = ReifiedLinear(0, longArrayOf(1), intArrayOf(0), LinearOp.EQ, 1)
        val selection = Element(0, 1, longArrayOf(2, 3), arrIsVars = true, indexOffset = 0)
        val factors = mutableListOf<Factor>(channel, selection)

        val selected = substituteBinaryArithmetic(factors, setOf(0), setOf(0), emptyMap(), Cancellation.Never)

        assertTrue(selected.isEmpty())
        assertEquals(listOf(channel, selection), factors)
    }

    @Test
    fun `fixed selection arms should preserve their values after binary index substitution`() {
        for (value in 0L..1L) for (result in listOf(-9L, 4L, 5L)) {
            val factors = mutableListOf<Factor>(
                ReifiedLinear(0, longArrayOf(1), intArrayOf(0), LinearOp.EQ, 1),
                Element(0, 1, longArrayOf(2, 3), arrIsVars = true, indexOffset = 0),
            )
            substituteBinaryArithmetic(factors, setOf(0), setOf(0), mapOf(2 to -9L, 3 to 4L), Cancellation.Never)
            val state = PropagationState(Problem(1, 4, arrayOf(IntDomain(0, 0), IntDomain(result, result),
                IntDomain(-9, -9), IntDomain(4, 4)), factors.toTypedArray()), Assumptions.None)
            state.pinBool(0, value == 1L)

            val consistent = factors.indices.all { state.factorAt(it).propagate(state, it) }

            assertEquals(result == if (value == 0L) -9L else 4L, consistent)
        }
    }

    @Test
    fun `cancellation during rewriting should retain the original factor list`() {
        val factors = mutableListOf<Factor>(
            ReifiedLinear(0, longArrayOf(1), intArrayOf(0), LinearOp.EQ, 1),
            Linear(longArrayOf(1, 2), intArrayOf(0, 1), LinearOp.LE, 7),
        )
        val original = factors.toList()
        var polls = 0

        assertFailsWith<PolynomialSourceInterrupted> {
            substituteBinaryArithmetic(factors, setOf(0), setOf(0), emptyMap(), Cancellation { ++polls >= 12 })
        }

        assertEquals(original, factors)
    }
}
