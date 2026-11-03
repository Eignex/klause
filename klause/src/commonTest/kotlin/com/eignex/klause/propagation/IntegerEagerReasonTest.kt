package com.eignex.klause.propagation

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.table.Table
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class IntegerEagerReasonTest {
    @Test
    fun `a live learned clause should expose its complete eager integer reason`() {
        val native = PropagationSession(Problem(0, 2, Array(2) { IntDomain(0, 1) }, arrayOf()),
            pbLearning = false).apply { enableIntegerRowLearning() }
        val state = native.explanationState
        val premise = Lit.make(state.atomVarGe(1, 1), false)
        val conclusion = Lit.make(state.atomVarGe(0, 1), true)
        assertIs<PropagationResult.Implied>(native.addLearnedClause(Clause(intArrayOf(premise, conclusion)), 2))
        assertIs<PropagationResult.Implied>(native.pinIntAtLeast(1, 1))

        val classification = state.classifyIntegerEagerReason(0, true, IntegerSourceWork(state))

        assertEquals(IntegerEagerReason.BinaryClause, classification)
    }

    @Test
    fun `canonical binary atoms should encode the complete eager implication`() {
        for (lower in listOf(false, true)) for (form in 0..3) {
            val state = PropagationState(Problem(0, 2, Array(2) { IntDomain(0, 1) }, arrayOf(
                Table(intArrayOf(1, 0), if (lower) longArrayOf(0, 0, 0, 1, 1, 1)
                    else longArrayOf(0, 0, 0, 1, 1, 0)),
            )), Assumptions.None)
            assertTrue(state.tightenIntMin(1, 1))
            val atom = when (form) {
                0 -> state.atomVarGe(1, 1)
                1 -> state.atomVarLe(1, 0)
                2 -> state.atomVarEq(1, 1)
                else -> state.atomVarEq(1, 0)
            }
            val reason = intArrayOf(Lit.make(atom, form == 1 || form == 3))
            state.currentFactor = 0
            assertTrue(if (lower) state.tightenIntMin(0, 1, reason) else state.tightenIntMax(0, 0, reason))

            val classification = state.classifyIntegerEagerReason(0, lower, IntegerSourceWork(state))

            assertEquals(IntegerEagerReason.BinaryClause, classification)
        }
    }

    @Test
    fun `a Boolean sibling should withhold binary integer encoding`() {
        val state = PropagationState(Problem(1, 2, Array(2) { IntDomain(0, 1) }, arrayOf(
            Table(intArrayOf(1, 0), longArrayOf(0, 0, 0, 1, 1, 1)),
        )), Assumptions.None)
        assertTrue(state.pinBool(0, true))
        assertTrue(state.tightenIntMin(1, 1))
        val reason = intArrayOf(Lit.make(state.atomVarGe(1, 1), false), Lit.make(0, false))
        state.currentFactor = 0
        assertTrue(state.tightenIntMin(0, 1, reason))

        val classification = state.classifyIntegerEagerReason(0, true, IntegerSourceWork(state))

        assertEquals(IntegerEagerReason.BooleanPremise, classification)
    }

    @Test
    fun `a nonbinary sibling should withhold binary integer encoding`() {
        val state = PropagationState(Problem(0, 3,
            arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 2)), arrayOf(
                Table(intArrayOf(1, 0), longArrayOf(0, 0, 0, 1, 1, 1)),
            )), Assumptions.None)
        assertTrue(state.tightenIntMin(1, 1))
        assertTrue(state.tightenIntMin(2, 1))
        val reason = intArrayOf(Lit.make(state.atomVarGe(1, 1), false), Lit.make(state.atomVarGe(2, 1), false))
        state.currentFactor = 0
        assertTrue(state.tightenIntMin(0, 1, reason))

        val classification = state.classifyIntegerEagerReason(0, true, IntegerSourceWork(state))

        assertEquals(IntegerEagerReason.NonbinaryPremise, classification)
    }

    @Test
    fun `an inactive sibling should withhold complete reason classification`() {
        val state = PropagationState(Problem(0, 3, Array(3) { IntDomain(0, 1) }, arrayOf(
            Table(intArrayOf(1, 0), longArrayOf(0, 0, 0, 1, 1, 1)),
        )), Assumptions.None)
        assertTrue(state.tightenIntMin(1, 1))
        val reason = intArrayOf(Lit.make(state.atomVarGe(1, 1), false), Lit.make(state.atomVarGe(2, 1), false))
        state.currentFactor = 0
        assertTrue(state.tightenIntMin(0, 1, reason))

        val classification = state.classifyIntegerEagerReason(0, true, IntegerSourceWork(state))

        assertEquals(IntegerEagerReason.InactivePremise, classification)
    }

    @Test
    fun `repeated reason coordinates should remain separate from binary clauses`() {
        for (repeated in listOf(0, 1)) {
            val state = PropagationState(Problem(0, 2, Array(2) { IntDomain(0, 1) }, arrayOf(
                Table(intArrayOf(1, 0), longArrayOf(0, 0, 0, 1, 1, 1)),
            )), Assumptions.None)
            assertTrue(state.tightenIntMin(1, 1))
            val reason = intArrayOf(Lit.make(state.atomVarGe(1, 1), false),
                Lit.make(state.atomVarGe(repeated, 1), false))
            state.currentFactor = 0
            assertTrue(state.tightenIntMin(0, 1, reason))

            val classification = state.classifyIntegerEagerReason(0, true, IntegerSourceWork(state))

            assertEquals(IntegerEagerReason.RepeatedCoordinate, classification)
        }
    }

    @Test
    fun `a reason wider than the complete literal cap should withhold encoding`() {
        val state = PropagationState(Problem(0, 17, Array(17) { IntDomain(0, 1) }, arrayOf(
            Table(intArrayOf(1, 0), longArrayOf(0, 0, 0, 1, 1, 1)),
        )), Assumptions.None)
        for (variable in 1..16) assertTrue(state.tightenIntMin(variable, 1))
        val reason = IntArray(16) { Lit.make(state.atomVarGe(it + 1, 1), false) }
        state.currentFactor = 0
        assertTrue(state.tightenIntMin(0, 1, reason))

        val classification = state.classifyIntegerEagerReason(0, true, IntegerSourceWork(state))

        assertEquals(IntegerEagerReason.TooWide, classification)
    }

    @Test
    fun `decisions and root reasons should retain distinct classifications`() {
        for (reason in listOf(null, intArrayOf())) {
            val state = PropagationState(Problem(0, 1, arrayOf(IntDomain(0, 1)), arrayOf(
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 1),
            )), Assumptions.None)
            state.currentFactor = 0
            assertTrue(state.tightenIntMin(0, 1, reason))

            val classification = state.classifyIntegerEagerReason(0, true, IntegerSourceWork(state))

            assertEquals(if (reason == null) IntegerEagerReason.Decision else IntegerEagerReason.Root, classification)
        }
    }

    @Test
    fun `lazy reasons should retain their historical marker without expansion`() {
        val state = PropagationState(Problem(0, 1, arrayOf(IntDomain(0, 1)), arrayOf(
            Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 1),
        )), Assumptions.None)
        state.currentFactor = 0
        val reason = state.lazyReasonSlots(6)
        assertTrue(state.tightenIntMin(0, 1, reason))

        val classification = state.classifyIntegerEagerReason(0, true, IntegerSourceWork(state))

        assertEquals(IntegerEagerReason.Lazy, classification)
    }

    @Test
    fun `source exhaustion should stop eager reason inspection`() {
        val state = PropagationState(Problem(0, 1, arrayOf(IntDomain(0, 1)), arrayOf()), Assumptions.None)

        assertFailsWith<IntegerSourceWorkLimit> {
            state.classifyIntegerEagerReason(0, true, IntegerSourceWork(state, remaining = 0))
        }
    }

    @Test
    fun `a narrowed nonbinary conclusion should withhold binary classification`() {
        val state = PropagationState(Problem(0, 2, arrayOf(IntDomain(0, 2), IntDomain(0, 1)), arrayOf(
            Table(intArrayOf(1, 0), longArrayOf(0, 0, 0, 1, 1, 1)),
        )), Assumptions.None)
        assertTrue(state.tightenIntMin(1, 1))
        val reason = intArrayOf(Lit.make(state.atomVarGe(1, 1), false))
        state.currentFactor = 0
        assertTrue(state.tightenIntMin(0, 1, reason))

        val classification = state.classifyIntegerEagerReason(0, true, IntegerSourceWork(state))

        assertEquals(IntegerEagerReason.NonbinaryConclusion, classification)
    }
}
