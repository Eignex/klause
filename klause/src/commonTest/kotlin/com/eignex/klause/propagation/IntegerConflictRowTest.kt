package com.eignex.klause.propagation

import com.eignex.klause.factor.arithmetic.LinearPropagator
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.util.CheckedLongOverflowException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IntegerConflictRowTest {
    @Test
    fun `wide original rows should cancel to a compact inequality in either source order`() {
        for (reverse in listOf(false, true)) {
            val state = PropagationState(Problem(0, 28, Array(28) { IntDomain(0, 1) }, emptyArray()), Assumptions.None)
            val work = IntegerSourceWork(state)
            val leftVars = IntArray(20) { it }.also { if (reverse) it.reverse() }
            val rightVars = (IntArray(12) { it } + IntArray(8) { it + 20 }).also { if (reverse) it.reverse() }
            val left = requireNotNull(WideIntegerSourceInequality.create(IntegerSourceRow(LinearPropagator(
                intArrayOf(), leftVars, LongArray(20) { 1 }, leftVars, LinearOp.LE, 4)), true, work))
            val right = requireNotNull(WideIntegerSourceInequality.create(IntegerSourceRow(LinearPropagator(
                intArrayOf(), rightVars, LongArray(20) { if (rightVars[it] < 12) -1 else 1 }, rightVars,
                LinearOp.LE, 4)), true, work))

            val row = requireNotNull(left.resolve(right, 0, work))

            assertEquals((12..27).toList(), row.variables.toList())
            assertTrue(row.coefficients.all { it == 1L })
            assertEquals(8L, row.bound)
        }
    }

    @Test
    fun `wide source cancellation should withhold a result with seventeen remaining terms`() {
        val state = PropagationState(Problem(0, 28, Array(28) { IntDomain(0, 1) }, emptyArray()), Assumptions.None)
        val work = IntegerSourceWork(state)
        val leftVars = IntArray(19) { it }
        val rightVars = IntArray(11) { it } + IntArray(9) { it + 19 }
        val left = requireNotNull(WideIntegerSourceInequality.create(IntegerSourceRow(LinearPropagator(
            intArrayOf(), leftVars, LongArray(19) { 1 }, leftVars, LinearOp.LE, 10)), true, work))
        val right = requireNotNull(WideIntegerSourceInequality.create(IntegerSourceRow(LinearPropagator(
            intArrayOf(), rightVars, LongArray(20) { if (rightVars[it] < 11) -1 else 1 }, rightVars,
            LinearOp.LE, 10)), true, work))

        assertNull(left.resolve(right, 0, work))
    }

    @Test
    fun `wide source cancellation should not combine opposite guards`() {
        val state = PropagationState(Problem(1, 28, Array(28) { IntDomain(0, 1) }, emptyArray()), Assumptions.None)
        val work = IntegerSourceWork(state)
        val leftVars = IntArray(20) { it }
        val rightVars = IntArray(12) { it } + IntArray(8) { it + 20 }
        val left = requireNotNull(WideIntegerSourceInequality.create(IntegerSourceRow(LinearPropagator(
            intArrayOf(), leftVars, LongArray(20) { 1 }, leftVars, LinearOp.LE, 10), Lit.make(0, true)), true, work))
        val right = requireNotNull(WideIntegerSourceInequality.create(IntegerSourceRow(LinearPropagator(
            intArrayOf(), rightVars, LongArray(20) { if (rightVars[it] < 12) -1 else 1 }, rightVars,
            LinearOp.LE, 10), Lit.make(0, false)), true, work))

        assertNull(left.resolve(right, 0, work))
    }

    @Test
    fun `wide source cancellation should stop when its source allowance is exhausted`() {
        val state = PropagationState(Problem(0, 28, Array(28) { IntDomain(0, 1) }, emptyArray()), Assumptions.None)
        val preparation = IntegerSourceWork(state)
        val leftVars = IntArray(20) { it }
        val rightVars = IntArray(12) { it } + IntArray(8) { it + 20 }
        val left = requireNotNull(WideIntegerSourceInequality.create(IntegerSourceRow(LinearPropagator(
            intArrayOf(), leftVars, LongArray(20) { 1 }, leftVars, LinearOp.LE, 10)), true, preparation))
        val right = requireNotNull(WideIntegerSourceInequality.create(IntegerSourceRow(LinearPropagator(
            intArrayOf(), rightVars, LongArray(20) { if (rightVars[it] < 12) -1 else 1 }, rightVars,
            LinearOp.LE, 10)), true, preparation))
        val exhausted = IntegerSourceWork(state, remaining = 0)

        assertFailsWith<IntegerSourceWorkLimit> { left.resolve(right, 0, exhausted) }
    }

    @Test
    fun `wide source cancellation should check combined bound overflow`() {
        val state = PropagationState(Problem(0, 28, Array(28) { IntDomain(0, 1) }, emptyArray()), Assumptions.None)
        val work = IntegerSourceWork(state)
        val leftVars = IntArray(20) { it }
        val rightVars = IntArray(12) { it } + IntArray(8) { it + 20 }
        val left = requireNotNull(WideIntegerSourceInequality.create(IntegerSourceRow(LinearPropagator(
            intArrayOf(), leftVars, LongArray(20) { 1 }, leftVars, LinearOp.LE, Long.MAX_VALUE)), true, work))
        val right = requireNotNull(WideIntegerSourceInequality.create(IntegerSourceRow(LinearPropagator(
            intArrayOf(), rightVars, LongArray(20) { if (rightVars[it] < 12) -1 else 1 }, rightVars,
            LinearOp.LE, Long.MAX_VALUE)), true, work))

        assertFailsWith<CheckedLongOverflowException> { left.resolve(right, 0, work) }
    }

    @Test
    fun `wide original rows with duplicate coordinates should withhold a source view`() {
        val state = PropagationState(Problem(0, 20, Array(20) { IntDomain(0, 1) }, emptyArray()), Assumptions.None)
        val variables = IntArray(20) { it }.apply { this[19] = 0 }
        val source = IntegerSourceRow(LinearPropagator(intArrayOf(), variables, LongArray(20) { 1 }, variables,
            LinearOp.LE, 10))

        assertNull(WideIntegerSourceInequality.create(source, true, IntegerSourceWork(state)))
    }

    @Test
    fun `subsumption should require a stronger bound and an implied guard`() {
        val guards = listOf(Lit.NONE, Lit.make(0, true), Lit.make(0, false), Lit.make(1, true))
        for (guard in guards) for (otherGuard in guards) for (bound in listOf(3L, 5L, 7L)) {
            val retained = requireNotNull(IntegerConflictRow.source(LinearPropagator(intArrayOf(), intArrayOf(0),
                longArrayOf(2), intArrayOf(0), LinearOp.LE, 10), useLo = true, guard = guard))
            val candidate = requireNotNull(IntegerConflictRow.source(LinearPropagator(intArrayOf(), intArrayOf(0),
                longArrayOf(1), intArrayOf(0), LinearOp.LE, bound), useLo = true, guard = otherGuard))

            assertEquals(bound >= 5 && (guard == Lit.NONE || guard == otherGuard), retained.subsumes(candidate),
                "guard=$guard otherGuard=$otherGuard bound=$bound")
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
    fun `an overflowing nonnegative combination should decline`() {
        val left = requireNotNull(IntegerConflictRow.source(LinearPropagator(intArrayOf(), intArrayOf(0, 1),
            longArrayOf(Long.MAX_VALUE, 1), intArrayOf(0, 1), LinearOp.LE, Long.MAX_VALUE), useLo = true))
        val right = requireNotNull(IntegerConflictRow.source(LinearPropagator(intArrayOf(), intArrayOf(0, 2),
            longArrayOf(-2, 1), intArrayOf(0, 2), LinearOp.LE, 0), useLo = true))

        assertFailsWith<CheckedLongOverflowException> { left.resolve(right, 0) }
    }

    @Test
    fun `an overflowing activity should decline`() {
        val row = requireNotNull(IntegerConflictRow.source(LinearPropagator(intArrayOf(), intArrayOf(0, 1),
            longArrayOf(Long.MAX_VALUE, 1), intArrayOf(0, 1), LinearOp.LE, Long.MAX_VALUE), useLo = true))
        val state = PropagationState(Problem(0, 2, Array(2) { IntDomain(1, 1) }, emptyArray()), Assumptions.None)

        assertFailsWith<CheckedLongOverflowException> { row.minimum(state) }
    }

    @Test
    fun `normalization should round a negative integer upper bound downward`() {
        val source = LinearPropagator(intArrayOf(), intArrayOf(0), longArrayOf(2), intArrayOf(0), LinearOp.LE, -3)

        val row = requireNotNull(IntegerConflictRow.source(source, useLo = true))

        assertEquals(1L, row.coefficient(0))
        assertEquals(-2L, row.bound)
    }

    @Test
    fun `minimum coefficient magnitude should decline before normalization`() {
        val source = LinearPropagator(intArrayOf(), intArrayOf(0), longArrayOf(Long.MIN_VALUE),
            intArrayOf(0), LinearOp.LE, 0)

        assertNull(IntegerConflictRow.source(source, useLo = true))
        assertFailsWith<CheckedLongOverflowException> { IntegerConflictRow.source(source, useLo = false) }
    }

    @Test
    fun `an unrepresentable negative unit quotient should decline`() {
        val source = LinearPropagator(intArrayOf(), intArrayOf(0), longArrayOf(-1),
            intArrayOf(0), LinearOp.LE, Long.MIN_VALUE)
        val row = requireNotNull(IntegerConflictRow.source(source, useLo = true))
        val state = PropagationState(Problem(0, 1, arrayOf(IntDomain(0, 1)), emptyArray()), Assumptions.None)

        assertFailsWith<CheckedLongOverflowException> { row.boundFor(state, 0, 0) }
    }

    @Test
    fun `a row combination should preserve every feasible source assignment`() {
        val left = requireNotNull(IntegerConflictRow.source(LinearPropagator(intArrayOf(), intArrayOf(0, 1, 2),
            longArrayOf(2, 1, -1), intArrayOf(0, 1, 2), LinearOp.LE, 0), useLo = true))
        val right = requireNotNull(IntegerConflictRow.source(LinearPropagator(intArrayOf(), intArrayOf(0, 1),
            longArrayOf(2, -1), intArrayOf(0, 1), LinearOp.LE, 0), useLo = true))

        val row = requireNotNull(left.resolve(right, 1))

        for (x in 0L..6L) for (y in 0L..12L) for (z in 0L..24L) {
            if (2 * x + y <= z && 2 * x - y <= 0) {
                val assignment = longArrayOf(x, y, z)
                var activity = 0L
                for (i in row.variables.indices) activity += row.coefficients[i] * assignment[row.variables[i]]
                assertTrue(activity <= row.bound)
            }
        }
    }
}
