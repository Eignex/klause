package com.eignex.klause.portfolio

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.SharedClause
import com.eignex.klause.propagation.addLearnedClause
import kotlin.test.Test
import kotlin.test.assertContentEquals
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
    fun `publishGlobal should share a globally-valid nogood the glue export would drop`() {
        val problem = twoIntVars()
        val pool = SharedClausePool()
        val producer = PoolClauseExchange(pool)
        val consumer = PoolClauseExchange(pool)

        val src = PropagationSession(problem)
        val nogood = intArrayOf(src.boundGeLit(0, 3, true), src.boundLeLit(1, 2, true))
        src.addLearnedClause(Clause(nogood), lbd = 9)
        assertTrue(src.exportGlueClauses(maxLbd = 4, maxLen = 8).isEmpty())

        producer.publishGlobal(src.asSharedClause(nogood, lbd = 9))
        val dst = PropagationSession(problem)
        consumer.onRestart(dst)
        assertEquals(1, dst.learnedClauseCount)
    }

    @Test
    fun `a widened pool filter should let the default exchange export past the glue bounds`() {
        val problem = twoIntVars()
        val pool = SharedClausePool(shareMaxLbd = 9, shareMaxLen = 10)
        val producer = PoolClauseExchange(pool)
        val consumer = PoolClauseExchange(pool)

        val src = PropagationSession(problem)
        src.addLearnedClause(Clause(intArrayOf(src.boundGeLit(0, 3, true), src.boundLeLit(1, 2, true))), lbd = 9)
        producer.onRestart(src)
        val dst = PropagationSession(problem)
        consumer.onRestart(dst)
        assertEquals(1, dst.learnedClauseCount)
    }

    @Test
    fun `publishGlobal should not re-import the publishing arm's own nogood`() {
        val problem = twoIntVars()
        val pool = SharedClausePool()
        val arm = PoolClauseExchange(pool)
        val session = PropagationSession(problem)
        val nogood = intArrayOf(session.boundGeLit(0, 3, true), session.boundLeLit(1, 2, true))

        arm.publishGlobal(session.asSharedClause(nogood, lbd = 9))
        arm.onRestart(session)
        assertEquals(0, session.learnedClauseCount)
    }

    @Test
    fun `pool should de-duplicate by key and advance the cursor`() {
        val pool = SharedClausePool()
        val a = SharedClause(intArrayOf(2, 5), LongArray(0), lbd = 2)
        val b = SharedClause(intArrayOf(4), LongArray(0), lbd = 1)
        val aDup = SharedClause(intArrayOf(2, 5), LongArray(0), lbd = 2)
        val c = SharedClause(IntArray(0), longArrayOf(0, 0, 3, 0), lbd = 1)

        pool.publish(listOf(a, b))
        val first = pool.drainSince(0)
        assertEquals(2, first.clauses.size)
        assertEquals(2L, first.cursor)

        pool.publish(listOf(aDup, c))
        val second = pool.drainSince(first.cursor)
        assertEquals(1, second.clauses.size)
        assertEquals(c.key, second.clauses.single().key)
    }

    @Test
    fun `pool exchange should import peer clauses but not its own`() {
        val problem = twoIntVars()
        val pool = SharedClausePool()
        val a = PortfolioExchangeFixture(problem, pool)
        val b = PortfolioExchangeFixture(problem, pool)

        a.session.addLearnedClause(
            Clause(intArrayOf(a.session.boundGeLit(0, 3, true), a.session.boundLeLit(1, 2, true))),
            lbd = 2,
        )
        a.exchange.onRestart(a.session)
        b.exchange.onRestart(b.session)
        assertEquals(1, b.session.learnedClauseCount)

        val before = a.session.learnedClauseCount
        a.exchange.onRestart(a.session)
        assertEquals(before, a.session.learnedClauseCount)
    }

    @Test
    fun `onSearchStart should re-import the arm's own clauses into a rebuilt session`() {
        val problem = twoIntVars()
        val pool = SharedClausePool()
        val exchange = PoolClauseExchange(pool)

        val seg1 = PropagationSession(problem)
        exchange.onSearchStart(seg1)
        seg1.addLearnedClause(
            Clause(intArrayOf(seg1.boundGeLit(0, 3, true), seg1.boundLeLit(1, 2, true))),
            lbd = 2,
        )
        exchange.onSearchEnd(seg1)

        val seg2 = PropagationSession(problem)
        assertEquals(0, seg2.learnedClauseCount)
        exchange.onSearchStart(seg2)
        assertEquals(1, seg2.learnedClauseCount)
    }

    @Test
    fun `onSearchEnd should export but never import`() {
        val problem = twoIntVars()
        val pool = SharedClausePool()
        val peerSession = PropagationSession(problem)
        peerSession.addLearnedClause(
            Clause(intArrayOf(peerSession.boundGeLit(0, 4, true), peerSession.boundLeLit(1, 1, true))),
            lbd = 2,
        )
        PoolClauseExchange(pool).onSearchEnd(peerSession)

        // Off-root imports are unsafe: the imported literals may all be false at the current level.
        val armSession = PropagationSession(problem)
        PoolClauseExchange(pool).onSearchEnd(armSession)
        assertEquals(0, armSession.learnedClauseCount)
    }

    private class PortfolioExchangeFixture(problem: Problem, pool: SharedClausePool) {
        val session = PropagationSession(problem)
        val exchange = PoolClauseExchange(pool)
    }

    private fun boolClause(id: Int, lbd: Int, len: Int = 2): SharedClause =
        SharedClause(IntArray(len) { (id + it) * 2 }, LongArray(0), lbd = lbd)

    @Test
    fun `compaction should keep low-LBD clauses over high-LBD ones`() {
        val pool = SharedClausePool(cap = 4)
        val keep = boolClause(0, lbd = 1)
        val drop = boolClause(10, lbd = 9)
        pool.publish(listOf(keep, drop, boolClause(20, lbd = 8), boolClause(30, lbd = 7)))
        pool.publish(listOf(boolClause(100, lbd = 2)))
        val kept = pool.drainSince(0).clauses.map { it.key }.toSet()
        assertTrue(keep.key in kept)
        assertTrue(drop.key !in kept)
    }

    @Test
    fun `compaction should retain a global nogood ahead of better-LBD glue`() {
        val pool = SharedClausePool(cap = 4)
        val global = boolClause(0, lbd = 9, len = 6)
        pool.publish(listOf(global), isGlobal = true)
        pool.publish((1..3).map { boolClause(it * 10, lbd = 1) })
        pool.publish(listOf(boolClause(100, lbd = 1)))
        val kept = pool.drainSince(0).clauses.map { it.key }.toSet()
        assertTrue(global.key in kept)
    }

    @Test
    fun `a stale cursor should re-drain the compacted pool without duplicate imports`() {
        val problem = Problem(
            numBoolVars = 200,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = emptyList(),
        )
        val pool = SharedClausePool(cap = 4)
        val exchange = PoolClauseExchange(pool)
        val session = PropagationSession(problem)

        pool.publish(listOf(boolClause(0, lbd = 1)))
        exchange.onRestart(session)
        val imported = session.learnedClauseCount
        pool.publish((1..8).map { boolClause(it * 10, lbd = it) })

        exchange.onRestart(session)
        val repeat = pool.drainSince(0).clauses.count { it.key == boolClause(0, lbd = 1).key }
        assertEquals(
            imported + pool.drainSince(0).clauses.size - repeat,
            session.learnedClauseCount,
        )
    }

    @Test
    fun `an evicted key should be able to re-enter the pool`() {
        val pool = SharedClausePool(cap = 4)
        val evictee = boolClause(0, lbd = 9)
        pool.publish(listOf(evictee))
        pool.publish((1..3).map { boolClause(it * 10, lbd = 1) })
        pool.publish(listOf(boolClause(100, lbd = 1)))
        var kept = pool.drainSince(0).clauses.map { it.key }.toSet()
        assertTrue(evictee.key !in kept)
        pool.publish(listOf(evictee))
        kept = pool.drainSince(0).clauses.map { it.key }.toSet()
        assertTrue(evictee.key in kept)
    }

    @Test
    fun `a drained clause should name the arm that first published it`() {
        val pool = SharedClausePool()
        val shared = boolClause(0, lbd = 1)
        pool.publish(listOf(shared), origin = 2)
        pool.publish(listOf(shared, boolClause(10, lbd = 1)), origin = 5)

        assertContentEquals(intArrayOf(2, 5), pool.drainSince(0).origins)
    }

    @Test
    fun `compaction should keep each surviving clause with its origin`() {
        val pool = SharedClausePool(cap = 4)
        pool.publish(listOf(boolClause(0, lbd = 9)), origin = 0)
        pool.publish(listOf(boolClause(10, lbd = 1)), origin = 1)
        pool.publish(listOf(boolClause(20, lbd = 8)), origin = 2)
        pool.publish(listOf(boolClause(30, lbd = 2)), origin = 3)
        pool.publish(listOf(boolClause(40, lbd = 9)), origin = 4)

        val drained = pool.drainSince(0)

        val lbdByOrigin = drained.clauses.indices.associate { drained.origins[it] to drained.clauses[it].lbd }
        assertEquals(mapOf(1 to 1, 3 to 2, 4 to 9), lbdByOrigin)
    }
}
