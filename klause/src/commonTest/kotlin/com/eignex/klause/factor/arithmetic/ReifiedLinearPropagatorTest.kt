package com.eignex.klause.factor.arithmetic

import com.eignex.klause.factor.PropagationReasonOracle
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.mark
import com.eignex.klause.propagation.undoTo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReifiedLinearPropagatorTest {
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
    fun `a carved disequality target keeps its premise`() {
        for (target in listOf(2L, Int.MAX_VALUE.toLong() + 1L)) {
            val problem = Problem(1, 1, arrayOf(IntDomain(target - 1L, target + 1L)),
                arrayOf(ReifiedLinear(0, longArrayOf(1L), intArrayOf(0), LinearOp.NE, target)))

            PropagationReasonOracle.assertReasonsImply(problem, "disequality hole $target") { state ->
                state.excludeIntValue(0, target)
            }
        }
    }

    @Test
    fun `a false disequality indicator conflicts with a carved target using its premise`() {
        for (target in listOf(2L, Int.MAX_VALUE.toLong() + 1L)) {
            val problem = Problem(1, 1, arrayOf(IntDomain(target - 1L, target + 1L)),
                arrayOf(ReifiedLinear(0, longArrayOf(1L), intArrayOf(0), LinearOp.NE, target)))

            PropagationReasonOracle.assertReasonsImply(problem, "negated disequality hole $target") { state ->
                state.pinBool(0, false) && state.excludeIntValue(0, target)
            }
        }
    }

    @Test
    fun `a root target hole decides the indicator without search premises`() {
        for (op in listOf(LinearOp.EQ, LinearOp.NE)) {
            val problem = Problem(1, 1, arrayOf(IntDomain(0L, 4L).excludeValue(2L)),
                arrayOf(ReifiedLinear(0, longArrayOf(1L), intArrayOf(0), op, 2L)))
            val state = PropagationState(problem, Assumptions.None)

            assertNull(state.runToFixpoint(allFactors = true))

            assertEquals(op == LinearOp.NE, state.boolValues[0])
            assertNull(state.boolAntecedents[0])
        }
    }

    @Test
    fun `reified equalities retain valid targets outside the 32 bit range`() {
        val targets = listOf(
            Int.MIN_VALUE.toLong() - 1L,
            Int.MAX_VALUE.toLong() + 1L,
            Long.MIN_VALUE + 1L,
            Long.MAX_VALUE - 1L,
        )
        for (target in targets) {
            val session = PropagationSession(
                Problem(
                    1,
                    1,
                    arrayOf(IntDomain(target - 1L, target + 1L)),
                    arrayOf(ReifiedLinear(0, longArrayOf(1L), intArrayOf(0), LinearOp.EQ, target)),
                ),
            )
            assertNull(session.boolValue(0))

            assertIs<PropagationResult.Implied>(session.pinBool(0, true))

            assertEquals(target, session.intDomain(0).min)
            assertEquals(target, session.intDomain(0).max)
        }
    }

    @Test
    fun `a negated reified equality removes its target outside the 32 bit range`() {
        for (target in listOf(Int.MIN_VALUE.toLong() - 1L, Int.MAX_VALUE.toLong() + 1L)) {
            val session = PropagationSession(
                Problem(
                    1,
                    1,
                    arrayOf(IntDomain(target - 1L, target + 1L)),
                    arrayOf(ReifiedLinear(0, longArrayOf(1L), intArrayOf(0), LinearOp.EQ, target)),
                ),
            )

            assertIs<PropagationResult.Implied>(session.pinBool(0, false))

            assertTrue(target !in session.intDomain(0))
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

    @Test
    fun `an overflowing equality quotient refutes its indicator`() {
        val session = PropagationSession(
            Problem(
                1,
                1,
                arrayOf(IntDomain(Long.MIN_VALUE, Long.MIN_VALUE + 1L)),
                arrayOf(ReifiedLinear(0, longArrayOf(-1L), intArrayOf(0), LinearOp.EQ, Long.MIN_VALUE)),
            ),
        )

        assertEquals(false, session.boolValue(0))
    }
}
