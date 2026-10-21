package com.eignex.klause.presolve

import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SatClauseDbTest {
    private fun pos(v: Int) = Lit.make(v, true)
    private fun neg(v: Int) = Lit.make(v, false)

    private fun db(numBool: Int, factors: List<Factor>, objectiveBoolVars: Set<Int> = emptySet()) =
        assertNotNull(SatClauseDb.build(Problem(numBool, 0, emptyArray(), factors), objectiveBoolVars))

    @Test
    fun `a variable touched by a non-clause factor should be ineligible`() {
        val d = db(
            3,
            listOf(
                Clause(intArrayOf(pos(0), pos(2))),
                Cardinality(intArrayOf(pos(0), pos(1)), min = 1, max = 1),
            ),
        )
        assertFalse(d.eligible[0])
        assertFalse(d.eligible[1])
        assertTrue(d.eligible[2])
    }

    @Test
    fun `a tautological clause should never enter a slot and should be dropped by the delta`() {
        val d = db(2, listOf(Clause(intArrayOf(pos(0), neg(0))), Clause(intArrayOf(pos(1)))))
        assertEquals(1, d.slotCount)
        assertEquals(1, d.origin(0))
        assertContentEquals(intArrayOf(0), d.toDelta(SourceRebuilds.NONE).droppedIndices)
    }

    @Test
    fun `the delta should drop consumed inputs and add surviving derived clauses`() {
        val d = db(3, listOf(Clause(intArrayOf(pos(0), pos(1))), Clause(intArrayOf(neg(0), pos(2)))))
        d.remove(0)
        val derived = d.add(intArrayOf(pos(1), pos(2)))
        assertNull(d.clause(0))
        assertEquals(-1, d.origin(derived))

        val delta = d.toDelta(SourceRebuilds.NONE)
        assertContentEquals(intArrayOf(0), delta.droppedIndices)
        assertContentEquals(
            listOf(listOf(pos(1), pos(2))),
            delta.addedFactors.map { (it as Clause).literals.toList() },
        )
    }

    @Test
    fun `a derived clause removed before the delta should contribute nothing`() {
        val d = db(2, listOf(Clause(intArrayOf(pos(0), pos(1)))))
        d.remove(d.add(intArrayOf(neg(0))))
        assertTrue(d.toDelta(SourceRebuilds.NONE).isEmpty)
    }

    @Test
    fun `interrupted construction should discard every partial index and retain its work`() {
        val problem = Problem(
            3, 0, emptyArray(),
            listOf(Clause(intArrayOf(pos(0), neg(0))), Clause(intArrayOf(pos(0), pos(1)))),
        )
        for (short in listOf(false, true)) {
            val full = PresolveBudget(Long.MAX_VALUE)
            val token = full.orSpent(Cancellation.Never)
            val complete = assertNotNull(if (short) {
                SatClauseDb.buildShortClauses(problem, 8) {
                    token.charge(10L)
                    !token()
                }
            } else SatClauseDb.build(problem, emptySet(), token))
            assertEquals(1, complete.slotCount)
            assertEquals(1, complete.occ(pos(0)).size)
            assertEquals(1, complete.occ(pos(1)).size)
            assertEquals(1, complete.origin(0))
            for (allowance in 1L..full.spent() step 7) {
                val budget = PresolveBudget(allowance)
                val stop = budget.orSpent(Cancellation.Never)

                val partial = if (short) {
                    SatClauseDb.buildShortClauses(problem, 8) {
                        stop.charge(10L)
                        !stop()
                    }
                } else SatClauseDb.build(problem, emptySet(), stop)

                assertNull(partial, "short=$short allowance=$allowance")
                assertTrue(budget.spent() in allowance..allowance + 9L)
            }
        }
    }

    @Test
    fun `external cancellation during occurrence indexing should discard the database`() {
        val problem = Problem(3, 0, emptyArray(), listOf(Clause(intArrayOf(pos(0), pos(1), pos(2)))))
        var polls = 0

        val partial = SatClauseDb.build(problem, emptySet(), Cancellation { ++polls >= 17 })

        assertNull(partial)
        assertEquals(17, polls)
    }
}
