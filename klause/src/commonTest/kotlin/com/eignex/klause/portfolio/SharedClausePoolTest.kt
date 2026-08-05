package com.eignex.klause.portfolio

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.SharedClause
import com.eignex.klause.propagation.addLearnedClause
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SharedClausePoolTest {

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
    fun `publishGlobal shares a globally-valid nogood the glue export would drop`() {
        // A long / high-LBD LP Farkas nogood is globally valid but never clears the LBD glue filter, so
        // it must reach peers through the direct publish path instead (#844).
        val problem = twoIntVars()
        val pool = SharedClausePool()
        val producer = PoolClauseExchange(pool)
        val consumer = PoolClauseExchange(pool)

        val src = PropagationSession(problem)
        val nogood = intArrayOf(src.boundGeLit(0, 3, true), src.boundLeLit(1, 2, true))
        src.addLearnedClause(Clause(nogood), lbd = 9)
        assertTrue(src.exportGlueClauses(maxLbd = 4, maxLen = 8).isEmpty(), "glue export drops the high-LBD nogood")

        producer.publishGlobal(src.asSharedClause(nogood, lbd = 9))
        val dst = PropagationSession(problem)
        consumer.onRestart(dst)
        assertEquals(1, dst.learnedClauseCount, "a peer imports the globally-valid nogood despite its LBD")
    }

    @Test
    fun `a widened pool-carried filter lets the default exchange export past the glue bounds`() {
        // The scenario's clause-share knobs land on the pool; every default-constructed exchange
        // follows them, so one --param widens the filter for the whole portfolio.
        val problem = twoIntVars()
        val pool = SharedClausePool(shareMaxLbd = 9, shareMaxLen = 10)
        val producer = PoolClauseExchange(pool)
        val consumer = PoolClauseExchange(pool)

        val src = PropagationSession(problem)
        src.addLearnedClause(Clause(intArrayOf(src.boundGeLit(0, 3, true), src.boundLeLit(1, 2, true))), lbd = 9)
        producer.onRestart(src)
        val dst = PropagationSession(problem)
        consumer.onRestart(dst)
        assertEquals(1, dst.learnedClauseCount, "the LBD-9 clause clears the widened filter")
    }

    @Test
    fun `publishGlobal does not re-import the publishing arm's own nogood`() {
        val problem = twoIntVars()
        val pool = SharedClausePool()
        val arm = PoolClauseExchange(pool)
        val session = PropagationSession(problem)
        val nogood = intArrayOf(session.boundGeLit(0, 3, true), session.boundLeLit(1, 2, true))

        arm.publishGlobal(session.asSharedClause(nogood, lbd = 9))
        arm.onRestart(session) // drains the pool — must skip the arm's own published nogood
        assertEquals(0, session.learnedClauseCount, "the publisher does not import back its own nogood")
    }

    @Test
    fun `pool de-dups by key and advances the cursor`() {
        val pool = SharedClausePool()
        val a = SharedClause(intArrayOf(2, 5), LongArray(0), lbd = 2)
        val b = SharedClause(intArrayOf(4), LongArray(0), lbd = 1)
        val aDup = SharedClause(intArrayOf(2, 5), LongArray(0), lbd = 2) // same content as a → same key
        val c = SharedClause(IntArray(0), longArrayOf(0, 0, 3, 0), lbd = 1)

        pool.publish(listOf(a, b))
        val first = pool.drainSince(0)
        assertEquals(2, first.clauses.size)
        assertEquals(2L, first.cursor)

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

    @Test
    fun `onSearchStart re-imports the arm's own clauses into a rebuilt session`() {
        // Models a single-threaded portfolio segment boundary (#381): one arm learns and exports a
        // clause, its session is discarded, and the next segment's fresh session must get it back —
        // the per-arm `seen`/`cursor` would otherwise suppress re-importing the arm's own clauses.
        val problem = twoIntVars()
        val pool = SharedClausePool()
        val exchange = PoolClauseExchange(pool) // the SAME instance persists across segments

        val seg1 = PropagationSession(problem)
        exchange.onSearchStart(seg1) // empty pool → nothing imported
        seg1.addLearnedClause(
            Clause(intArrayOf(seg1.boundGeLit(0, 3, true), seg1.boundLeLit(1, 2, true))),
            lbd = 2,
        )
        exchange.onSearchEnd(seg1) // export the arm's clause to the pool

        // Segment 2: a brand-new session with an empty DB. onSearchStart must re-import the own nogood.
        val seg2 = PropagationSession(problem)
        assertEquals(0, seg2.learnedClauseCount)
        exchange.onSearchStart(seg2)
        assertEquals(1, seg2.learnedClauseCount, "the rebuilt segment re-imports the arm's own nogood")
    }

    @Test
    fun `onSearchEnd exports but never imports`() {
        val problem = twoIntVars()
        val pool = SharedClausePool()
        // A peer publishes a clause to the pool.
        val peerSession = PropagationSession(problem)
        peerSession.addLearnedClause(
            Clause(intArrayOf(peerSession.boundGeLit(0, 4, true), peerSession.boundLeLit(1, 1, true))),
            lbd = 2,
        )
        PoolClauseExchange(pool).onSearchEnd(peerSession)

        // An arm ending a slice mid-search must NOT import the peer's pooled clause: off-root imports
        // are unsafe (the literals may be all-false), so onSearchEnd is export-only.
        val armSession = PropagationSession(problem)
        PoolClauseExchange(pool).onSearchEnd(armSession)
        assertEquals(0, armSession.learnedClauseCount, "onSearchEnd never imports")
    }

    private class PortfolioExchangeFixture(problem: Problem, pool: SharedClausePool) {
        val session = PropagationSession(problem)
        val exchange = PoolClauseExchange(pool)
    }

    private fun boolClause(id: Int, lbd: Int, len: Int = 2): SharedClause =
        SharedClause(IntArray(len) { (id + it) * 2 }, LongArray(0), lbd = lbd)

    @Test
    fun `a publish over the cap compacts instead of refusing new clauses`() {
        val pool = SharedClausePool(cap = 4)
        pool.publish((0 until 4).map { boolClause(it * 10, lbd = it + 1) })
        pool.publish(listOf(boolClause(100, lbd = 1)))
        val all = pool.drainSince(0).clauses
        assertTrue(all.size <= 4)
        assertTrue(all.any { it.key == boolClause(100, lbd = 1).key }, "the pool keeps accepting after the cap")
    }

    @Test
    fun `compaction keeps low-LBD clauses over high-LBD ones`() {
        val pool = SharedClausePool(cap = 4)
        val keep = boolClause(0, lbd = 1)
        val drop = boolClause(10, lbd = 9)
        pool.publish(listOf(keep, drop, boolClause(20, lbd = 8), boolClause(30, lbd = 7)))
        pool.publish(listOf(boolClause(100, lbd = 2))) // overflow → compact to 2 best
        val kept = pool.drainSince(0).clauses.map { it.key }.toSet()
        assertTrue(keep.key in kept, "the glue clause survives compaction")
        assertTrue(drop.key !in kept, "the worst clause is evicted")
    }

    @Test
    fun `compaction retains a global nogood ahead of better-LBD glue`() {
        val pool = SharedClausePool(cap = 4)
        val global = boolClause(0, lbd = 9, len = 6)
        pool.publish(listOf(global), isGlobal = true)
        pool.publish((1..3).map { boolClause(it * 10, lbd = 1) })
        pool.publish(listOf(boolClause(100, lbd = 1))) // overflow → compact
        val kept = pool.drainSince(0).clauses.map { it.key }.toSet()
        assertTrue(global.key in kept, "a globally-published nogood outranks filtered glue")
    }

    @Test
    fun `a stale cursor re-drains the compacted pool and the exchange stays duplicate-free`() {
        // The fixture clauses use boolean literals, so the session's problem needs the variables.
        val problem = Problem(
            numBoolVars = 200,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = emptyList(),
        )
        val pool = SharedClausePool(cap = 4)
        val exchange = PoolClauseExchange(pool)
        val session = PropagationSession(problem)

        // The arm imports the pre-compaction pool, then a flood of publishes forces a compaction.
        pool.publish(listOf(boolClause(0, lbd = 1)))
        exchange.onRestart(session)
        val imported = session.learnedClauseCount
        pool.publish((1..8).map { boolClause(it * 10, lbd = it) })

        // The arm's cursor generation is stale: the drain restarts at 0 and `seen` filters the repeat.
        exchange.onRestart(session)
        val repeat = pool.drainSince(0).clauses.count { it.key == boolClause(0, lbd = 1).key }
        assertEquals(
            imported + pool.drainSince(0).clauses.size - repeat,
            session.learnedClauseCount,
            "the surviving new clauses import once; the already-seen clause does not re-import",
        )
    }

    @Test
    fun `an evicted key can re-enter the pool`() {
        val pool = SharedClausePool(cap = 4)
        val evictee = boolClause(0, lbd = 9)
        pool.publish(listOf(evictee))
        pool.publish((1..3).map { boolClause(it * 10, lbd = 1) })
        pool.publish(listOf(boolClause(100, lbd = 1))) // overflow → compact, evicting the LBD-9 clause
        var kept = pool.drainSince(0).clauses.map { it.key }.toSet()
        assertTrue(evictee.key !in kept)
        pool.publish(listOf(evictee))
        kept = pool.drainSince(0).clauses.map { it.key }.toSet()
        assertTrue(evictee.key in kept, "eviction releases the key for re-publication")
    }
}
