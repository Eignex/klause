package com.eignex.klause.factor.arithmetic

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.fitsLong
import com.eignex.klause.util.toLongExact
import com.eignex.klause.util.parseBigInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

class DivModTest {
    @Test
    fun `division conventions preserve signed quotient and remainder`() {
        for ((operands, results) in listOf(
            (-7L to 3L) to ((-2L to -1L) to (-3L to 2L)),
            (-7L to -3L) to ((2L to -1L) to (3L to 2L)),
            (7L to -3L) to ((-2L to 1L) to (-2L to 1L)),
        )) {
            for ((convention, expected) in listOf(DivisionConvention.TRUNCATING to results.first,
                DivisionConvention.EUCLIDEAN to results.second)) {
                val actual = DivMod(0, 1, 2, 3, convention).outputs(bigIntOf(operands.first), bigIntOf(operands.second))
                assertEquals(bigIntOf(expected.first) to bigIntOf(expected.second), actual)
            }
        }
    }

    @Test
    fun `minimum signed dividend retains its exact overflowing quotient`() {
        val outputs = assertNotNull(DivMod(0, 1, 2, 3).outputs(bigIntOf(Long.MIN_VALUE), bigIntOf(-1)))

        assertEquals(parseBigInt("9223372036854775808"), outputs.first)
        assertEquals(bigIntOf(0), outputs.second)
    }

    @Test
    fun `word division agrees with exact arithmetic at signed boundaries`() {
        for (convention in DivisionConvention.entries) {
            val relation = DivMod(0, 1, 2, 3, convention)
            for ((a, b) in listOf(Long.MIN_VALUE to Long.MIN_VALUE, Long.MIN_VALUE to -1L,
                Long.MIN_VALUE to 1L, Long.MIN_VALUE to 3L, -7L to Long.MIN_VALUE,
                7L to Long.MIN_VALUE, Long.MAX_VALUE to -3L)) {
                val exact = assertNotNull(relation.outputs(bigIntOf(a), bigIntOf(b)))
                val expected = if (exact.first.fitsLong() && exact.second.fitsLong())
                    exact.first.toLongExact() to exact.second.toLongExact() else null

                assertEquals(expected, relation.wordOutputs(a, b), "$convention $a $b")
            }
        }
    }

    @Test
    fun `coupled outputs remove unsupported divisor values`() {
        val problem = Problem(0, 4, arrayOf(IntDomain(17, 17), IntDomain(3, 6),
            IntDomain(3, 3), IntDomain(2, 2)), arrayOf<Factor>(DivMod(0, 1, 2, 3)))

        val session = PropagationSession(problem)

        assertFalse(session.isUnsatAtRoot)
        assertEquals(IntDomain(5, 5), session.intDomain(1))
    }

    @Test
    fun `fixed quotient and remainder determine a divisor beyond tuple enumeration`() {
        val source = Problem(0, 4, arrayOf(IntDomain(17, 17), IntDomain(1, 1_000_000),
            IntDomain(3, 3), IntDomain(2, 2)), arrayOf<Factor>(DivMod(0, 1, 2, 3)))

        val session = PropagationSession(source)

        assertFalse(session.isUnsatAtRoot)
        assertEquals(IntDomain(5, 5), session.intDomain(1))
    }

    @Test
    fun `retracting dividend fixings restores correlated outputs`() {
        val problem = Problem(0, 3, arrayOf(IntDomain(-7, 7), IntDomain(-3, 3), IntDomain(-2, 2)),
            arrayOf<Factor>(DivMod(0, bigIntOf(3), 1, 2)))
        val session = PropagationSession(problem)
        val quotient = session.intDomain(1)
        val remainder = session.intDomain(2)

        session.pinInt(0, -7)
        assertEquals(IntDomain(-2, -2), session.intDomain(1))
        assertEquals(IntDomain(-1, -1), session.intDomain(2))
        session.popLast()

        assertEquals(quotient, session.intDomain(1))
        assertEquals(remainder, session.intDomain(2))
    }

    @Test
    fun `inconsistent repeated outputs are rejected`() {
        val source = DivMod(0, bigIntOf(3), 1, 2)
        val mapped = source.remap(VarRemap(intArrayOf(), intArrayOf(0, 1, 1)))
        val problem = Problem(0, 2, arrayOf(IntDomain(7, 7), IntDomain(-3, 3)), arrayOf(mapped))

        val session = PropagationSession(problem)

        assertEquals(true, session.isUnsatAtRoot)
    }

    @Test
    fun `zero divisor is removed without forbidding nonzero choices`() {
        val problem = Problem(0, 4, arrayOf(IntDomain(0, 0), IntDomain(-1, 1), IntDomain(0, 0),
            IntDomain(0, 0)), arrayOf<Factor>(DivMod(0, 1, 2, 3)))
        val session = PropagationSession(problem)

        assertFalse(0L in session.intDomain(1))
        assertIs<PropagationResult.Unsat>(session.pinInt(1, 0L))
    }

    @Test
    fun `structural identity separates conventions and preserves remapped roles`() {
        val source = DivMod(0, 1, 2, 3)
        val mapping = VarRemap(intArrayOf(), intArrayOf(1, 0, 1, 0))

        assertNotEquals(source.structuralKey(), DivMod(0, 1, 2, 3, DivisionConvention.EUCLIDEAN).structuralKey())
        assertEquals(source.remap(mapping).structuralKey().hashCode(), source.remapStructuralHash(mapping))
    }
}
