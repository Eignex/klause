package com.eignex.klause.propagation

import com.eignex.klause.factor.arithmetic.LinearPropagator
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.bigIntOf
import com.eignex.util.numbers.compareTo
import com.eignex.util.numbers.plus
import com.eignex.util.numbers.shl
import com.eignex.util.numbers.times
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IntegerConflictRowTest {
    @Test
    fun `large coefficient arithmetic should observe cancellation before its primitive`() {
        val row = requireNotNull(IntegerConflictRow.source(intArrayOf(0, 1),
            arrayOf(bigIntOf(1) shl 4096, bigIntOf(1)), bigIntOf(0), useLo = true))
        val state = PropagationState(Problem(0, 2, Array(2) { IntDomain(0, 1) }, emptyArray()), Assumptions.None)
        state.finiteWork.cancellation = ExecutionControl { true }

        assertFailsWith<FinitePropagationInterrupted> { row.minimum(state) }
    }

    @Test
    fun `exact resolution should retain growth through eighty eliminations`() {
        var row = requireNotNull(IntegerConflictRow.source(LinearPropagator(intArrayOf(), intArrayOf(0, 1),
            longArrayOf(Long.MAX_VALUE, 1), intArrayOf(0, 1), LinearOp.LE, 0), useLo = true))
        var expected = bigIntOf(1)
        var pivot = 0
        repeat(80) { step ->
            val next = step + 2
            val reason =
                requireNotNull(IntegerConflictRow.source(LinearPropagator(intArrayOf(), intArrayOf(pivot, next),
                    longArrayOf(-2, 1), intArrayOf(pivot, next), LinearOp.LE, 0), useLo = true))
            row = requireNotNull(row.resolve(reason, pivot))
            expected *= bigIntOf(2)
            pivot = next
        }

        assertEquals(expected, row.coefficient(1))
        assertEquals(bigIntOf(Long.MAX_VALUE), row.coefficient(pivot))
    }

    @Test
    fun `normalization should retain a row with hundreds of distinct terms`() {
        val width = 256
        val variables = IntArray(width) { it }
        val row = requireNotNull(IntegerConflictRow.source(LinearPropagator(intArrayOf(), variables,
            LongArray(width) { 2 }, variables, LinearOp.LE, 511), useLo = true))
        val state =
            PropagationState(Problem(0, width, Array(width) { IntDomain(0, 1) }, emptyArray()), Assumptions.None)

        assertEquals(width, row.variables.size)
        assertEquals(bigIntOf(255), row.bound)
        assertEquals(bigIntOf(256), row.maximum(state))
    }

    @Test
    fun `subsumption should require a stronger bound and an implied guard`() {
        val guards = listOf(Lit.NONE, Lit.make(0, true), Lit.make(0, false), Lit.make(1, true))
        for (guard in guards) {
            for (otherGuard in guards) {
                for (bound in listOf(3L, 5L, 7L)) {
                    val retained =
                        requireNotNull(IntegerConflictRow.source(LinearPropagator(intArrayOf(), intArrayOf(0),
                            longArrayOf(2), intArrayOf(0), LinearOp.LE, 10), useLo = true, guard = guard))
                    val candidate =
                        requireNotNull(IntegerConflictRow.source(LinearPropagator(intArrayOf(), intArrayOf(0),
                            longArrayOf(1), intArrayOf(0), LinearOp.LE, bound), useLo = true, guard = otherGuard))

                    assertEquals(bound >= 5 && (guard == Lit.NONE || guard == otherGuard), retained.subsumes(candidate),
                        "guard=$guard otherGuard=$otherGuard bound=$bound")
                }
            }
        }
    }

    @Test
    fun `subsumption should keep different numerical directions`() {
        val retained = requireNotNull(IntegerConflictRow.source(LinearPropagator(intArrayOf(), intArrayOf(0, 1),
            longArrayOf(1, -1), intArrayOf(0, 1), LinearOp.LE, 0), useLo = true))
        for (coefficients in listOf(longArrayOf(1, 1), longArrayOf(-1, 1), longArrayOf(2, -1))) {
            val candidate = requireNotNull(IntegerConflictRow.source(LinearPropagator(intArrayOf(), intArrayOf(0, 1),
                coefficients, intArrayOf(0, 1), LinearOp.LE, 1), useLo = true))

            assertFalse(retained.subsumes(candidate))
        }
    }

    @Test
    fun `a later term should assert when an earlier raw target is a hole`() {
        val row = requireNotNull(IntegerConflictRow.source(LinearPropagator(intArrayOf(), intArrayOf(0, 1),
            longArrayOf(2, -3), intArrayOf(0, 1), LinearOp.LE, -1), useLo = true))
        val state = PropagationState(Problem(0, 2,
            arrayOf(IntDomain(0, 10).excludeValue(5), IntDomain(0, 4)), emptyArray()), Assumptions.None)

        assertTrue(row.assertsAt(state, 0))
    }

    @Test
    fun `an assertion whose raw target is a historical hole should decline`() {
        val row = requireNotNull(IntegerConflictRow.source(LinearPropagator(intArrayOf(), intArrayOf(0),
            longArrayOf(1), intArrayOf(0), LinearOp.LE, 3), useLo = true))
        val state = PropagationState(Problem(0, 1, arrayOf(IntDomain(0, 6).excludeValue(3)), emptyArray()),
            Assumptions.None)
        state.undoLogging = true
        assertTrue(state.tightenIntMax(0, 2))

        assertFalse(row.assertsAt(state, 0))
    }

    @Test
    fun `a later interior carve should not remove an earlier raw assertion target`() {
        val row = requireNotNull(IntegerConflictRow.source(LinearPropagator(intArrayOf(), intArrayOf(0),
            longArrayOf(1), intArrayOf(0), LinearOp.LE, 3), useLo = true))
        val state = PropagationState(Problem(0, 1, arrayOf(IntDomain(0, 6)), emptyArray()), Assumptions.None)
        state.undoLogging = true
        assertTrue(state.tightenIntMin(0, 1))
        val trail = state.undo.size
        assertTrue(state.excludeIntValue(0, 3))

        assertTrue(row.assertsAt(state, trail))
        assertFalse(row.assertsAt(state, state.undo.size))
    }

    @Test
    fun `a nonnegative combination should preserve coefficients beyond signed long`() {
        val left = requireNotNull(IntegerConflictRow.source(LinearPropagator(intArrayOf(), intArrayOf(0, 1),
            longArrayOf(Long.MAX_VALUE, 1), intArrayOf(0, 1), LinearOp.LE, Long.MAX_VALUE), useLo = true))
        val right = requireNotNull(IntegerConflictRow.source(LinearPropagator(intArrayOf(), intArrayOf(0, 2),
            longArrayOf(-2, 1), intArrayOf(0, 2), LinearOp.LE, 0), useLo = true))

        val combined = requireNotNull(left.resolve(right, 0))
        assertEquals(bigIntOf(Long.MAX_VALUE), combined.coefficient(2))
        assertEquals(bigIntOf(Long.MAX_VALUE) * bigIntOf(2), combined.bound)
    }

    @Test
    fun `an activity beyond signed long should remain exact`() {
        val row = requireNotNull(IntegerConflictRow.source(LinearPropagator(intArrayOf(), intArrayOf(0, 1),
            longArrayOf(Long.MAX_VALUE, 1), intArrayOf(0, 1), LinearOp.LE, Long.MAX_VALUE), useLo = true))
        val state = PropagationState(Problem(0, 2, Array(2) { IntDomain(1, 1) }, emptyArray()), Assumptions.None)

        assertEquals(bigIntOf(Long.MAX_VALUE) + bigIntOf(1), row.minimum(state))
    }

    @Test
    fun `normalization should round a negative integer upper bound downward`() {
        val source = LinearPropagator(intArrayOf(), intArrayOf(0), longArrayOf(2), intArrayOf(0), LinearOp.LE, -3)

        val row = requireNotNull(IntegerConflictRow.source(source, useLo = true))

        assertEquals(bigIntOf(1), row.coefficient(0))
        assertEquals(bigIntOf(-2), row.bound)
    }

    @Test
    fun `minimum signed long coefficients should normalize in either direction`() {
        val source = LinearPropagator(intArrayOf(), intArrayOf(0), longArrayOf(Long.MIN_VALUE),
            intArrayOf(0), LinearOp.LE, 0)

        assertEquals(bigIntOf(-1), requireNotNull(IntegerConflictRow.source(source, useLo = true)).coefficient(0))
        assertEquals(bigIntOf(1), requireNotNull(IntegerConflictRow.source(source, useLo = false)).coefficient(0))
    }

    @Test
    fun `a bound outside signed long should remain unavailable as an endpoint`() {
        val source = LinearPropagator(intArrayOf(), intArrayOf(0), longArrayOf(-1),
            intArrayOf(0), LinearOp.LE, Long.MIN_VALUE)
        val row = requireNotNull(IntegerConflictRow.source(source, useLo = true))
        val state = PropagationState(Problem(0, 1, arrayOf(IntDomain(0, 1)), emptyArray()), Assumptions.None)

        assertNull(row.boundFor(state, 0, 0))
    }

    @Test
    fun `a row combination should preserve every feasible source assignment`() {
        val left = requireNotNull(IntegerConflictRow.source(LinearPropagator(intArrayOf(), intArrayOf(0, 1, 2),
            longArrayOf(2, 1, -1), intArrayOf(0, 1, 2), LinearOp.LE, 0), useLo = true))
        val right = requireNotNull(IntegerConflictRow.source(LinearPropagator(intArrayOf(), intArrayOf(0, 1),
            longArrayOf(2, -1), intArrayOf(0, 1), LinearOp.LE, 0), useLo = true))

        val row = requireNotNull(left.resolve(right, 1))

        for (x in 0L..6L) {
            for (y in 0L..12L) {
                for (z in 0L..24L) {
                    if (2 * x + y <= z && 2 * x - y <= 0) {
                        val assignment = longArrayOf(x, y, z)
                        var activity = bigIntOf(0)
                        for (i in row.variables.indices) {
                            activity += row.coefficients[i] * bigIntOf(assignment[row.variables[i]])
                        }
                        assertTrue(activity <= row.bound)
                    }
                }
            }
        }
    }
}
