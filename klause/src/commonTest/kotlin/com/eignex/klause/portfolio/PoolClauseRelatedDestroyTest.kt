package com.eignex.klause.portfolio

import com.eignex.klause.RuntimeInitializedTest
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.BoolFoldDefinition
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.meta.alns.DestroyOperator
import com.eignex.klause.meta.alns.FreedVars
import com.eignex.klause.meta.alns.RepairScope
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PoolClauseRelatedDestroyTest : RuntimeInitializedTest() {
    @Test
    fun `peer support should join a disconnected adjacency neighborhood without enlarging it`() {
        val problem = Problem(0, 8, Array(8) { IntDomain(0, 1) }, emptyArray()).bake()
        val incumbent = Sample(BooleanArray(0), LongArray(8) { 1L })
        val objective = LinearObjective()
        val original = DestroyOperator.AdjacencyRelated.destroy(Random(17), problem, incumbent, objective, 0.5)
        val support = (0 until 8).filter { it !in original.ints }.take(2)
        val clause = SharedClause(intArrayOf(), longArrayOf(
            support[0].toLong(), 0, 1, 0, support[1].toLong(), 0, 1, 0,
        ), 2)
        val pool = SharedClausePool()
        pool.publish(listOf(clause), origin = 0)
        val meter = SharingMeter()

        val freed = PoolClauseRelatedDestroy(pool, meter).destroy(Random(17), problem, incumbent, objective, 0.5)

        assertEquals(original.ints.size, freed.ints.size)
        assertEquals(freed.ints.size, freed.ints.toSet().size)
        assertTrue(support.all { it in freed.ints })
        assertEquals(1L, meter.snapshot().clauses.getValue("neighborhoodOrigin0Choices"))
        assertEquals(1L, meter.snapshot().clauses.getValue("neighborhoodChanges"))
        assertEquals(listOf(clause.key), pool.drainSince(0).clauses.map { it.key })
    }

    @Test
    fun `mixed peer literals should release each canonical coordinate once`() {
        val problem = Problem(4, 4, Array(4) { IntDomain(0, 1) }, emptyArray()).bake()
        val pool = SharedClausePool()
        pool.publish(listOf(SharedClause(intArrayOf(6, 6), longArrayOf(3, 0, 1, 0, 3, 1, 0, 0), 2)), origin = 2)
        val original = FreedVars(intArrayOf(0, 1), intArrayOf(0, 1))
        val fallback = DestroyOperator { _, _, _, _, _ -> original }
        val meter = SharingMeter()

        val freed = PoolClauseRelatedDestroy(pool, meter, fallback).destroy(Random(17), problem,
            Sample(BooleanArray(4) { true }, LongArray(4) { 1L }), LinearObjective(), 0.5)

        assertEquals(setOf(0, 1, 3), freed.bools.toSet())
        assertEquals(setOf(3), freed.ints.toSet())
        assertEquals(4, freed.bools.size + freed.ints.size)
        assertEquals(1L, meter.snapshot().clauses.getValue("neighborhoodOrigin2Changes"))
        assertNull(meter.snapshot().clauses["uses"])
    }

    @Test
    fun `already included peer support should count a choice without a neighborhood change`() {
        val problem = Problem(0, 4, Array(4) { IntDomain(0, 1) }, emptyArray()).bake()
        val pool = SharedClausePool()
        pool.publish(listOf(SharedClause(intArrayOf(), longArrayOf(0, 0, 1, 0, 1, 0, 1, 0), 2)), origin = 0)
        val original = FreedVars(intArrayOf(), intArrayOf(0, 1))
        val meter = SharingMeter()

        val freed = PoolClauseRelatedDestroy(pool, meter, DestroyOperator { _, _, _, _, _ -> original })
            .destroy(Random(17), problem, Sample(BooleanArray(0), LongArray(4)), LinearObjective(), 0.5)

        assertEquals(original, freed)
        assertEquals(1L, meter.snapshot().clauses.getValue("neighborhoodOrigin0Choices"))
        assertNull(meter.snapshot().clauses["neighborhoodChanges"])
        assertNull(meter.snapshot().clauses["uses"])
    }

    @Test
    fun `fixed or externally frozen peer support should preserve the original neighborhood`() {
        val domains = Array(4) { IntDomain(0, 1) }
        val plain = Problem(4, 4, domains, emptyArray()).bake()
        val fixedInt = Problem(4, 4, Array(4) { if (it == 3) IntDomain(1, 1) else IntDomain(0, 1) },
            emptyArray()).bake()
        val fixedBool = Problem(4, 4, domains, arrayOf(Clause(intArrayOf(6)))).bake()
        val original = FreedVars(intArrayOf(0, 1), intArrayOf(0, 1))
        for ((problem, assumptions) in listOf(plain to Assumptions(bools = mapOf(3 to true)),
            plain to Assumptions(ints = mapOf(3 to 1L)), fixedInt to Assumptions.None, fixedBool to Assumptions.None)) {
            val pool = SharedClausePool()
            pool.publish(listOf(SharedClause(intArrayOf(6), longArrayOf(3, 0, 1, 0), 2)))
            val freed = PoolClauseRelatedDestroy(pool, fallback = DestroyOperator { _, _, _, _, _ -> original })
                .destroy(Random(17), problem, Sample(BooleanArray(4) { true }, LongArray(4) { 1L }),
                    LinearObjective(), 0.5, assumptions, ExecutionControl.Never)

            assertEquals(original, freed)
        }
    }

    @Test
    fun `ineligible peer payloads should not replace the original neighborhood`() {
        val problem = Problem(20, 4, Array(4) { IntDomain(0, 1) }, emptyArray()).bake()
        val original = FreedVars(intArrayOf(0, 1), intArrayOf())
        val clauses = listOf(
            SharedClause(intArrayOf(40, 2), longArrayOf(), 2),
            SharedClause(intArrayOf(6), longArrayOf(4, 0, 1, 0), 2),
            SharedClause(intArrayOf(6), longArrayOf(3, 3, 1, 0), 2),
            SharedClause(intArrayOf(6), longArrayOf(3, 0, 1, 2), 2),
            SharedClause(IntArray(17) { Lit.make(it, true) }, longArrayOf(), 2),
            SharedClause(intArrayOf(4, 6, 8), longArrayOf(), 2),
            SharedClause(intArrayOf(4, 4), longArrayOf(), 2),
        )
        for (clause in clauses) {
            val pool = SharedClausePool()
            pool.publish(listOf(clause))
            val rng = Random(17)
            val meter = SharingMeter()
            val freed = PoolClauseRelatedDestroy(pool, meter, DestroyOperator { _, _, _, _, _ -> original })
                .destroy(rng, problem, Sample(BooleanArray(20), LongArray(4)), LinearObjective(), 0.5)

            assertEquals(original, freed)
            assertEquals(Random(17).nextLong(), rng.nextLong())
            assertNull(meter.snapshot().clauses["neighborhoodChoices"])
        }
    }

    @Test
    fun `an empty pool should preserve the adjacency result and random stream`() {
        val problem = Problem(0, 8, Array(8) { IntDomain(0, 1) }, emptyArray()).bake()
        val sample = Sample(BooleanArray(0), LongArray(8))
        val expectedRng = Random(17)
        val actualRng = Random(17)
        val expected = DestroyOperator.AdjacencyRelated.destroy(expectedRng, problem, sample, LinearObjective(), 0.5)

        val freed = PoolClauseRelatedDestroy(SharedClausePool()).destroy(actualRng, problem,
            sample, LinearObjective(), 0.5)

        assertEquals(expected, freed)
        assertEquals(expectedRng.nextLong(), actualRng.nextLong())
    }

    @Test
    fun `snapshot limits should decline before changing the neighborhood`() {
        val problem = Problem(0, 1024, Array(1024) { IntDomain(0, 1) }, emptyArray()).bake()
        val original = FreedVars(intArrayOf(), intArrayOf(0, 1))
        for ((scratch, count) in listOf(0 to 1, 4096 to 513)) {
            val pool = SharedClausePool(reclamationScratchEntries = scratch)
            val clauses = (0 until count).map {
                SharedClause(intArrayOf(), longArrayOf(it.toLong(), 0, 1, 0, it + 1L, 0, 1, 0), 2)
            }
            assertEquals(count, pool.publish(clauses, maxWork = 1_000_000L, maxScratchEntries = 32_768))
            val meter = SharingMeter()

            val freed = PoolClauseRelatedDestroy(pool, meter, DestroyOperator { _, _, _, _, _ -> original })
                .destroy(Random(17), problem, Sample(BooleanArray(0), LongArray(1024)), LinearObjective(), 0.5)

            assertEquals(original, freed)
            assertEquals(1L, meter.snapshot().clauses.getValue("neighborhoodDeclined"))
            assertNull(meter.snapshot().clauses["neighborhoodChoices"])
        }
    }

    @Test
    fun `partial cancellation and the local work cap should discard the whole guidance`() {
        val problem = Problem(0, 1024, Array(1024) { IntDomain(0, 1) }, emptyArray()).bake()
        val original = FreedVars(intArrayOf(), IntArray(512) { it })
        val pool = SharedClausePool()
        pool.publish((0 until 16).map {
            SharedClause(intArrayOf(), longArrayOf(it + 512L, 0, 1, 0, it + 513L, 0, 1, 0), 2)
        })
        for (limit in listOf(0L, 10L, 100L, 1000L, Long.MAX_VALUE)) {
            var charged = 0L
            val stop = ExecutionControl({ charged >= limit }, WorkMeter { charged += it })
            val meter = SharingMeter()

            val freed = PoolClauseRelatedDestroy(pool, meter, DestroyOperator { _, _, _, _, _ -> original })
                .destroy(Random(17), problem, Sample(BooleanArray(0), LongArray(1024)), LinearObjective(), 0.5,
                    Assumptions.None, stop)

            assertEquals(original, freed)
            assertEquals(charged, meter.snapshot().clauses.getValue("neighborhoodWork"))
            assertTrue(charged <= 4096L)
            assertEquals(1L, meter.snapshot().clauses.getValue("neighborhoodDeclined"))
            assertNull(meter.snapshot().clauses["neighborhoodChoices"])
        }
    }

    @Test
    fun `guided requests should retain definition closure and caller pins`() {
        val problem = Problem(4, 0, emptyArray(), emptyArray()).bake()
        val sweep = assertNotNull(DefinitionalSweep.infer(problem, boolFolds = listOf(
            BoolFoldDefinition(1, intArrayOf(Lit.make(0, true)), true),
        )))
        val caller = Assumptions(bools = mapOf(2 to true))
        val original = FreedVars(intArrayOf(0, 2), intArrayOf())
        val pool = SharedClausePool()
        pool.publish(listOf(SharedClause(intArrayOf(2, 6), longArrayOf(), 2)))
        val incumbent = Sample(BooleanArray(4) { true }, LongArray(0))

        val requested = PoolClauseRelatedDestroy(pool, fallback = DestroyOperator { _, _, _, _, _ -> original })
            .destroy(Random(17), problem, incumbent, LinearObjective(), 0.5, caller, ExecutionControl.Never)
        val effective = assertNotNull(RepairScope.effective(requested, caller, sweep, ExecutionControl.Never))
        val pins = RepairScope.pins(problem, incumbent, effective).mergedWith(caller)

        assertEquals(2, requested.bools.size)
        assertEquals(setOf(0, 1, 3), effective.bools.toSet())
        assertEquals(true, pins.boolValueOrNull(2))
    }
}
