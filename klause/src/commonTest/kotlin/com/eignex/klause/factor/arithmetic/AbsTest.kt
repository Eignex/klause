package com.eignex.klause.factor.arithmetic

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.bake
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AbsTest {
    @Test
    fun `finite solutions agree with the source absolute value over domains with holes`() {
        val source = Problem(0, 2, arrayOf(IntDomain(-4, 4).excludeValue(-2), IntDomain(0, 4).excludeValue(3)),
            arrayOf(Abs(0, 1)))

        val solutions = BacktrackSolver(source.bake()).enumerate().map { it.ints.toList() }.toSet()

        assertEquals(setOf(listOf(-4L, 4L), listOf(-1L, 1L), listOf(0L, 0L), listOf(1L, 1L),
            listOf(2L, 2L), listOf(4L, 4L)), solutions)
    }

    @Test
    fun `inverse filtering removes unsupported signs and magnitudes`() {
        val source = Problem(0, 2, arrayOf(IntDomain(-5, 5).excludeValue(4), IntDomain(3, 4)), arrayOf(Abs(0, 1)))
        val session = PropagationSession(source)

        assertEquals(setOf(-4L, -3L, 3L), (-5L..5L).filter { it in session.intDomain(0) }.toSet())
    }

    @Test
    fun `minimum signed operand has no finite absolute value`() {
        val source = Problem(0, 2,
            arrayOf(IntDomain(Long.MIN_VALUE, Long.MIN_VALUE), IntDomain(0L, Long.MAX_VALUE)), arrayOf(Abs(0, 1)))

        assertTrue(PropagationSession(source).isUnsatAtRoot)
    }

    @Test
    fun `maximum magnitude remains representable`() {
        val source = Problem(0, 2, arrayOf(IntDomain(-Long.MAX_VALUE, -Long.MAX_VALUE),
            IntDomain(0L, Long.MAX_VALUE)), arrayOf(Abs(0, 1)))
        val session = PropagationSession(source)

        assertFalse(session.isUnsatAtRoot)
        assertEquals(Long.MAX_VALUE, session.intDomain(1).min)
    }

    @Test
    fun `result aliases retain precisely the nonnegative solutions`() {
        val source = Problem(0, 1, arrayOf(IntDomain(-2, 2)), arrayOf(Abs(0, 0)))

        val solutions = BacktrackSolver(source.bake()).enumerate().map { it.ints.single() }.toSet()

        assertEquals(setOf(0L, 1L, 2L), solutions)
    }

    @Test
    fun `noninjective remapping keeps both semantic roles`() {
        val source = Abs(0, 1)
        val mapping = VarRemap(intArrayOf(), intArrayOf(2, 2))
        val mapped = source.remap(mapping)

        assertContentEquals(intArrayOf(2), mapped.intVars)
        assertEquals(Abs(2, 2).structuralKey(), mapped.structuralKey())
        assertEquals(mapped.structuralKey().hashCode(), source.remapStructuralHash(mapping))
    }
}
