package com.eignex.klause.portfolio

import com.eignex.klause.RuntimeInitializedTest
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.meta.alns.DestroyOperator
import com.eignex.klause.meta.alns.FreedVars
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.SharedClause
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.ExecutionControl
import com.eignex.klause.util.WorkMeter
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PoolClauseNeighborhoodObserverTest : RuntimeInitializedTest() {
    @Test
    fun `completed inventory should retain action aliases without changing selection or random draws`() {
        val problem = Problem(0, 8, Array(8) { IntDomain(0, 1) }, emptyArray()).bake()
        val pool = SharedClausePool()
        pool.publish(listOf(SharedClause(intArrayOf(), longArrayOf(6, 0, 1, 0, 7, 0, 1, 0), 2)), origin = 0)
        pool.publish(listOf(SharedClause(intArrayOf(), longArrayOf(7, 0, 0, 0, 6, 0, 0, 0), 2)), origin = 3)
        pool.publish(listOf(SharedClause(intArrayOf(), longArrayOf(4, 0, 1, 0, 5, 0, 1, 0), 2)), origin = 0)
        val original = FreedVars(intArrayOf(), intArrayOf(0, 1, 2, 3))
        val fallback = DestroyOperator { _, _, _, _, _ -> original }
        val sample = Sample(BooleanArray(0), LongArray(8))
        val observations = ArrayList<PoolClauseNeighborhoodObservation>()
        val observer = PoolClauseNeighborhoodObserver { worker, seed, value ->
            assertEquals("alns/test", worker)
            assertEquals(17L, seed)
            observations += value
        }
        val plainRng = Random(17)
        val observedRng = Random(17)
        var plainWork = 0L
        var observedWork = 0L

        val plain = PoolClauseRelatedDestroy(pool, fallback = fallback).destroy(plainRng, problem,
            sample, LinearObjective(), 0.5, Assumptions.None,
            ExecutionControl({ false }, WorkMeter { plainWork += it }))
        val observed = PoolClauseRelatedDestroy(pool, fallback = fallback, observer = observer,
            worker = "alns/test", seed = 17L).destroy(observedRng, problem, sample, LinearObjective(), 0.5,
            Assumptions.None, ExecutionControl({ false }, WorkMeter { observedWork += it }))

        assertEquals(plain.ints.toList(), observed.ints.toList())
        assertEquals(plainRng.nextLong(), observedRng.nextLong())
        assertEquals(plainWork + observer.work, observedWork)
        val inventory = observations.single()
        assertTrue(inventory.complete && inventory.samplerCompleted)
        val entries = assertNotNull(inventory.entries).map { assertNotNull(it) }
        assertEquals(listOf(0, 3, 0), entries.map { it[0] })
        assertEquals(listOf(setOf(6, 7), setOf(6, 7), setOf(4, 5)), entries.map { it.drop(2).toSet() })
        assertTrue(entries[inventory.selectedIndex].drop(2).all { it in observed.ints })
        assertEquals(3L, inventory.cursor)
    }

    @Test
    fun `ineligible entries should retain the support pass disposition`() {
        val problem = Problem(0, 4, Array(4) { IntDomain(0, 1) }, emptyArray()).bake()
        val pool = SharedClausePool()
        pool.publish(listOf(
            SharedClause(intArrayOf(), longArrayOf(0, 0, 1, 0, 1, 0, 1, 0), 2),
            SharedClause(intArrayOf(), longArrayOf(2, 0, 1, 0, 3, 0, 1, 0), 2),
        ))
        val observations = ArrayList<PoolClauseNeighborhoodObservation>()
        val observer = PoolClauseNeighborhoodObserver { _, _, value -> observations += value }
        val original = FreedVars(intArrayOf(), intArrayOf(0, 1))

        PoolClauseRelatedDestroy(pool, fallback = DestroyOperator { _, _, _, _, _ -> original }, observer = observer)
            .destroy(Random(17), problem, Sample(BooleanArray(0), LongArray(4)), LinearObjective(), 0.5,
                Assumptions(ints = mapOf(3 to 1L)), ExecutionControl.Never)

        val inventory = observations.single()
        assertTrue(inventory.complete)
        assertEquals(listOf(2, -5), assertNotNull(inventory.entries).map { assertNotNull(it)[1] })
    }

    @Test
    fun `four completed eligible inventories should close the shared capture window`() {
        val observations = ArrayList<PoolClauseNeighborhoodObservation>()
        val observer = PoolClauseNeighborhoodObserver { _, _, value -> observations += value }

        repeat(12) {
            observer.begin("alns", 1, 0, 4, 2, ExecutionControl.Never)?.apply {
                snapshot(1L, 1)
                record(0, 0, 2, intArrayOf(2, 3))
                selectedIndex = 0
                supportPassComplete = true
                samplerCompleted = true
                finish()
            }
        }

        assertEquals(4, observations.size)
        assertEquals(4, observer.completedEligible)
        assertEquals(8L, observer.omitted)
        assertEquals(12L, observer.calls)
    }

    @Test
    fun `metadata exhaustion should withhold complete inventory denominators`() {
        val observations = ArrayList<PoolClauseNeighborhoodObservation>()
        val observer = PoolClauseNeighborhoodObserver { _, _, value -> observations += value }
        var charged = 0L
        val stop = ExecutionControl({ false }, WorkMeter { charged += it })

        assertNotNull(observer.begin("alns", 1, 0, 32, 16, stop)).apply {
            snapshot(512L, 512)
            repeat(512) { record(it, 0, 16, IntArray(16) { it }) }
            supportPassComplete = true
            samplerCompleted = true
            selectedIndex = 0
            finish()
        }

        val inventory = observations.single()
        assertTrue(inventory.truncated)
        assertFalse(inventory.complete)
        assertEquals(0, observer.completedEligible)
        assertEquals(charged, observer.work)
        assertTrue(charged <= 4096L)
        assertTrue(inventory.modeledBytes <= 65_536L)
        assertNull(observer.begin("alns", 1, 0, 32, 16, stop))
        assertTrue(observer.work <= 4096L)
        assertTrue(observer.modeledBytes <= 65_536L)
    }

    @Test
    fun `interrupted snapshots should stop after the bounded attempt window`() {
        val observations = ArrayList<PoolClauseNeighborhoodObservation>()
        val observer = PoolClauseNeighborhoodObserver { _, _, value -> observations += value }
        var cancelled = false
        val stop = ExecutionControl { cancelled }

        repeat(20) {
            cancelled = false
            observer.begin("alns", 1, 0, 4, 2, stop)?.apply {
                snapshot(1L, 1)
                cancelled = true
                record(0, 0, 2, intArrayOf(2, 3))
                finish()
            }
        }

        assertEquals(16, observations.size)
        assertTrue(observations.all { it.truncated && !it.complete && !it.samplerCompleted })
        assertEquals(4L, observer.omitted)
        assertEquals(0, observer.completedEligible)
    }
}
