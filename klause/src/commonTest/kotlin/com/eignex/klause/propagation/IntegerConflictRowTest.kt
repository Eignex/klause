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
    fun `cover division should preserve feasible binary source assignments`() {
        val variants = listOf(longArrayOf(2, 3, 5), longArrayOf(-2, 3, 5),
            longArrayOf(2, -3, 5), longArrayOf(-2, -3, -5))
        val state = PropagationState(Problem(0, 3, Array(3) { IntDomain(0, 1) }, emptyArray()), Assumptions.None)
        for (coefficients in variants) for (bound in listOf(-5L, -1L, 0L, 1L, 4L, 7L)) {
            val source = requireNotNull(IntegerConflictRow.source(LinearPropagator(intArrayOf(), intArrayOf(0, 1, 2),
                coefficients, intArrayOf(0, 1, 2), LinearOp.LE, bound), useLo = true))

            val divided = requireNotNull(source.dividedFor(state, 0))

            for (x in 0L..1L) for (y in 0L..1L) for (z in 0L..1L) {
                val assignment = longArrayOf(x, y, z)
                val sourceActivity = coefficients.indices.sumOf { coefficients[it] * assignment[it] }
                if (sourceActivity <= bound) {
                    val activity = divided.variables.indices.sumOf {
                        divided.coefficients[it] * assignment[divided.variables[it]]
                    }
                    assertTrue(activity <= divided.bound)
                }
            }
        }
    }

    @Test
    fun `cover division should retain the signed source guard`() {
        val state = PropagationState(Problem(1, 2, Array(2) { IntDomain(0, 1) }, emptyArray()), Assumptions.None)
        for (guard in listOf(Lit.NONE, Lit.make(0, true), Lit.make(0, false))) {
            val source = requireNotNull(IntegerConflictRow.source(LinearPropagator(intArrayOf(), intArrayOf(0, 1),
                longArrayOf(-2, 3), intArrayOf(0, 1), LinearOp.LE, 1), useLo = true, guard = guard))

            val divided = requireNotNull(source.dividedFor(state, 0))

            assertEquals(-1L, divided.coefficient(0))
            assertEquals(2L, divided.coefficient(1))
            assertEquals(1L, divided.bound)
            assertEquals(guard, divided.guard)
        }
    }

    @Test
    fun `cover division should require binary model bounds before trail narrowing`() {
        val source = requireNotNull(IntegerConflictRow.source(LinearPropagator(intArrayOf(), intArrayOf(0, 1),
            longArrayOf(2, 3), intArrayOf(0, 1), LinearOp.LE, 3), useLo = true))
        for (domain in listOf(IntDomain(-1, 1), IntDomain(0, 2))) {
            val state = PropagationState(Problem(0, 2, arrayOf(domain, IntDomain(0, 1)), emptyArray()),
                Assumptions.None)
            assertTrue(state.tightenIntMin(0, 0))
            assertTrue(state.tightenIntMax(0, 1))

            assertNull(source.dividedFor(state, 0))
        }
    }

    @Test
    fun `an overflowing cover should retain the exact source`() {
        val source = requireNotNull(IntegerConflictRow.source(LinearPropagator(intArrayOf(), intArrayOf(0, 1),
            longArrayOf(2, Long.MAX_VALUE), intArrayOf(0, 1), LinearOp.LE, Long.MIN_VALUE), useLo = true))
        val state = PropagationState(Problem(0, 2, Array(2) { IntDomain(0, 1) }, emptyArray()), Assumptions.None)

        assertNull(source.dividedFor(state, 0))
        assertEquals(Long.MIN_VALUE, source.bound)
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
