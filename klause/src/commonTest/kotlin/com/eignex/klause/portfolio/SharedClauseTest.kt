package com.eignex.klause.portfolio

import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.Clause
import com.eignex.klause.solver.propagation.PropagationSession
import com.eignex.klause.solver.propagation.SharedClause
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SharedClauseTest {

    private fun twoIntVars(): Problem = Problem(
        numBoolVars = 0,
        numIntVars = 2,
        intDomains = arrayOf(IntDomain(0, 5), IntDomain(0, 5)),
        factors = emptyList(),
    )

    @Test
    fun `export then import round-trips int-atom literals across sessions`() {
        // Source session learns the nogood  [x >= 3] ∨ [y <= 2]  and exports it.
        val src = PropagationSession(twoIntVars())
        val clause = Clause(intArrayOf(src.boundGeLit(0, 3, true), src.boundLeLit(1, 2, true)))
        src.addLearnedClause(clause, lbd = 2)
        val exported = src.exportGlueClauses(maxLbd = 10, maxLen = 10)
        assertEquals(1, exported.size, "the glue clause should export")

        // A fresh session of the same problem imports it — atoms re-allocated in its own space.
        val dst = PropagationSession(twoIntVars())
        assertEquals(0, dst.learnedClauseCount)
        dst.importClause(exported.single())
        assertEquals(1, dst.learnedClauseCount, "import should register the clause")

        // Re-exporting from the destination yields the same semantic key — the translation is exact.
        assertEquals(exported.single().key, dst.exportGlueClauses(10, 10).single().key)
    }

    @Test
    fun `glue filter drops high-LBD and long clauses`() {
        val s = PropagationSession(twoIntVars())
        s.addLearnedClause(Clause(intArrayOf(s.boundGeLit(0, 3, true), s.boundLeLit(1, 2, true))), lbd = 9)
        assertTrue(s.exportGlueClauses(maxLbd = 4, maxLen = 10).isEmpty(), "LBD 9 > maxLbd 4 → excluded")
        assertEquals(1, s.exportGlueClauses(maxLbd = 9, maxLen = 10).size)
        assertTrue(s.exportGlueClauses(maxLbd = 9, maxLen = 1).isEmpty(), "length 2 > maxLen 1 → excluded")
    }

    @Test
    fun `pool de-dups by key and advances the cursor`() {
        val pool = SharedClausePool()
        val a = SharedClause(intArrayOf(2, 5), IntArray(0), lbd = 2)
        val b = SharedClause(intArrayOf(4), IntArray(0), lbd = 1)
        val aDup = SharedClause(intArrayOf(2, 5), IntArray(0), lbd = 2) // same content as a → same key
        val c = SharedClause(IntArray(0), intArrayOf(0, 0, 3, 0), lbd = 1)

        pool.publish(listOf(a, b))
        val first = pool.drainSince(0)
        assertEquals(2, first.clauses.size)
        assertEquals(2, first.cursor)

        pool.publish(listOf(aDup, c)) // aDup deduped, only c lands
        val second = pool.drainSince(first.cursor)
        assertEquals(1, second.clauses.size, "only the genuinely new clause drains")
        assertEquals(c.key, second.clauses.single().key)
    }

    @Test
    fun `pool exchange imports peers' clauses but not its own`() {
        // Two arms over the same problem share one pool. Arm A learns a clause; after both restart,
        // B has imported it and A has not re-imported its own.
        val problem = twoIntVars()
        val pool = SharedClausePool()
        val a = PortfolioExchangeFixture(problem, pool)
        val b = PortfolioExchangeFixture(problem, pool)

        a.session.addLearnedClause(
            Clause(intArrayOf(a.session.boundGeLit(0, 3, true), a.session.boundLeLit(1, 2, true))),
            lbd = 2,
        )
        a.exchange.onRestart(a.session) // exports A's clause
        b.exchange.onRestart(b.session) // imports it
        assertEquals(1, b.session.learnedClauseCount, "B imports A's shared clause")

        val before = a.session.learnedClauseCount
        a.exchange.onRestart(a.session) // must not re-import its own export
        assertEquals(before, a.session.learnedClauseCount, "A does not re-import its own clause")
    }

    private class PortfolioExchangeFixture(problem: Problem, pool: SharedClausePool) {
        val session = PropagationSession(problem)
        val exchange = PoolClauseExchange(pool)
    }
}
