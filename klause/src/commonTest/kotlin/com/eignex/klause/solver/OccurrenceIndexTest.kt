package com.eignex.klause.solver

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.model.PbOp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class OccurrenceIndexTest {

    @Test
    fun `inverts a factor into per-variable occurrence lists`() {
        val clause = Clause(intArrayOf(Lit.make(0, true), Lit.make(2, true)))
        val occ = Problem(3, 0, emptyArray(), listOf(clause)).occurrences
        assertEquals(listOf(0), occ.boolOccurrences[0].toList())
        assertTrue(occ.boolOccurrences[1].isEmpty(), "var 1 is not mentioned by the clause")
        assertEquals(listOf(0), occ.boolOccurrences[2].toList())
    }

    @Test
    fun `a variable's occurrence list names every factor mentioning it`() {
        val c0 = Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true)))
        val c1 = Clause(intArrayOf(Lit.make(1, true), Lit.make(2, true)))
        val occ = Problem(3, 0, emptyArray(), listOf(c0, c1)).occurrences
        assertEquals(listOf(0), occ.boolOccurrences[0].toList())
        assertEquals(listOf(0, 1), occ.boolOccurrences[1].toList())
        assertEquals(listOf(1), occ.boolOccurrences[2].toList())
    }

    @Test
    fun `an unmentioned variable has an empty occurrence list in both var spaces`() {
        val clause = Clause(intArrayOf(Lit.make(0, true)))
        val linear = Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 2)
        val domains = arrayOf(IntDomain(0, 3), IntDomain(0, 3))
        val occ = Problem(2, 2, domains, listOf(clause, linear)).occurrences
        assertTrue(occ.boolOccurrences[1].isEmpty(), "bool var 1 is mentioned by no factor")
        assertTrue(occ.intOccurrences[1].isEmpty(), "int var 1 is mentioned by no factor")
    }

    @Test
    fun `the watcher-filtered bool list drops a watcher-using factor from every variable`() {
        val clause = Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true)))
        val pb = PseudoBoolean(longArrayOf(1, 2), intArrayOf(Lit.make(1, true), Lit.make(2, true)), PbOp.LE, 2L)
        val occ = Problem(3, 0, emptyArray(), listOf(clause, pb)).occurrences
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
        val occ = Problem(0, 3, domains, listOf(watching, plain)).occurrences
        assertEquals(listOf(0), occ.intOccurrences[0].toList())
        assertEquals(listOf(0, 1), occ.intOccurrences[1].toList())
        assertEquals(listOf(1), occ.intOccurrences[2].toList())
        assertTrue(occ.nonIntEventWatcherIntOccurrences[0].isEmpty(), "the subscriber wakes on var 0 via its events")
        assertEquals(listOf(1), occ.nonIntEventWatcherIntOccurrences[1].toList())
        assertEquals(listOf(1), occ.nonIntEventWatcherIntOccurrences[2].toList())
    }

    @Test
    fun `local-search lists alias the deductive lists when no factor splits its roles`() {
        val problem = Problem(2, 0, emptyArray(), listOf(Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true)))))
        assertSame(
            problem.occurrences.boolOccurrences,
            problem.occurrences.lsBoolOccurrences,
            "with both roles present the LS list should reuse the deductive array, not rebuild it",
        )
    }
}
