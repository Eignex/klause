package com.eignex.klause.portfolio

import com.eignex.klause.RuntimeInitializedTest
import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.SharedClause
import com.eignex.klause.propagation.addLearnedClause
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SharingChannel
import com.eignex.klause.util.ExecutionControl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PoolClauseExchangeTest : RuntimeInitializedTest() {
    @Test
    fun `integer peer clauses should propagate after a satisfied repair root changes`() {
        val problem = Problem(0, 6, Array(6) { IntDomain(0, 1) }, emptyArray())
        val peer = SharedClausePool()
        val quads = LongArray(6 * SharedClause.QUAD)
        for (i in 0 until 6) {
            quads[i * SharedClause.QUAD] = i.toLong()
            quads[i * SharedClause.QUAD + 2] = 1L
        }
        peer.publish(listOf(SharedClause(intArrayOf(), quads, 1)), origin = 0, isGlobal = true)
        val meter = SharingMeter()
        val exchange = PoolClauseExchange(SharedClausePool(), meter = meter, peerPool = peer)
        val session = PropagationSession(problem)
        session.seed(Assumptions(ints = mapOf(5 to 1L)))
        exchange.onSearchStart(session)
        session.pinInt(4, 1L)
        exchange.onSearchEnd(session)
        assertNull(meter.snapshot().clauses["origin0Uses"])

        session.reseedFrom(Assumptions(ints = (0 until 5).associateWith { 0L }))
        exchange.onSearchStart(session)

        assertEquals(IntDomain(1, 1), session.intDomain(5))
        assertEquals(1L, meter.snapshot().clauses.getValue("origin0Uses"))
        assertEquals(1L, meter.snapshot().channels.getValue(SharingChannel.Clauses).imported)
        exchange.onSearchClose(session)
    }

    @Test
    fun `simultaneous pool duplicates should retain peer use attribution once per session`() {
        for (native in listOf(false, true)) {
            val private = SharedClausePool()
            val peer = SharedClausePool()
            val shared = sharedPoolTestClause(0, 1)
            private.publish(listOf(shared))
            peer.publish(listOf(shared), origin = 0, isGlobal = true)
            val tally = ContributionTally()
            val meter = SharingMeter()
            val exchange = PoolClauseExchange(private, tally = tally, meter = meter, peerPool = peer)
            val problem = Problem(4, 0, emptyArray(), emptyList())
            val session = PropagationSession(problem, nativeSat = native)
            val credits = HashMap<Pair<Contribution, Int>, Double>()
            exchange.onSearchStart(session)
            tally.drain { kind, origin, amount -> credits[kind to origin] = amount }
            assertTrue(credits.isEmpty())

            session.pinBool(0, false)
            exchange.onSearchEnd(session)
            exchange.onSearchStart(session)
            exchange.onSearchClose(session)
            tally.drain { kind, origin, amount -> credits[kind to origin] = amount }

            assertEquals(true, session.boolValue(1))
            assertEquals(1, session.learnedClauseCount)
            assertEquals(mapOf((Contribution.Clause to 0) to 1.0), credits)
            assertEquals(1L, meter.snapshot().clauses.getValue("origin0Uses"))
            val fresh = PropagationSession(problem, nativeSat = native)
            exchange.onSearchStart(fresh)
            assertEquals(1, fresh.learnedClauseCount)
            exchange.onSearchClose(fresh)
        }
    }

    @Test
    fun `a later peer duplicate should not relabel a retained private clause`() {
        val private = SharedClausePool()
        val peer = SharedClausePool()
        val shared = sharedPoolTestClause(0, 1)
        private.publish(listOf(shared))
        val meter = SharingMeter()
        val exchange = PoolClauseExchange(private, meter = meter, peerPool = peer)
        val session = PropagationSession(Problem(4, 0, emptyArray(), emptyList()))
        exchange.onSearchStart(session)
        peer.publish(listOf(shared), origin = 0)

        exchange.onRestart(session)
        session.pinBool(0, false)
        exchange.onSearchEnd(session)

        assertEquals(true, session.boolValue(1))
        assertEquals(1, session.learnedClauseCount)
        assertNull(meter.snapshot().clauses["origin0Uses"])
        exchange.onSearchClose(session)
    }

    @Test
    fun `conditioned retirement should export glue only to the private pool`() {
        for (native in listOf(false, true)) {
            val private = SharedClausePool()
            val peer = SharedClausePool()
            val shared = sharedPoolTestClause(0, 1)
            peer.publish(listOf(shared), origin = 0)
            val exchange = PoolClauseExchange(private, peerPool = peer).conditioned()
            val session = PropagationSession(Problem(20, 0, emptyArray(), emptyList()), nativeSat = native)
            exchange.onSearchStart(session)
            val glue = sharedPoolTestClause(2, 1)
            session.addLearnedClause(Clause(glue.boolLits), 1)
            session.addLearnedClause(Clause(sharedPoolTestClause(4, 1).boolLits), 1, permanent = true)

            exchange.publishGlobal(sharedPoolTestClause(8, 1))
            exchange.onSearchClose(session)

            assertEquals(listOf(shared.key), peer.drainSince(0).clauses.map { it.key })
            assertEquals(listOf(glue.key), private.drainSince(0).clauses.map { it.key })
            assertEquals(listOf(SharedClausePool.NO_ORIGIN), private.drainSince(0).origins.toList())
        }
    }

    @Test
    fun `each pool generation should advance independently without duplicate imports`() {
        for (compactPeer in listOf(false, true)) {
            val private = SharedClausePool(cap = 4)
            val peer = SharedClausePool(cap = 4)
            private.publish(listOf(sharedPoolTestClause(0, 1)))
            peer.publish(listOf(sharedPoolTestClause(20, 1)), origin = 0)
            val exchange = PoolClauseExchange(private, peerPool = peer)
            val problem = Problem(200, 0, emptyArray(), emptyList())
            val session = PropagationSession(problem)
            exchange.onSearchStart(session)
            val original = (private.drainSince(0).clauses + peer.drainSince(0).clauses).map { it.key }.toSet()
            val compacted = if (compactPeer) peer else private
            compacted.publish((1..8).map { sharedPoolTestClause(40 + it * 10, it) })
            val retained = (private.drainSince(0).clauses + peer.drainSince(0).clauses).map { it.key }.toSet()

            exchange.onRestart(session)
            exchange.onRestart(session)

            assertEquals((original + retained).size, session.learnedClauseCount)
            exchange.onSearchClose(session)
            val fresh = PropagationSession(problem)
            exchange.onSearchStart(fresh)
            assertEquals(retained.size, fresh.learnedClauseCount)
            exchange.onSearchClose(fresh)
        }
    }

    @Test
    fun `cancelled pool suffixes should resume without losing or reattaching their prefix`() {
        for (bulkPeer in listOf(false, true)) {
            for (stopAfter in listOf(0, 1, 3)) {
                val private = SharedClausePool()
                val peer = SharedClausePool()
                (if (bulkPeer) peer else private).publish((0 until 20).map { sharedPoolTestClause(it * 3, 1) })
                val meter = SharingMeter()
                val exchange = PoolClauseExchange(private, peerPool = peer, meter = meter)
                var resumed = false
                var active: PropagationSession? = null
                val session = PropagationSession(Problem(100, 0, emptyArray(), emptyList()),
                    cancellation = ExecutionControl { !resumed && (active?.learnedClauseCount ?: 0) >= stopAfter })
                active = session
                exchange.onSearchStart(session)
                assertEquals(stopAfter, session.learnedClauseCount)

                resumed = true
                exchange.onSearchStart(session)
                exchange.onRestart(session)

                assertEquals(20, session.learnedClauseCount)
                assertEquals(20L, meter.snapshot().channels.getValue(SharingChannel.Clauses).imported)
                exchange.onSearchClose(session)
            }
        }
    }

    @Test
    fun `work limited imports should keep both suffixes available for later grants`() {
        for (bulkPeer in listOf(false, true)) {
            val private = SharedClausePool()
            val peer = SharedClausePool()
            (if (bulkPeer) peer else private).publish((0 until 30).map { sharedPoolTestClause(it * 3, 1) })
            val meter = SharingMeter()
            val exchange = PoolClauseExchange(private, peerPool = peer, meter = meter)
            val session = PropagationSession(Problem(100, 0, emptyArray(), emptyList()))

            for (grant in listOf(0L, 100L, 300L, 1_000L, 10_000L)) {
                session.withWorkLimit(grant) { exchange.onSearchStart(session) }
            }

            assertEquals(30, session.learnedClauseCount)
            assertEquals(30L, meter.snapshot().channels.getValue(SharingChannel.Clauses).imported)
            assertTrue(meter.snapshot().clauses.getValue("importWork") > 0L)
            exchange.onSearchClose(session)
        }
    }

    @Test
    fun `repair rebinding should retain peer clauses under changing pins and tighter cutoffs`() {
        val problem = Problem(2, 0, emptyArray(), arrayOf(Clause(intArrayOf(0, 2)), Clause(intArrayOf(1, 2)))).bake()
        val objective = LinearObjective(boolWeights = longArrayOf(1, 1))
        val private = SharedClausePool()
        val peer = SharedClausePool()
        val shared = sharedPoolTestClause(1, 1, len = 1)
        peer.publish(listOf(shared), origin = 0)
        val exchange = PoolClauseExchange(private, skipPermanent = true, shareGlobalNogoods = false, peerPool = peer)

        BacktrackSolver(problem).openRepair(objective, BacktrackParams(clauseExchange = exchange)).use { repair ->
            val first = repair.repairStep(Assumptions(bools = mapOf(0 to true)), 100L, 3.0, ExecutionControl.Never)
            val second = repair.repairStep(Assumptions(bools = mapOf(0 to false)), 100L, 2.0, ExecutionControl.Never)
            val third = repair.repairStep(Assumptions(bools = mapOf(0 to true)), 100L, 1.5, ExecutionControl.Never)

            assertEquals(2.0, objective.evaluate(assertNotNull(first.sample)))
            assertEquals(1.0, objective.evaluate(assertNotNull(second.sample)))
            assertNull(third.sample)
        }
        assertEquals(listOf(shared.key), peer.drainSince(0).clauses.map { it.key })
        assertTrue(private.drainSince(0).origins.all { it == SharedClausePool.NO_ORIGIN })
    }
}
