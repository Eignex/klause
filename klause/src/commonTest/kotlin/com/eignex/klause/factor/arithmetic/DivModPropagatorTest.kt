package com.eignex.klause.factor.arithmetic

import com.eignex.klause.factor.PropagationReasonOracle
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.intdomain.intDomainFromSurvivors
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationProblem
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.parseBigInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DivModPropagatorTest {
    @Test
    fun `exhausted support retention should preserve domain filtering`() {
        val source = Problem(0, 3, arrayOf(
            intDomainFromSurvivors(longArrayOf(1, 5)), IntDomain(0, 2), IntDomain(0, 1),
        ), arrayOf(DivMod(0, bigIntOf(2), 1, 2)))
        val state = PropagationState(source, Assumptions.None)
        state.divModSupportCells = 1 shl 20
        val propagator = DivModPropagator(source.factors[0] as DivMod)

        assertTrue(propagator.propagate(state, 0))

        assertEquals(IntDomain(0, 2).excludeValue(1), state.intDomains[1])
        assertEquals(1 shl 20, state.divModSupportCells)
    }

    @Test
    fun `restored output holes should recover their signed supports`() {
        for (convention in DivisionConvention.entries) {
            val source = Problem(0, 4, arrayOf(
                IntDomain(-7, 7), IntDomain(-3, -3), IntDomain(-3, 3), IntDomain(-2, 2),
            ), arrayOf(DivMod(0, 1, 2, 3, convention)))
            val session = PropagationSession(source)
            val expected = if (convention == DivisionConvention.TRUNCATING) -7L else -5L

            assertIs<PropagationResult.Implied>(session.pinInt(2, 2))
            assertIs<PropagationResult.Implied>(session.pinInt(3, 0))
            session.popToLevel(0)
            assertIs<PropagationResult.Implied>(session.pinInt(2, 2))
            assertIs<PropagationResult.Implied>(session.pinInt(3,
                if (convention == DivisionConvention.TRUNCATING) -1 else 1))

            assertEquals(IntDomain(expected, expected), session.intDomain(0))
        }
    }

    @Test
    fun `small branch supports should rebuild after restoring an operand hole`() {
        for (restoredOperand in 0..1) {
            val source = Problem(0, 4, arrayOf(
                IntDomain(0, 64), IntDomain(1, 65), IntDomain(0, 64), IntDomain(0, 64),
            ), arrayOf(DivMod(0, 1, 2, 3)))
            val state = PropagationState(source, Assumptions.None)
            val propagator = DivModPropagator(source.factors[0] as DivMod)
            assertTrue(propagator.propagate(state, 0))
            val original = state.intDomains.copyOf()
            state.intDomains[0] = original[0].excludeValue(32)
            state.intDomains[1] = original[1].excludeValue(32)
            assertTrue(propagator.propagate(state, 0))
            for (variable in original.indices) state.intDomains[variable] = original[variable]
            val otherOperand = 1 - restoredOperand
            state.intDomains[otherOperand] = original[otherOperand].excludeValue(32).excludeValue(33)

            assertTrue(propagator.propagate(state, 0))

            assertTrue(32L in state.intDomains[restoredOperand])
        }
    }

    @Test
    fun `sparse dividend buckets should preserve distant sibling values`() {
        val high = 1_000_000_000_000L
        val source = Problem(0, 3, arrayOf(
            intDomainFromSurvivors(longArrayOf(0, high)),
            IntDomain(0, high / 2), IntDomain(0, 1),
        ), arrayOf(DivMod(0, bigIntOf(2), 1, 2)))
        val session = PropagationSession(source)
        assertIs<PropagationResult.Implied>(session.pinInt(0, 0))
        session.popLast()

        assertIs<PropagationResult.Implied>(session.pinInt(0, high))

        assertEquals(IntDomain(high / 2, high / 2), session.intDomain(1))
    }

    @Test
    fun `small branch supports should rebuild for a distant sibling of a wide root`() {
        val source = Problem(0, 3, arrayOf(
            IntDomain(0, 10000), IntDomain(0, 5000), IntDomain(0, 1),
        ), arrayOf(DivMod(0, bigIntOf(2), 1, 2)))
        val session = PropagationSession(source)
        assertIs<PropagationResult.Implied>(session.pinIntAtMost(0, 5))
        session.popLast()

        assertIs<PropagationResult.Implied>(session.pinIntAtLeast(0, 9998))

        assertEquals(IntDomain(4999, 5000), session.intDomain(1))
    }

    @Test
    fun `retained supports should preserve aliased outputs across branches`() {
        for (convention in DivisionConvention.entries) {
            val source = Problem(0, 2, arrayOf(IntDomain(0, 10), IntDomain(0, 3)),
                arrayOf(DivMod(0, bigIntOf(3), 1, 1, convention)))
            val session = PropagationSession(source)
            assertIs<PropagationResult.Implied>(session.pinInt(1, 0))
            session.popLast()

            assertIs<PropagationResult.Implied>(session.pinInt(1, 1))

            assertEquals(IntDomain(4, 4), session.intDomain(0))
        }
    }

    @Test
    fun `aliased operand roles should retain valid sibling assignments`() {
        for (convention in DivisionConvention.entries) {
            for ((relation, first, second) in listOf(
                Triple(DivMod(0, 0, 1, 2, convention), longArrayOf(-3, 1, 0), longArrayOf(3, 1, 0)),
                Triple(DivMod(0, 1, 0, 2, convention), longArrayOf(-2, 1, 0), longArrayOf(2, 1, 0)),
                Triple(DivMod(0, 1, 2, 0, convention), longArrayOf(1, 3, 0), longArrayOf(2, 3, 0)),
                Triple(DivMod(0, 1, 1, 2, convention), longArrayOf(4, 2, 0), longArrayOf(5, 2, 1)),
            )) {
                val session = PropagationSession(Problem(0, 3, Array(3) { IntDomain(-8, 8) }, arrayOf(relation)))
                for (variable in first.indices) {
                    assertIs<PropagationResult.Implied>(session.pinInt(variable, first[variable]))
                }
                session.popToLevel(0)

                for (variable in second.indices) {
                    assertIs<PropagationResult.Implied>(session.pinInt(variable, second[variable]))
                }

                for (variable in second.indices) {
                    assertEquals(IntDomain(second[variable], second[variable]), session.intDomain(variable))
                }
            }
        }
    }

    @Test
    fun `shared projection should isolate branch support construction`() {
        val source = Problem(0, 3, arrayOf(
            IntDomain(0, 10000), IntDomain(0, 5000), IntDomain(0, 1),
        ), arrayOf(DivMod(0, bigIntOf(2), 1, 2)))
        val projection = PropagationProblem(source)
        val first = PropagationSession(projection)
        val second = PropagationSession(projection)
        assertIs<PropagationResult.Implied>(first.pinIntAtMost(0, 5))

        assertIs<PropagationResult.Implied>(second.pinIntAtLeast(0, 9998))

        assertEquals(IntDomain(0, 2), first.intDomain(1))
        assertEquals(IntDomain(4999, 5000), second.intDomain(1))
    }

    @Test
    fun `retained supports should preserve arbitrary width literal division`() {
        for (convention in DivisionConvention.entries) {
            val source = Problem(0, 3, arrayOf(
                IntDomain(0, 5), IntDomain(0, 1), IntDomain(0, 5),
            ), arrayOf(DivMod(0, parseBigInt("9223372036854775808"), 1, 2, convention)))
            val session = PropagationSession(source)
            assertIs<PropagationResult.Implied>(session.pinInt(0, 1))
            session.popLast()

            assertIs<PropagationResult.Implied>(session.pinInt(0, 5))

            assertFalse(session.isUnsatAtRoot)
            assertEquals(IntDomain(0, 0), session.intDomain(1))
            assertEquals(IntDomain(5, 5), session.intDomain(2))
        }
    }

    @Test
    fun `tuple supports should retain separated quotient values`() {
        val problem = Problem(
            0,
            3,
            arrayOf(
                IntDomain(1, 5).excludeValue(2).excludeValue(3).excludeValue(4),
                IntDomain(0, 2),
                IntDomain(0, 1),
            ),
            arrayOf<Factor>(DivMod(0, bigIntOf(2), 1, 2)),
        )

        val session = PropagationSession(problem)

        assertEquals(IntDomain(0, 2).excludeValue(1), session.intDomain(1))
    }

    @Test
    fun `coupled filtering explanations should retain bound and hole premises`() {
        for (convention in DivisionConvention.entries) {
            for ((variable, value) in listOf(0 to -2L, 1 to 1L, 2 to 0L, 3 to 0L)) {
                val source = Problem(
                    0,
                    4,
                    Array(4) { IntDomain(-2, 2) },
                    arrayOf<Factor>(DivMod(0, 1, 2, 3, convention)),
                )
                PropagationReasonOracle.assertReasonsImply(source, "$convention $variable $value") { state ->
                    state.excludeIntValue(variable, value)
                }
            }
        }
    }
}
