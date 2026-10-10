package com.eignex.klause.propagation

import com.eignex.klause.factor.table.Mdd
import com.eignex.klause.factor.table.Table
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.VarRemap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FactorProjectionPreparationTest {
    @Test
    fun `shared table preparation respects holes in another factor's domains`() {
        val relation = longArrayOf(0, 0, 1, 1, 2, 2)
        val problem = Problem(
            0, 4,
            arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2).excludeValue(1), IntDomain(0, 2)),
            arrayOf<Factor>(Table(intArrayOf(0, 1), relation), Table(intArrayOf(2, 3), relation)),
        )

        for (resumable in listOf(false, true)) {
            val projection = if (resumable) {
                PropagationProblem.preparation(problem).asSequence().filterNotNull().last()
            } else {
                PropagationProblem(problem)
            }
            val session = PropagationSession(projection)

            assertFalse(session.isUnsatAtRoot)
            assertTrue(1L in session.intDomain(0))
            assertFalse(1L in session.intDomain(3))
        }
    }

    @Test
    fun `shared diagram structure preserves different acceptance languages`() {
        val transitions = longArrayOf(0, 0, 0, 1, 1, 1)
        val starts = intArrayOf(0, 6)
        val states = intArrayOf(2, 2)
        val problem = Problem(
            0, 2, Array(2) { IntDomain(0, 1) },
            arrayOf<Factor>(
                Mdd(intArrayOf(0), states, starts, transitions, 0, intArrayOf(0), 3),
                Mdd(intArrayOf(1), states, starts, transitions, 1, intArrayOf(1), 3),
            ),
        )

        val session = PropagationSession(problem)

        assertFalse(session.isUnsatAtRoot)
        assertEquals(0L, session.intDomain(0).min)
        assertEquals(0L, session.intDomain(0).max)
        assertEquals(1L, session.intDomain(1).min)
        assertEquals(1L, session.intDomain(1).max)
    }

    @Test
    fun `diagram value remapping prepares indexes for the renamed symbols`() {
        val original = Mdd(
            intArrayOf(0), intArrayOf(1, 1), intArrayOf(0, 3), longArrayOf(0, 0, 0), 0, intArrayOf(0), 3,
        )
        val renamed = original.remapValues { it + 1 }.remap(VarRemap(intArrayOf(), intArrayOf(1)))
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 1) }, arrayOf<Factor>(original, renamed))

        val session = PropagationSession(problem)

        assertFalse(session.isUnsatAtRoot)
        assertEquals(0L, session.intDomain(0).max)
        assertEquals(1L, session.intDomain(1).min)
    }
}
