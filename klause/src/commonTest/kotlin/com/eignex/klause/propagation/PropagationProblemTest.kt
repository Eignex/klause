package com.eignex.klause.propagation

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.model.PbOp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PropagationProblemTest {

    @Test
    fun `sessions sharing a native projection keep their decisions independent`() {
        val problem = Problem(
            3,
            0,
            emptyArray(),
            listOf(Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true)))),
        )
        val projection = PropagationProblem(problem)
        val first = PropagationSession(projection, nativeSat = true)
        val second = PropagationSession(projection, nativeSat = true)

        first.pinBool(0, false)
        first.pinBool(1, false)
        second.pinBool(2, false)
        second.pinBool(1, false)

        assertEquals(listOf(false, false, true), (0..2).map(first::boolValue))
        assertEquals(listOf(true, false, false), (0..2).map(second::boolValue))
    }

    @Test
    fun `the watcher-filtered bool list drops a watcher-using factor from every variable`() {
        val clause = Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true)))
        val pb = PseudoBoolean(longArrayOf(1, 2), intArrayOf(Lit.make(1, true), Lit.make(2, true)), PbOp.LE, 2L)
        val occ = PropagationProblem(Problem(3, 0, emptyArray(), listOf(clause, pb)))
        assertEquals(listOf(0), occ.boolOccurrences[0].toList())
        assertEquals(listOf(0, 1), occ.boolOccurrences[1].toList())
        assertEquals(listOf(1), occ.boolOccurrences[2].toList())
        assertTrue(occ.nonBoolWatcherBoolOccurrences[0].isEmpty(), "the clause wakes through its literal watchers")
        assertEquals(listOf(1), occ.nonBoolWatcherBoolOccurrences[1].toList())
        assertEquals(listOf(1), occ.nonBoolWatcherBoolOccurrences[2].toList())
    }

    @Test
    fun `the event-filtered int list drops a factor only for the variables it watches`() {
        val watching = AllDifferent(intArrayOf(0, 1), domainMin = 0, domainSize = 3, boundsConsistent = true)
        val plain = AllDifferent(intArrayOf(1, 2), domainMin = 0, domainSize = 3)
        val domains = arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2))
        val occ = PropagationProblem(Problem(0, 3, domains, listOf(watching, plain)))
        assertEquals(listOf(0), occ.intOccurrences[0].toList())
        assertEquals(listOf(0, 1), occ.intOccurrences[1].toList())
        assertEquals(listOf(1), occ.intOccurrences[2].toList())
        assertTrue(occ.nonIntEventWatcherIntOccurrences[0].isEmpty(), "the subscriber wakes on var 0 via its events")
        assertEquals(listOf(1), occ.nonIntEventWatcherIntOccurrences[1].toList())
        assertEquals(listOf(1), occ.nonIntEventWatcherIntOccurrences[2].toList())
    }

    @Test
    fun `propagation projection owns its occurrence lists`() {
        val problem = Problem(2, 0, emptyArray(), listOf(Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true)))))
        val first = PropagationProblem(problem)
        val second = PropagationProblem(problem)
        assertTrue(first.boolOccurrences.contentDeepEquals(second.boolOccurrences))
    }
}
