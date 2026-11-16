package com.eignex.klause.portfolio

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.SharedClause
import com.eignex.klause.propagation.addLearnedClause
import com.eignex.klause.util.ExecutionControl
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SharedClausePoolReclamationTest {
    @Test
    fun `compaction should keep low-LBD clauses over high-LBD ones`() {
        val pool = SharedClausePool(cap = 4)
        val keep = sharedPoolTestClause(0, lbd = 1)
        val drop = sharedPoolTestClause(10, lbd = 9)
        pool.publish(listOf(keep, drop, sharedPoolTestClause(20, lbd = 8), sharedPoolTestClause(30, lbd = 7)))
        pool.publish(listOf(sharedPoolTestClause(100, lbd = 2)))
        val kept = pool.drainSince(0).clauses.map { it.key }.toSet()
        assertTrue(keep.key in kept)
        assertTrue(drop.key !in kept)
    }

    @Test
    fun `compaction should retain a global nogood ahead of better-LBD glue`() {
        val pool = SharedClausePool(cap = 4)
        val global = sharedPoolTestClause(0, lbd = 9, len = 6)
        pool.publish(listOf(global), isGlobal = true)
        pool.publish((1..3).map { sharedPoolTestClause(it * 10, lbd = 1) })
        pool.publish(listOf(sharedPoolTestClause(100, lbd = 1)))
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

        pool.publish(listOf(sharedPoolTestClause(0, lbd = 1)))
        exchange.onRestart(session)
        val imported = session.learnedClauseCount
        pool.publish((1..8).map { sharedPoolTestClause(it * 10, lbd = it) })

        exchange.onRestart(session)
        val repeat = pool.drainSince(0).clauses.count { it.key == sharedPoolTestClause(0, lbd = 1).key }
        assertEquals(
            imported + pool.drainSince(0).clauses.size - repeat,
            session.learnedClauseCount,
        )
    }

    @Test
    fun `an evicted key should be able to re-enter the pool`() {
        val pool = SharedClausePool(cap = 4)
        val evictee = sharedPoolTestClause(0, lbd = 9)
        pool.publish(listOf(evictee))
        pool.publish((1..3).map { sharedPoolTestClause(it * 10, lbd = 1) })
        pool.publish(listOf(sharedPoolTestClause(100, lbd = 1)))
        var kept = pool.drainSince(0).clauses.map { it.key }.toSet()
        assertTrue(evictee.key !in kept)
        pool.publish(listOf(evictee))
        kept = pool.drainSince(0).clauses.map { it.key }.toSet()
        assertTrue(evictee.key in kept)
    }

    @Test
    fun `a drained clause should name the arm that first published it`() {
        val pool = SharedClausePool()
        val shared = sharedPoolTestClause(0, lbd = 1)
        pool.publish(listOf(shared), origin = 2)
        pool.publish(listOf(shared, sharedPoolTestClause(10, lbd = 1)), origin = 5)

        assertContentEquals(intArrayOf(2, 5), pool.drainSince(0).origins)
    }

    @Test
    fun `compaction should keep each surviving clause with its origin`() {
        val pool = SharedClausePool(cap = 4)
        pool.publish(listOf(sharedPoolTestClause(0, lbd = 9)), origin = 0)
        pool.publish(listOf(sharedPoolTestClause(10, lbd = 1)), origin = 1)
        pool.publish(listOf(sharedPoolTestClause(20, lbd = 8)), origin = 2)
        pool.publish(listOf(sharedPoolTestClause(30, lbd = 2)), origin = 3)
        pool.publish(listOf(sharedPoolTestClause(40, lbd = 9)), origin = 4)

        val drained = pool.drainSince(0)

        val lbdByOrigin = drained.clauses.indices.associate { drained.origins[it] to drained.clauses[it].lbd }
        assertEquals(mapOf(1 to 1, 3 to 2, 4 to 9), lbdByOrigin)
    }

    @Test
    fun `reclamation releases payload owners and private array capacity`() {
        val pool = SharedClausePool(cap = 16)
        pool.publish((0 until 16).map { sharedPoolTestClause(it * 10, it) })
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
            pool.publish((0 until 4).map { sharedPoolTestClause(it * 10, it) })
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
        val clauses = (0 until 4).map { sharedPoolTestClause(it * 10, it) }
        val reference = SharedClausePool(cap = 4)
        clauses.forEachIndexed { origin, clause -> reference.publish(listOf(clause), origin = origin) }
        val completeWork = reference.reclaim().work
        for (cancelAt in listOf(0L, completeWork / 3L, completeWork * 2L / 3L, completeWork - 1L)) {
            val pool = SharedClausePool(cap = 4)
            clauses.forEachIndexed { origin, clause -> pool.publish(listOf(clause), origin = origin) }
            val before = pool.drainSince(0)
            var work = 0L

            val result = pool.reclaim(cancelled = { work >= cancelAt }, charge = { work++ })

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
        val first = sharedPoolTestClause(0, 9)
        pool.publish(listOf(first))
        val drained = pool.drainSince(0)

        pool.publish((1 until 12).map { sharedPoolTestClause(it * 10, 1) })

        assertTrue(pool.memory().getValue("poolCompactions") > 0L)
        assertTrue(pool.memory().getValue("poolOwnedBytes") <= 500L)
        assertTrue(pool.drainSince(drained.cursor).clauses.isNotEmpty())
        assertEquals(first, drained.clauses.single())
        assertContentEquals(intArrayOf(0, 2), first.boolLits)
    }

    @Test
    fun `an undersized byte limit declines publication without retaining capacity`() {
        val pool = SharedClausePool(softLimitBytes = 1L)

        val accepted = pool.publish(listOf(sharedPoolTestClause(0, 1)))

        assertEquals(0, accepted)
        assertEquals(0L, pool.memory().getValue("poolOwnedBytes"))
        assertEquals(0L, pool.memory().getValue("poolProtectedBytes"))
        assertEquals(1L, pool.memory().getValue("poolDeclined"))
        assertTrue(pool.drainSince(0).clauses.isEmpty())
    }

    @Test
    fun `byte reclamation preserves imports across suspension and releases obsolete sessions`() {
        for (native in listOf(false, true)) {
            val pool = SharedClausePool(cap = 100, softLimitBytes = 500L, measureMemory = true, booleanOnly = true)
            val meter = SharingMeter()
            val exchange = PoolClauseExchange(pool, origin = 1, meter = meter)
            val problem = Problem(128, 0, emptyArray(), listOf(Clause(intArrayOf(0, 1))))
            val session = PropagationSession(problem, nativeSat = native)
            assertEquals(native, session.usesNativeSat)
            exchange.onSearchStart(session)
            repeat(12) {
                pool.publish(listOf(sharedPoolTestClause(it * 4, 1)), origin = 2)
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
        val first = sharedPoolTestClause(0, 1)
        pool.publish(listOf(first))
        val before = pool.memory().getValue("poolOwnedBytes")
        var charged = 0

        val accepted = pool.publish(listOf(sharedPoolTestClause(10, 1)), cancelled = { charged >= 10 }, charge = { charged++ })

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
            val problem = Problem(128, 0, emptyArray(), listOf(Clause(intArrayOf(0, 1))))
            val session = PropagationSession(problem, nativeSat = native)
            assertEquals(native, session.usesNativeSat)
            exchange.onSearchStart(session)
            repeat(24) {
                pool.publish(listOf(sharedPoolTestClause(it * 4, 1)), origin = 2)
                exchange.onRestart(session)
            }
            val count = session.learnedClauseCount
            val stats = meter.snapshot().clauses
            assertEquals(8, count)
            assertEquals(144L, stats.getValue("seenCapacityBytes"))
            assertTrue(stats.getValue("seenDeclined") > 0L)
            assertTrue(stats.getValue("poolCompactions") > 1L)
            pool.publish(listOf(sharedPoolTestClause(0, 1)), origin = 2)
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

            exchange.publishGlobal(sharedPoolTestClause(0, 1))
            exchange.onSearchEnd(PropagationSession(Problem(2, 0, emptyArray(), emptyList())))

            assertEquals(1L, meter.snapshot().clauses.getValue("seenDeclined"))
            assertEquals(0L, meter.snapshot().clauses.getValue("seenCapacityBytes"))
            assertTrue(pool.drainSince(0).clauses.isEmpty())
        }
    }

    @Test
    fun `final reclamation debit cancellation preserves the published generation`() {
        val clauses = (0 until 4).map { sharedPoolTestClause(it * 10, it) }
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
            pool.publish(listOf(sharedPoolTestClause(0, 1)), origin = 2, maxWork = 10_000L, maxScratchEntries = 1000)
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
        pool.publish(listOf(sharedPoolTestClause(0, 1)), origin = 2)
        var cancelled = false
        val session = PropagationSession(Problem(2, 0, emptyArray(), emptyList()),
            cancellation = ExecutionControl { cancelled })
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
        val problem = Problem(128, 0, emptyArray(), listOf(Clause(intArrayOf(0, 1))))
        val session = PropagationSession(problem, nativeSat = true)
        assertTrue(session.usesNativeSat)
        session.addLearnedClause(Clause(sharedPoolTestClause(0, 1, len = 128).boolLits), 1)
        val before = session.work

        exchange.onSearchEnd(session)

        assertTrue(session.work - before <= allowance)
        assertTrue(pool.drainSince(0).clauses.isEmpty())
    }
}
