package com.eignex.klause.solver

import com.eignex.klause.factor.bool.Clause
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
    fun `local-search lists alias the deductive lists when no factor splits its roles`() {
        val problem = Problem(2, 0, emptyArray(), listOf(Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true)))))
        assertSame(
            problem.occurrences.boolOccurrences,
            problem.occurrences.lsBoolOccurrences,
            "with both roles present the LS list should reuse the deductive array, not rebuild it",
        )
    }
}
