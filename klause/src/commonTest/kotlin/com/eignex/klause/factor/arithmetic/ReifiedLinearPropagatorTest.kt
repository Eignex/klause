package com.eignex.klause.factor.arithmetic

import com.eignex.klause.factor.PropagationReasonOracle
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.mark
import com.eignex.klause.propagation.reasonOf
import com.eignex.klause.propagation.undoTo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReifiedLinearPropagatorTest {
    @Test
    fun `single sided indicator reasons imply the pin for signed rows`() {
        for (op in listOf(LinearOp.LE, LinearOp.GE, LinearOp.EQ, LinearOp.NE)) {
            for (coefficient in listOf(-1L, 1L)) {
                val problem = Problem(1, 2, arrayOf(IntDomain(0L, 3L), IntDomain(0L, 3L)),
                    arrayOf(ReifiedLinear(0, longArrayOf(coefficient, coefficient), intArrayOf(0, 1),
                        op, 3L * coefficient)))

                PropagationReasonOracle.assertReasonsImply(problem, "$op signed indicator $coefficient") { state ->
                    state.tightenIntMin(0, 2L) && state.tightenIntMin(1, 2L)
                }
            }
        }
    }

    @Test
    fun `settled inequality indicator reasons cite the bounds at the pin through rollback`() {
        for (value in listOf(false, true)) {
            val problem = Problem(1, 1, arrayOf(IntDomain(0L, 10L)),
                arrayOf(ReifiedLinear(0, longArrayOf(1L), intArrayOf(0), LinearOp.LE, 5L)))
            val state = PropagationState(problem, Assumptions.None)
            state.undoLogging = true
            assertNull(state.runToFixpoint(allFactors = true))
            val root = state.mark()
            state.currentLevel = 1
            if (value) assertTrue(state.tightenIntMax(0, 5L)) else assertTrue(state.tightenIntMin(0, 6L))
            assertNull(state.runToFixpoint(allFactors = false))
            val reason = state.boolAntecedents[0]
            state.currentLevel = 2
            if (value) assertTrue(state.tightenIntMax(0, 3L)) else assertTrue(state.tightenIntMin(0, 8L))

            val explained = state.reasonOf(reason)

            val atom = if (value) state.atomVarLe(0, 5L) else state.atomVarGe(0, 6L)
            assertEquals(value, state.boolValues[0])
            assertEquals(listOf(Lit.make(atom, false)), explained?.toList())
            state.undoTo(root)
            assertNull(state.boolValues[0])
            state.currentLevel = 1
            if (value) assertTrue(state.tightenIntMin(0, 6L)) else assertTrue(state.tightenIntMax(0, 5L))
            assertNull(state.runToFixpoint(allFactors = false))
            assertEquals(!value, state.boolValues[0])
            val siblingAtom = if (value) state.atomVarGe(0, 6L) else state.atomVarLe(0, 5L)
            assertEquals(listOf(Lit.make(siblingAtom, false)), state.reasonOf(state.boolAntecedents[0])?.toList())
        }
    }

    @Test
    fun `incremental reification follows interior target membership through rollback`() {
        for (op in listOf(LinearOp.EQ, LinearOp.NE)) {
            for (target in listOf(2L, Int.MAX_VALUE.toLong() + 1L)) {
                val problem = Problem(1, 1, arrayOf(IntDomain(target - 2L, target + 2L)),
                    arrayOf(ReifiedLinear(0, longArrayOf(1L), intArrayOf(0), op, target)))
                val state = PropagationState(problem, Assumptions.None)
                state.undoLogging = true
                assertNull(state.runToFixpoint(allFactors = true))
                val root = state.mark()
                state.currentLevel = 1
                assertTrue(state.excludeIntValue(0, target - 1L))
                assertNull(state.runToFixpoint(allFactors = false))
                assertNull(state.boolValues[0])

                assertTrue(state.excludeIntValue(0, target))
                assertNull(state.runToFixpoint(allFactors = false))

                assertEquals(op == LinearOp.NE, state.boolValues[0])
                assertEquals(target - 2L, state.intDomains[0].min)
                assertEquals(target + 2L, state.intDomains[0].max)
                assertEquals(listOf(Lit.make(state.atomVarEq(0, target), true)), state.boolAntecedents[0]?.toList())
                state.undoTo(root)
                assertNull(state.boolValues[0])
                assertTrue(target in state.intDomains[0])
                state.currentLevel = 1
                assertTrue(state.tightenIntMin(0, target))
                assertTrue(state.tightenIntMax(0, target))
                assertNull(state.runToFixpoint(allFactors = false))
                assertEquals(op == LinearOp.EQ, state.boolValues[0])
            }
        }
    }

    @Test
    fun `nonintegral single term targets decide both reification polarities`() {
        for (op in listOf(LinearOp.EQ, LinearOp.NE)) {
            for (coefficient in listOf(2L, -2L)) {
                val bound = if (coefficient > 0L) 1L else -1L
                val session = PropagationSession(Problem(1, 1, arrayOf(IntDomain(0L, 2L)),
                    arrayOf(ReifiedLinear(0, longArrayOf(coefficient), intArrayOf(0), op, bound))))

                assertEquals(op == LinearOp.NE, session.boolValue(0))
            }
        }
    }

    @Test
    fun `a carved equality target outside the 32 bit range keeps its premise`() {
        for (target in listOf(Int.MIN_VALUE.toLong() - 1L, Int.MAX_VALUE.toLong() + 1L)) {
            val problem = Problem(
                1,
                1,
                arrayOf(IntDomain(target - 1L, target + 1L)),
                arrayOf(ReifiedLinear(0, longArrayOf(1L), intArrayOf(0), LinearOp.EQ, target)),
            )

            PropagationReasonOracle.assertReasonsImply(problem, "64 bit equality hole $target") { state ->
                state.excludeIntValue(0, target)
            }
        }
    }

}
