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
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IntegerConflictRowTest {
    @Test
    fun `bound implication origins should survive row view copies`() {
        val source = requireNotNull(IntegerConflictRow.normalized(mapOf(0 to 1L, 1 to 1L), 1L, Lit.NONE))
            .derivedFromRetainedSources(1, boundImplication = true)

        val copied = source.derivedThroughTransient(2).derivedFromDeclaredBounds(1, DECLARED_BOUND_EARLY)
            .derivedFromRetainedSources(2)

        assertTrue(copied.hasBoundImplication)
    }

    @Test
    fun `a historical conflict should not qualify as an assertion`() {
        val row = requireNotNull(IntegerConflictRow.normalized(mapOf(0 to 1L), 0L, Lit.NONE))
        val state = PropagationState(Problem(0, 1, arrayOf(IntDomain(1, 2)), emptyArray()), Assumptions.None)

        assertEquals(IntegerAssertion.CONFLICTING, row.assertionAt(state, 0))
    }

    @Test
    fun `an entailed historical row should report no tightening`() {
        val row = requireNotNull(IntegerConflictRow.normalized(mapOf(0 to 1L), 2L, Lit.NONE))
        val state = PropagationState(Problem(0, 1, arrayOf(IntDomain(0, 2)), emptyArray()), Assumptions.None)

        assertEquals(IntegerAssertion.NO_TIGHTENING, row.assertionAt(state, 0))
    }

    @Test
    fun `a compact declared box consequence should stay valid outside the current pins`() {
        val state = PropagationState(Problem(0, 17, Array(17) {
            if (it < 3) IntDomain(0, 2) else IntDomain(0, 1)
        }, emptyArray()), Assumptions.None.withInt(0, 1).withInt(1, 1).withInt(2, 1))
        val work = IntegerSourceWork(state)
        val row = requireNotNull(TransientIntegerRow.normalized((0..16).associateWith {
            when (it) {
                0 -> 2L
                1 -> 1L
                2 -> -1L
                else -> if (it % 2 == 0) 1L else -1L
            }
        }, -6L, work))

        val consequence = requireNotNull(row.declaredBoundConsequence(state))

        assertTrue(consequence.minimum(state) > consequence.bound)
        assertEquals(1, consequence.declaredBoundTerms)
        assertEquals(16, consequence.variables.size)
        assertEquals(DECLARED_BOUND_PRESERVED_TERMS, consequence.declaredBoundModes)
        for (x in 0L..2L) for (y in 0L..2L) for (z in 0L..2L) for (pattern in 0..3) {
            val values = LongArray(17) { variable ->
                when {
                    variable == 0 -> x
                    variable == 1 -> y
                    variable == 2 -> z
                    pattern == 0 -> 0L
                    pattern == 1 -> 1L
                    pattern == 2 -> if (variable % 2 == 0) 0L else 1L
                    else -> if (variable % 2 == 0) 1L else 0L
                }
            }
            val activity = row.variables.indices.sumOf { row.coefficients[it] * values[row.variables[it]] }
            if (activity <= row.bound) {
                assertTrue(consequence.variables.indices.sumOf {
                    consequence.coefficients[it] * values[consequence.variables[it]]
                } <= consequence.bound)
            }
        }
    }

    @Test
    fun `root pins should not supply declared bounds for temporary weakening`() {
        var assumptions = Assumptions.None
        for (variable in 0..16) assumptions = assumptions.withInt(variable, 1)
        val state = PropagationState(Problem(0, 17, Array(17) { IntDomain(0, 1) }, emptyArray()), assumptions)
        val row = requireNotNull(TransientIntegerRow.normalized(
            (0..16).associateWith { 1L }, 16L, IntegerSourceWork(state)))

        assertNull(row.declaredBoundConsequence(state))
    }

    @Test
    fun `unrepresentable declared bound arithmetic should withhold a compact consequence`() {
        val state = PropagationState(Problem(0, 17, Array(17) {
            if (it == 0) IntDomain(Long.MAX_VALUE - 1L, Long.MAX_VALUE) else IntDomain(0, 1)
        }, emptyArray()), Assumptions.None)
        val row = requireNotNull(TransientIntegerRow.normalized(
            (0..16).associateWith { if (it == 0) 2L else 1L }, 0L, IntegerSourceWork(state)))

        assertNull(row.declaredBoundConsequence(state))
    }

    @Test
    fun `successive wide cancellations should normalize to a compact integer consequence`() {
        for (reverse in listOf(false, true)) {
            val state = PropagationState(Problem(0, 18, Array(18) { IntDomain(0, 1) }, emptyArray()), Assumptions.None)
            val work = IntegerSourceWork(state)
            val variables = IntArray(18) { it }.also { if (reverse) it.reverse() }
            val source = requireNotNull(WideIntegerSourceInequality.create(IntegerSourceRow(LinearPropagator(
                intArrayOf(), variables, LongArray(18) { 2 }, variables, LinearOp.LE, 5)), true, work))
            val first = requireNotNull(IntegerConflictRow.source(LinearPropagator(intArrayOf(), intArrayOf(0),
                longArrayOf(-2), intArrayOf(0), LinearOp.LE, 0), true))
            val second = requireNotNull(IntegerConflictRow.source(LinearPropagator(intArrayOf(), intArrayOf(1),
                longArrayOf(-1), intArrayOf(1), LinearOp.LE, 0), true))

            val intermediate = requireNotNull(source.resolveTransient(first, 0, work))
            val result = assertIs<IntegerConflictRow>(intermediate.resolveTransient(second, 1, work))

            assertEquals(17, intermediate.variables.size)
            assertFalse(intermediate is IntegerConflictRow)
            assertEquals((2..17).toList(), result.variables.toList())
            assertTrue(result.coefficients.all { it == 1L })
            assertEquals(2L, result.bound)
        }
    }

    @Test
    fun `transient cancellation should reject more than thirty two final coordinates`() {
        val state = PropagationState(Problem(0, 63, Array(63) { IntDomain(0, 1) }, emptyArray()), Assumptions.None)
        val work = IntegerSourceWork(state)
        val leftVariables = IntArray(32) { it }
        val rightVariables = intArrayOf(0) + IntArray(31) { it + 32 }
        val left = requireNotNull(WideIntegerSourceInequality.create(IntegerSourceRow(LinearPropagator(
            intArrayOf(), leftVariables, LongArray(32) { 1 }, leftVariables, LinearOp.LE, 0)), true, work))
        val right = requireNotNull(WideIntegerSourceInequality.create(IntegerSourceRow(LinearPropagator(
            intArrayOf(), rightVariables, LongArray(32) { if (it == 0) -1 else 1 }, rightVariables,
            LinearOp.LE, 0)), true, work))

        assertNull(left.resolveTransient(right, 0, work))
    }

    @Test
    fun `transient cancellation should withhold a wide guarded consequence`() {
        val state = PropagationState(Problem(1, 18, Array(18) { IntDomain(0, 1) }, emptyArray()), Assumptions.None)
        val work = IntegerSourceWork(state)
        val variables = IntArray(18) { it }
        val guard = Lit.make(0, true)
        val source = requireNotNull(WideIntegerSourceInequality.create(IntegerSourceRow(LinearPropagator(
            intArrayOf(), variables, LongArray(18) { 1 }, variables, LinearOp.LE, 5), guard), true, work))
        val pivot = requireNotNull(IntegerConflictRow.source(LinearPropagator(intArrayOf(), intArrayOf(0),
            longArrayOf(-1), intArrayOf(0), LinearOp.LE, 0), true, guard))

        assertNull(source.resolveTransient(pivot, 0, work))
    }


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
        for (transient in listOf(false, true)) {
            val exhausted = IntegerSourceWork(state, remaining = 0)

            assertFailsWith<IntegerSourceWorkLimit> {
                if (transient) left.resolveTransient(right, 0, exhausted) else left.resolve(right, 0, exhausted)
            }
        }
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
        assertEquals(IntegerAssertion.RAW_BOUND_UNAVAILABLE, row.assertionAt(state, 0))
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
