package com.eignex.klause.portfolio

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.SharedClause
import com.eignex.klause.propagation.addLearnedClause
import com.eignex.klause.solver.result.SharingChannel
import com.eignex.klause.util.Cancellation
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

    @Test
    fun `a duplicate at capacity should preserve the generation`() {
        val pool = SharedClausePool(cap = 2)
        val clause = boolClause(0, 1)
        pool.publish(listOf(clause, boolClause(2, 1)))
        val cursor = pool.drainSince(0).cursor

        pool.publish(listOf(clause))

        assertEquals(cursor, pool.drainSince(cursor).cursor)
        assertTrue(pool.drainSince(cursor).clauses.isEmpty())
    }

    @Test
    fun `slow consumers should suppress retained and evicted keys across generations`() {
        for (native in listOf(false, true)) {
            val problem = Problem(128, 0, emptyArray(), emptyList())
            val pool = SharedClausePool(cap = 4, measureMemory = true)
            val meter = SharingMeter()
            val exchange = PoolClauseExchange(pool, origin = 1, meter = meter)
            val session = PropagationSession(problem, nativeSat = native)
            val retained = boolClause(0, 1)
            val evicted = boolClause(2, 9)
            pool.publish(listOf(retained, evicted), origin = 2)
            exchange.onSearchStart(session)
            repeat(16) { round ->
                pool.publish(listOf(boolClause(4 + round * 2, 2), boolClause(40 + round * 2, 2)), origin = 2)
            }
            pool.publish(listOf(evicted), origin = 2)
            exchange.onRestart(session)
            val count = session.learnedClauseCount

            exchange.onRestart(session)

            assertEquals(count, session.learnedClauseCount)
            assertEquals(1, (0 until count).count {
                session.learnedClauseLiterals(it).contentEquals(evicted.boolLits)
            })
            assertTrue(meter.snapshot().channels.getValue(SharingChannel.Clauses).duplicates > 0)
            assertTrue(meter.snapshot().clauses.getValue("poolCompactions") > 1)
        }
    }

    @Test
    fun `suspension should retain the filter while a fresh session releases its capacity`() {
        val problem = Problem(128, 0, emptyArray(), emptyList())
        val pool = SharedClausePool(cap = 4, measureMemory = true, memorySampleWork = 262_144L)
        val meter = SharingMeter()
        val exchange = PoolClauseExchange(pool, origin = 1, meter = meter)
        val session = PropagationSession(problem)
        exchange.onSearchStart(session)
        repeat(24) { i ->
            pool.publish(listOf(boolClause(i * 2, 1)), origin = 2)
            exchange.onRestart(session)
        }
        exchange.onSearchEnd(session)
        val suspended = meter.snapshot().clauses
        assertEquals(1L, suspended.getValue("memorySampleComplete"))
        assertEquals(24L, suspended.getValue("seenKeys"))
        assertEquals(24L, suspended.getValue("learnedObjects"))
        assertTrue(suspended.getValue("seenKeys") > suspended.getValue("poolClauses"))
        val count = session.learnedClauseCount
        session.reseedFrom(com.eignex.klause.propagation.Assumptions.None.withBool(100, true))
        exchange.onSearchStart(session)
        assertEquals(count, session.learnedClauseCount)
        assertEquals(suspended.getValue("seenCapacityBytes"), meter.snapshot().clauses.getValue("seenCapacityBytes"))

        val fresh = PropagationSession(problem)
        exchange.onSearchStart(fresh)

        assertEquals(pool.drainSince(0).clauses.size, fresh.learnedClauseCount)
        assertTrue(meter.snapshot().clauses.getValue("seenCapacityBytes") < suspended.getValue("seenCapacityBytes"))
        assertEquals(suspended.getValue("seenCapacityBytes"),
            meter.snapshot().clauses.getValue("peakSeenCapacityBytes"))
    }

    @Test
    fun `a preparation pause should preserve exports before the first search start`() {
        val problem = Problem(4, 0, emptyArray(), emptyList())
        val pool = SharedClausePool()
        val exchange = PoolClauseExchange(pool)
        val session = PropagationSession(problem)
        session.addLearnedClause(Clause(intArrayOf(0, 2)), 1)
        exchange.onSearchEnd(session)

        exchange.onSearchStart(session)

        assertEquals(1, session.learnedClauseCount)
        assertEquals(1, pool.drainSince(0).clauses.size)
    }

    @Test
    fun `retiring a search should release its duplicate filter`() {
        val problem = Problem(4, 0, emptyArray(), emptyList())
        val pool = SharedClausePool(measureMemory = true)
        val meter = SharingMeter()
        val exchange = PoolClauseExchange(pool, origin = 1, meter = meter)
        val session = PropagationSession(problem)
        pool.publish(listOf(boolClause(0, 1)), origin = 2)
        exchange.onSearchStart(session)

        exchange.onSearchClose(session)

        assertEquals(0L, meter.snapshot().clauses.getValue("seenCapacityBytes"))
        assertTrue(meter.snapshot().clauses.getValue("peakSeenCapacityBytes") > 0)
    }

    @Test
    fun `shared consumption should retain accounted reasons through native compaction`() {
        for (native in listOf(false, true)) {
            val problem = Problem(4, 0, emptyArray(), emptyList())
            val pool = SharedClausePool(measureMemory = true)
            val meter = SharingMeter()
            val exchange = PoolClauseExchange(pool, origin = 1, meter = meter)
            val session = PropagationSession(problem, nativeSat = native)
            pool.publish(listOf(boolClause(0, 1)), origin = 2)
            exchange.onSearchStart(session)
            session.pinBool(0, false)
            assertEquals(true, session.boolValue(1))
            session.forgetLearnedClauses { _, _ -> false }

            exchange.onSearchEnd(session)

            val stats = meter.snapshot().clauses
            assertEquals(1, session.learnedClauseCount)
            assertEquals(1L, stats.getValue("uses"))
            assertEquals(1L, stats.getValue("retainedConsumedPeerImports"))
            assertTrue(stats.getValue("reasonPayloadBytes") >= 4L)
            assertTrue(stats.getValue("importWork") >= stats.getValue("translatedLiterals"))
            assertEquals(1L, stats.getValue("reasonProtectedObjects"))
        }
    }

    @Test
    fun `cancelled close releases the filter without observing or exporting the store`() {
        for (native in listOf(false, true)) {
            var cancelled = false
            val problem = Problem(8, 0, emptyArray(), emptyList())
            val pool = SharedClausePool(measureMemory = true)
            val meter = SharingMeter()
            val exchange = PoolClauseExchange(pool, origin = 1, meter = meter)
            val session = PropagationSession(problem, nativeSat = native, cancellation = Cancellation { cancelled })
            pool.publish(listOf(boolClause(0, 1)), origin = 2)
            exchange.onSearchStart(session)
            session.addLearnedClause(Clause(intArrayOf(4, 6)), 1)
            val before = session.work
            cancelled = true

            exchange.onSearchClose(session)

            val stats = meter.snapshot().clauses
            assertEquals(before, session.work)
            assertEquals(0L, stats.getValue("seenCapacityBytes"))
            assertEquals(1L, stats.getValue("memorySampleStale"))
            assertEquals(0L, stats.getValue("memorySampleComplete"))
            assertEquals(0L, stats.getValue("memorySampleWork"))
            assertEquals(1, pool.drainSince(0).clauses.size)
            val fresh = PropagationSession(problem, nativeSat = native)
            exchange.onSearchStart(fresh)
            assertEquals(1, fresh.learnedClauseCount)
            assertEquals(1L, meter.snapshot().clauses.getValue("memorySampleComplete"))
        }
    }

    @Test
    fun `partial samples remove fields from the previous complete sample`() {
        val problem = Problem(4, 0, emptyArray(), emptyList())
        var cancelled = false
        val pool = SharedClausePool(measureMemory = true)
        val meter = SharingMeter()
        val exchange = PoolClauseExchange(pool, meter = meter)
        val session = PropagationSession(problem, cancellation = Cancellation { cancelled })
        exchange.onSearchStart(session)
        assertEquals(1L, meter.snapshot().clauses.getValue("memorySampleComplete"))
        cancelled = true

        exchange.onSearchEnd(session)

        val stats = meter.snapshot().clauses
        assertEquals(1L, stats.getValue("memorySamplePartial"))
        assertEquals(0L, stats.getValue("memorySampleComplete"))
        assertTrue("learnedPayloadBytes" !in stats)
        assertTrue("peakLearnedPayloadBytes" in stats)
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

    @Test
    fun `reclamation releases payload owners and private array capacity`() {
        val pool = SharedClausePool(cap = 16)
        pool.publish((0 until 16).map { boolClause(it * 10, it) })
        val before = pool.memory()

        val result = pool.reclaim()

        val after = pool.memory()
        assertEquals(SharedClausePool.ReclamationStatus.RECLAIMED, result.status)
        assertEquals(8L, after.getValue("poolClauses"))
        assertEquals(before.getValue("poolOwnedBytes"), result.beforeBytes)
        assertEquals(after.getValue("poolOwnedBytes"), result.afterBytes)
        assertTrue(after.getValue("poolPayloadBytes") < before.getValue("poolPayloadBytes"))
        assertTrue(after.getValue("poolPrivateIndexBytes") < before.getValue("poolPrivateIndexBytes"))
    }

    @Test
    fun `shared payload arrays are owned once independently of clause wrappers`() {
        val pool = SharedClausePool()
        val literals = intArrayOf(0, 2)
        val firstAtoms = longArrayOf(0, 0, 3, 0)
        val secondAtoms = longArrayOf(1, 0, 3, 0)
        pool.publish(listOf(SharedClause(literals, firstAtoms, 1), SharedClause(literals, secondAtoms, 1)))

        val memory = pool.memory()

        assertEquals(72L, memory.getValue("poolPayloadBytes"))
        assertEquals(memory.getValue("poolPayloadBytes") + memory.getValue("poolPrivateIndexBytes"),
            memory.getValue("poolOwnedBytes"))
    }

    @Test
    fun `tiny reclamation allowances preserve the current generation and clauses`() {
        for ((work, scratch) in listOf(0L to 1_000, 1_000L to 0, 12L to 1_000)) {
            val pool = SharedClausePool(cap = 4)
            pool.publish((0 until 4).map { boolClause(it * 10, it) })
            val before = pool.drainSince(0)
            val bytes = pool.memory().getValue("poolOwnedBytes")
            var charged = 0L

            val result = pool.reclaim(work, scratch, charge = { charged++ })

            assertEquals(SharedClausePool.ReclamationStatus.DEFERRED, result.status)
            assertEquals(before.cursor, pool.drainSince(0).cursor)
            assertEquals(before.clauses, pool.drainSince(0).clauses)
            assertEquals(bytes, result.afterBytes)
            assertEquals(charged, result.work)
            assertTrue(result.work <= work)
            assertTrue(result.scratchEntries <= scratch)
        }
    }

    @Test
    fun `cancelled reclamation leaves origins and stale consumer cursors intact`() {
        for (cancelAt in listOf(0, 10, 35, 65)) {
            val pool = SharedClausePool(cap = 4)
            repeat(4) { pool.publish(listOf(boolClause(it * 10, it)), origin = it) }
            val before = pool.drainSince(0)
            var polls = 0

            val result = pool.reclaim(cancelled = { polls++ >= cancelAt })

            assertEquals(SharedClausePool.ReclamationStatus.CANCELLED, result.status)
            val after = pool.drainSince(0)
            assertEquals(before.cursor, after.cursor)
            assertEquals(before.clauses, after.clauses)
            assertContentEquals(before.origins, after.origins)
            assertEquals(result.beforeBytes, result.afterBytes)
        }
    }

    @Test
    fun `soft byte pressure reclaims before the entry cap and retains slow consumer payloads`() {
        val pool = SharedClausePool(cap = 100, softLimitBytes = 500L)
        val first = boolClause(0, 9)
        pool.publish(listOf(first))
        val drained = pool.drainSince(0)

        pool.publish((1 until 12).map { boolClause(it * 10, 1) })

        assertTrue(pool.memory().getValue("poolCompactions") > 0L)
        assertTrue(pool.memory().getValue("poolOwnedBytes") <= 500L)
        assertTrue(pool.drainSince(drained.cursor).clauses.isNotEmpty())
        assertEquals(first, drained.clauses.single())
        assertContentEquals(intArrayOf(0, 2), first.boolLits)
    }

    @Test
    fun `an undersized byte limit declines publication without retaining capacity`() {
        val pool = SharedClausePool(softLimitBytes = 1L)

        val accepted = pool.publish(listOf(boolClause(0, 1)))

        assertEquals(0, accepted)
        assertEquals(0L, pool.memory().getValue("poolOwnedBytes"))
        assertEquals(0L, pool.memory().getValue("poolProtectedBytes"))
        assertEquals(1L, pool.memory().getValue("poolDeclined"))
        assertTrue(pool.drainSince(0).clauses.isEmpty())
    }

    @Test
    fun `byte reclamation preserves imports across suspension and releases obsolete sessions`() {
        for (native in listOf(false, true)) {
            val pool = SharedClausePool(cap = 100, softLimitBytes = 500L, measureMemory = true)
            val meter = SharingMeter()
            val exchange = PoolClauseExchange(pool, origin = 1, meter = meter)
            val problem = Problem(128, 0, emptyArray(), emptyList())
            val session = PropagationSession(problem, nativeSat = native)
            exchange.onSearchStart(session)
            repeat(12) {
                pool.publish(listOf(boolClause(it * 4, 1)), origin = 2)
                exchange.onRestart(session)
            }
            exchange.onSearchEnd(session)
            val count = session.learnedClauseCount

            exchange.onSearchStart(session)

            assertEquals(count, session.learnedClauseCount)
            assertTrue(pool.memory().getValue("poolOwnedBytes") <= 500L)
            assertTrue(pool.memory().getValue("poolCompactions") > 0L)
            exchange.onSearchClose(session)
            assertEquals(0L, meter.snapshot().clauses.getValue("seenCapacityBytes"))
            val fresh = PropagationSession(problem, nativeSat = native)
            exchange.onSearchStart(fresh)
            assertEquals(pool.drainSince(0).clauses.size, fresh.learnedClauseCount)
        }
    }

    @Test
    fun `a cancelled publication preserves the previous batch and releases preparation`() {
        val pool = SharedClausePool(cap = 16)
        val first = boolClause(0, 1)
        pool.publish(listOf(first))
        val before = pool.memory().getValue("poolOwnedBytes")
        var charged = 0

        val accepted = pool.publish(listOf(boolClause(10, 1)), cancelled = { charged >= 10 }, charge = { charged++ })

        assertEquals(0, accepted)
        assertEquals(listOf(first), pool.drainSince(0).clauses)
        assertEquals(before, pool.memory().getValue("poolOwnedBytes"))
    }

    @Test
    fun `private duplicate filters decline growth across pool generations without reimporting`() {
        for (native in listOf(false, true)) {
            val pool = SharedClausePool(cap = 100, softLimitBytes = 200L, measureMemory = true)
            val meter = SharingMeter()
            val exchange = PoolClauseExchange(pool, origin = 1, meter = meter)
            val problem = Problem(128, 0, emptyArray(), emptyList())
            val session = PropagationSession(problem, nativeSat = native)
            exchange.onSearchStart(session)
            repeat(24) {
                pool.publish(listOf(boolClause(it * 4, 1)), origin = 2)
                exchange.onRestart(session)
            }
            val count = session.learnedClauseCount
            val stats = meter.snapshot().clauses
            assertEquals(8, count)
            assertEquals(144L, stats.getValue("seenCapacityBytes"))
            assertTrue(stats.getValue("seenDeclined") > 0L)
            assertTrue(stats.getValue("poolCompactions") > 1L)
            pool.publish(listOf(boolClause(0, 1)), origin = 2)
            session.reseedFrom(com.eignex.klause.propagation.Assumptions.None.withBool(100, true))

            exchange.onSearchStart(session)

            assertEquals(count, session.learnedClauseCount)
            assertEquals(144L, meter.snapshot().clauses.getValue("seenCapacityBytes"))
            exchange.onSearchClose(session)
            assertEquals(0L, meter.snapshot().clauses.getValue("seenCapacityBytes"))
            val fresh = PropagationSession(problem, nativeSat = native)
            exchange.onSearchStart(fresh)
            assertEquals(pool.drainSince(0).clauses.size, fresh.learnedClauseCount)
        }
    }

    @Test
    fun `tiny private admission allowances decline global sharing without retaining keys`() {
        for ((work, scratch) in listOf(0L to 1000, 1000L to 0)) {
            val pool = SharedClausePool(softLimitBytes = 1000L, reclamationWork = work,
                reclamationScratchEntries = scratch, measureMemory = true)
            val meter = SharingMeter()
            val exchange = PoolClauseExchange(pool, meter = meter)

            exchange.publishGlobal(boolClause(0, 1))
            exchange.onSearchEnd(PropagationSession(Problem(2, 0, emptyArray(), emptyList())))

            assertEquals(1L, meter.snapshot().clauses.getValue("seenDeclined"))
            assertEquals(0L, meter.snapshot().clauses.getValue("seenCapacityBytes"))
            assertTrue(pool.drainSince(0).clauses.isEmpty())
        }
    }

    @Test
    fun `final reclamation debit cancellation preserves the published generation`() {
        val clauses = (0 until 4).map { boolClause(it * 10, it) }
        val control = SharedClausePool(cap = 4)
        control.publish(clauses)
        var required = 0
        assertEquals(SharedClausePool.ReclamationStatus.RECLAIMED, control.reclaim(charge = { required++ }).status)
        val pool = SharedClausePool(cap = 4)
        pool.publish(clauses)
        val before = pool.drainSince(0)
        var charged = 0

        val result = pool.reclaim(cancelled = { charged >= required }, charge = { charged++ })

        assertEquals(SharedClausePool.ReclamationStatus.CANCELLED, result.status)
        assertEquals(required, charged)
        assertEquals(before.cursor, pool.drainSince(0).cursor)
        assertEquals(before.clauses, pool.drainSince(0).clauses)
        assertEquals(result.beforeBytes, result.afterBytes)
    }

    @Test
    fun `unfunded drain preparation leaves the private filter empty`() {
        for ((work, scratch) in listOf(0L to 1000, 1000L to 0)) {
            val pool = SharedClausePool(softLimitBytes = 1000L, reclamationWork = work,
                reclamationScratchEntries = scratch, measureMemory = true)
            pool.publish(listOf(boolClause(0, 1)), origin = 2, maxWork = 10_000L, maxScratchEntries = 1000)
            val meter = SharingMeter()
            val exchange = PoolClauseExchange(pool, origin = 1, meter = meter)
            val session = PropagationSession(Problem(2, 0, emptyArray(), emptyList()))

            exchange.onSearchStart(session)
            exchange.onSearchEnd(session)

            assertEquals(0, session.learnedClauseCount)
            assertEquals(1L, meter.snapshot().clauses.getValue("drainPreparationDeclined"))
            assertEquals(0L, meter.snapshot().clauses.getValue("seenKeys"))
            assertEquals(0L, meter.snapshot().clauses.getValue("seenCapacityBytes"))
        }
    }

    @Test
    fun `cancelled drain preparation preserves the cursor for a resumed import`() {
        val pool = SharedClausePool(softLimitBytes = 1000L)
        pool.publish(listOf(boolClause(0, 1)), origin = 2)
        var cancelled = false
        val session = PropagationSession(Problem(2, 0, emptyArray(), emptyList()),
            cancellation = Cancellation { cancelled })
        val exchange = PoolClauseExchange(pool, origin = 1)
        cancelled = true
        exchange.onSearchStart(session)
        assertEquals(0, session.learnedClauseCount)

        cancelled = false
        exchange.onSearchStart(session)

        assertEquals(1, session.learnedClauseCount)
    }

    @Test
    fun `unfunded export preparation retains no private publication keys`() {
        for ((work, scratch) in listOf(1000L to 1000, 10_000L to 0)) {
            val pool = SharedClausePool(softLimitBytes = 1000L, reclamationWork = work,
                reclamationScratchEntries = scratch, measureMemory = true)
            val meter = SharingMeter()
            val exchange = PoolClauseExchange(pool, origin = 1, meter = meter)
            val session = PropagationSession(Problem(2, 0, emptyArray(), emptyList()))
            session.addLearnedClause(Clause(intArrayOf(0, 2)), 1)

            exchange.onSearchEnd(session)

            assertEquals(1L, meter.snapshot().clauses.getValue("exportPreparationDeclined"))
            assertEquals(0L, meter.snapshot().clauses.getValue("seenKeys"))
            assertEquals(0L, meter.snapshot().clauses.getValue("seenCapacityBytes"))
            assertTrue(pool.drainSince(0).clauses.isEmpty())
        }
    }

    @Test
    fun `oversized native clause rejection stays within the export allowance`() {
        val allowance = 512L
        val pool = SharedClausePool(softLimitBytes = 1000L, shareMaxLen = 2, reclamationWork = allowance)
        val exchange = PoolClauseExchange(pool)
        val session = PropagationSession(Problem(128, 0, emptyArray(), emptyList()), nativeSat = true)
        session.addLearnedClause(Clause(boolClause(0, 1, len = 128).boolLits), 1)
        val before = session.work

        exchange.onSearchEnd(session)

        assertTrue(session.work - before <= allowance)
        assertTrue(pool.drainSince(0).clauses.isEmpty())
    }
}
