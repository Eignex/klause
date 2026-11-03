package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.basis.BasisSolver
import com.eignex.klause.simplex.basis.KotlinBasisSolver
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LpDirectionalProbesTest {
    @Test
    fun `child probes preserve objective units across sense scale and common constants`() {
        for (sense in listOf(Sense.MINIMIZE, Sense.MAXIMIZE)) for (scale in listOf(1L, 4L)) {
            val builder = LpBuilder()
            val sign = if (sense == Sense.MAXIMIZE) -1L else 1L
            repeat(3) { builder.addVar(0, 1, cost = longArrayOf(3L, 2L, 4L)[it] * sign) }
            repeat(3) { builder.addRow(intArrayOf(it, (it + 1) % 3), longArrayOf(1, 1), Relation.GE, 1L) }
            val trail = LpBoundTrail(assertNotNull(builder.build(sense).authoritativeModel()))
            val objective = trail.state.model.objective
            assertTrue(trail.replaceObjective(ExactLpObjective(List(objective.size) { objective.cost(it) },
                scale = ExactLpNumber.of(scale), externalConstant = ExactLpNumber.of(1L shl 60))))
            val model = assertNotNull(trail.state.ownerWorkingModel())
            RevisedSimplex(model).use { solver ->
                assertNotNull(solver.solve())
                var work = 0L
                var completed = 0

                val gains = assertNotNull(solver.directionalGains(model, intArrayOf(0, 1), longArrayOf(0, 0),
                    1_000_000L, Cancellation.Never) { work += it.work; completed += it.completed })

                assertEquals(1.5 / scale, gains[0].down, 1e-7)
                assertEquals(0.5 / scale, gains[0].up, 1e-7)
                assertEquals(2.5 / scale, gains[1].down, 1e-7)
                assertEquals(0.5 / scale, gains[1].up, 1e-7)
                assertEquals(4, completed)
                assertTrue(work in 1L..1_000_000L)
            }
        }
    }

    @Test
    fun `child probes leave the parent state optimum basis and metrics unchanged`() {
        val builder = LpBuilder()
        repeat(3) { builder.addVar(0, 1, cost = longArrayOf(3L, 2L, 4L)[it]) }
        repeat(3) { builder.addRow(intArrayOf(it, (it + 1) % 3), longArrayOf(1, 1), Relation.GE, 1L) }
        val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        val state = trail.state
        val model = assertNotNull(state.ownerWorkingModel())
        RevisedSimplex(model).use { solver ->
            val before = assertNotNull(solver.solve())
            val basic = before.basis.basicVars.copyOf()
            val statuses = before.basis.status.copyOf()
            val metrics = solver.lastMetrics

            assertNotNull(solver.directionalGains(model, intArrayOf(0, 1), longArrayOf(0, 0),
                1_000_000L, Cancellation.Never) {})

            assertTrue(trail.state === state)
            assertContentEquals(basic, assertNotNull(solver.retainedBasis()).basicVars)
            assertContentEquals(statuses, assertNotNull(solver.retainedBasis()).status)
            assertContentEquals(basic, before.basis.basicVars)
            assertContentEquals(statuses, before.basis.status)
            assertEquals(metrics, solver.lastMetrics)
            assertEquals(4.5, assertNotNull(solver.resolveBounds()).objective, 1e-7)
        }
    }

    @Test
    fun `unavailable child probes perform no preparation or numerical work`() {
        for (decline in listOf("unfunded", "cancelled", "stale", "closed", "empty", "duplicate", "shape")) {
            val builder = LpBuilder()
            repeat(3) { builder.addVar(0, 1, cost = longArrayOf(3L, 2L, 4L)[it]) }
            repeat(3) { builder.addRow(intArrayOf(it, (it + 1) % 3), longArrayOf(1, 1), Relation.GE, 1L) }
            val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
            val model = assertNotNull(trail.state.ownerWorkingModel())
            RevisedSimplex(model).use { solver ->
                assertNotNull(solver.solve())
                if (decline == "closed") solver.close()
                if (decline == "stale") assertTrue(trail.assertBound(2, false, ExactLpSide(ExactLpNumber.of(1L)), 1L))
                val point = if (decline == "stale") assertNotNull(trail.state.ownerWorkingModel()) else model
                val columns = when (decline) {
                    "empty" -> intArrayOf()
                    "duplicate" -> intArrayOf(0, 0)
                    else -> intArrayOf(0, 1)
                }
                val splits = if (decline == "shape") longArrayOf(0) else LongArray(columns.size)
                var work = 0L

                val gains = solver.directionalGains(point, columns, splits,
                    if (decline == "unfunded") 1L else 1_000_000L,
                    if (decline == "cancelled") Cancellation { true } else Cancellation.Never) { work += it.work }

                assertNull(gains, decline)
                assertEquals(0L, work, decline)
            }
        }
    }

    @Test
    fun `cancelled child queries retire scratch factors and preserve the parent`() {
        val builder = LpBuilder()
        repeat(3) { builder.addVar(0, 1, cost = longArrayOf(3L, 2L, 4L)[it]) }
        repeat(3) { builder.addRow(intArrayOf(it, (it + 1) % 3), longArrayOf(1, 1), Relation.GE, 1L) }
        val model = assertNotNull(LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
            .ownerWorkingModel())
        var created = 0
        var closed = 0
        var probing = false
        var cancelled = false
        RevisedSimplex(model, basisSolverFactory = { matrix ->
            created++
            if (probing) cancelled = true
            val backend = KotlinBasisSolver(matrix)
            object : BasisSolver by backend {
                private var retired = false
                override fun close() {
                    if (!retired) { retired = true; closed++ }
                    backend.close()
                }
            }
        }).use { solver ->
            assertNotNull(solver.solve())
            val beforeCreated = created
            val beforeLive = created - closed
            probing = true
            var work = 0L

            val gains = solver.directionalGains(model, intArrayOf(0, 1), longArrayOf(0, 0),
                1_000_000L, Cancellation { cancelled }) { work += it.work }

            assertNull(gains)
            assertTrue(created > beforeCreated)
            assertEquals(beforeLive, created - closed)
            assertTrue(work > 0L)
            assertEquals(4.5, assertNotNull(solver.resolveBounds()).objective, 1e-7)
        }
        assertEquals(created, closed)
    }

    @Test
    fun `stopped child probes keep spent work and do not publish partial gains`() {
        val builder = LpBuilder()
        repeat(3) { builder.addVar(0, 1, cost = longArrayOf(3L, 2L, 4L)[it]) }
        repeat(3) { builder.addRow(intArrayOf(it, (it + 1) % 3), longArrayOf(1, 1), Relation.GE, 1L) }
        val model = assertNotNull(LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
            .ownerWorkingModel())
        RevisedSimplex(model).use { solver ->
            assertNotNull(solver.solve())
            var work = 0L
            var completed = 0

            val gains = solver.directionalGains(model, intArrayOf(0, 1), longArrayOf(0, 0),
                100L, Cancellation.Never) { work += it.work; completed += it.completed }

            assertNull(gains)
            assertTrue(work in 1L..100L)
            assertEquals(0, completed)
            assertEquals(4.5, assertNotNull(solver.resolveBounds()).objective, 1e-7)
        }
    }
}
