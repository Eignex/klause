package com.eignex.klause.factor.arithmetic

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class PowerTest {
    @Test
    fun `signed endpoint powers distinguish overflow from exact minimum`() {
        for ((base, exponent, expected) in listOf(
            Triple(0L, 0, 1L), Triple(Long.MIN_VALUE, 1, Long.MIN_VALUE),
            Triple(-2L, 63, Long.MIN_VALUE), Triple(-1L, Int.MAX_VALUE, -1L),
            Triple(1L, Int.MAX_VALUE, 1L), Triple(-3L, 3, -27L),
        )) assertEquals(expected, integerPowerOrNull(base, exponent))
        for ((base, exponent) in listOf(2L to 63, Long.MIN_VALUE to 2, Long.MAX_VALUE to 2)) {
            assertNull(integerPowerOrNull(base, exponent))
        }
    }

    @Test
    fun `integer remapping preserves an aliased power relation`() {
        val mapped = Power(1, 3, 2).remap(VarRemap(intArrayOf(), intArrayOf(9, 4, 4)))

        assertEquals(Power(4, 3, 4).structuralKey(), mapped.structuralKey())
        assertEquals(listOf(4), mapped.intVars.toList())
    }

    @Test
    fun `real coordinates have distinct structural identity`() {
        val real = Power(Power.Coordinate.Real(SourceRealId(0)), 2, Power.Coordinate.Real(SourceRealId(1)))

        assertNotEquals(Power(0, 2, 1).structuralKey(), real.structuralKey())
        assertEquals(listOf(0, 1), real.variables.reals.toList())
    }

    @Test
    fun `even inverse filtering preserves both roots and domain holes`() {
        val problem = Problem(0, 2, arrayOf(IntDomain(-5, 5).excludeValue(-2), IntDomain(4, 4)),
            arrayOf<Factor>(Power(0, 2, 1)))
        val session = PropagationSession(problem)

        assertIs<PropagationResult.Implied>(session.seed(Assumptions.None))

        assertEquals(IntDomain(2, 2), session.intDomain(0))
    }

    @Test
    fun `nonpowers are rejected without enumerating a wide operand domain`() {
        val problem = Problem(0, 2, arrayOf(IntDomain(Long.MIN_VALUE, Long.MAX_VALUE), IntDomain(5, 5)),
            arrayOf<Factor>(Power(0, 2, 1)))

        val result = PropagationSession(problem).seed(Assumptions.None)

        assertIs<PropagationResult.Unsat>(result)
    }

    @Test
    fun `power aliases retain exactly the integer fixed points`() {
        for ((exponent, expected) in listOf(0 to setOf(1L), 1 to (-2L..2L).toSet(),
            2 to setOf(0L, 1L), 3 to setOf(-1L, 0L, 1L))) {
            val problem = Problem(0, 1, arrayOf(IntDomain(-2, 2)), arrayOf<Factor>(Power(0, exponent, 0)))

            val values = BacktrackSolver(problem.bake()).enumerate(BacktrackParams(maxDecisions = 50))
                .map { it.intValue(0) }.toSet()

            assertEquals(expected, values, "exponent $exponent")
        }
    }

    @Test
    fun `retracted inverse bounds admit the opposite sign sibling`() {
        val problem = Problem(0, 2, arrayOf(IntDomain(-3, 3), IntDomain(0, 9)), arrayOf<Factor>(Power(0, 2, 1)))
        val session = PropagationSession(problem)
        session.seed(Assumptions.None)
        session.pinInt(1, 4)
        session.pinIntAtLeast(0, 0)
        assertEquals(IntDomain(2, 2), session.intDomain(0))

        session.popLast()
        val result = session.pinInt(0, -2)

        assertIs<PropagationResult.Implied>(result)
        assertEquals(IntDomain(-2, -2), session.intDomain(0))
    }

    @Test
    fun `overflowing powers cannot satisfy a saturated endpoint`() {
        val problem = Problem(0, 2, arrayOf(IntDomain(3037000500L, 3037000500L),
            IntDomain(Long.MAX_VALUE, Long.MAX_VALUE)), arrayOf<Factor>(Power(0, 2, 1)))

        val result = BacktrackSolver(problem.bake()).solve(BacktrackParams(maxDecisions = 0))

        assertIs<SolveResult.Unsat>(result)
    }
}
