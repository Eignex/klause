package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.basis.BasisArithmeticException
import com.eignex.klause.simplex.basis.BasisSolver
import com.eignex.klause.simplex.basis.IndexedVector
import com.eignex.klause.simplex.basis.KotlinBasisSolver
import com.eignex.klause.util.ExecutionControl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LpDirectionalProbeFundingTest {
    @Test
    fun `funded parent values reduce complete query work without changing values`() {
        for (rowScale in listOf(1L, 1_000_000L)) {
            val n = 12
            val diagonal = n / 2 + 2L
            val rhs = diagonal + n - 1L
            val builder = LpBuilder()
            repeat(n) { builder.addVar(0, 1, cost = 2L * rhs) }
            repeat(n) { row ->
                builder.addRow(IntArray(n) { it }, LongArray(n) {
                    2L * rowScale * if (it == row) diagonal else 1L
                }, Relation.GE, rhs * rowScale)
            }
            val model = assertNotNull(LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
                .ownerWorkingModel())
            val results = ArrayList<List<LpDirectionalValues>>()
            val work = ArrayList<Long>()
            val solveWork = ArrayList<Long>()
            val parentWork = ArrayList<Long>()
            for (reuse in listOf(false, true)) {
                RevisedSimplex(model, reuseProbeValues = reuse).use { parent ->
                    val point = assertNotNull(parent.solve())
                    val before = parent.lastMetrics
                    val objective = parent.probeObjective(ExecutionControl.Never)
                    var charged = 0L
                    var solving = 0L
                    val numerical = ArrayList<LpProbeNumerical>()
                    val preflights = ArrayList<LpProbePreflight>()

                    val values = parent.directionalValues(LpProbeSource(model, point.basis), intArrayOf(0, 1),
                        longArrayOf(0, 0), 1_000_000L, ExecutionControl.Never) {
                        charged += it.work
                        solving += it.solveWork
                        it.numerical?.let(numerical::add)
                        it.preflight?.let(preflights::add)
                    }

                    results.add(assertNotNull(values))
                    work.add(charged)
                    solveWork.add(solving)
                    assertEquals(4, numerical.size)
                    assertEquals(1, numerical.count { it.factorCopy })
                    assertEquals(if (reuse) 1 else 0, numerical.count { it.parentValues })
                    assertEquals(if (reuse) 1 else 0, preflights.count { it.parentValueWork > 0L })
                    assertEquals(before, parent.lastMetrics)
                    assertEquals(objective, parent.probeObjective(ExecutionControl.Never))
                    assertEquals(point.objective, assertNotNull(parent.resolveBounds()).objective, 1e-7)
                    parentWork.add(parent.lastMetrics.workOps)
                }
            }
            for (column in results[0].indices) {
                assertEquals(results[0][column].down, results[1][column].down, 1e-7)
                assertEquals(results[0][column].up, results[1][column].up, 1e-7)
            }
            assertTrue(work[1] < work[0], "seeded=${work[1]} ordinary=${work[0]}")
            assertTrue(solveWork[1] < solveWork[0])
            assertEquals(parentWork[0], parentWork[1])
        }
    }

    @Test
    fun `funded parent values save complete query work on an updated sparse basis`() {
        val n = 13
        val builder = LpBuilder()
        repeat(n) { builder.addVar(0, 1, cost = 1L) }
        repeat(n) { builder.addRow(intArrayOf(it, (it + 1) % n), longArrayOf(1, 1), Relation.GE, 1L) }
        val model = assertNotNull(LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
            .ownerWorkingModel())
        val results = ArrayList<List<LpDirectionalGain>>()
        val work = ArrayList<Long>()
        for (reuse in listOf(false, true)) {
            RevisedSimplex(model, refactorUpdateLimit = 64, reuseProbeValues = reuse).use { parent ->
                assertTrue(assertNotNull(parent.solve()).optimal)
                var charged = 0L
                var completed = 0
                var seeded = 0

                val gains = parent.directionalGains(model, intArrayOf(0, 1), longArrayOf(0, 0),
                    50_000L, ExecutionControl.Never) {
                    charged += it.work
                    completed += it.completed
                    if (it.numerical?.parentValues == true) seeded++
                    assertTrue(charged <= 50_000L)
                }

                results.add(assertNotNull(gains))
                work.add(charged)
                assertEquals(4, completed)
                assertEquals(if (reuse) 1 else 0, seeded)
            }
        }
        for (column in results[0].indices) {
            assertEquals(results[0][column].down, results[1][column].down, 1e-7)
            assertEquals(results[0][column].up, results[1][column].up, 1e-7)
        }
        assertTrue(work[1] < work[0], "seeded=${work[1]} ordinary=${work[0]}")
    }

    @Test
    fun `funded parent values preserve negative source units and common constants`() {
        val n = 12
        val diagonal = n / 2 + 2L
        val rhs = diagonal + n - 1L
        val builder = LpBuilder()
        repeat(n) { builder.addVar(0, 1, cost = -2L * rhs) }
        repeat(n) { row ->
            builder.addRow(IntArray(n) { it }, LongArray(n) {
                2_000_000L * if (it == row) diagonal else 1L
            }, Relation.LE, 1_000_000L * rhs)
        }
        val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        val objective = trail.state.model.objective
        assertTrue(trail.replaceObjective(ExactLpObjective(List(objective.size) { objective.cost(it) },
            scale = ExactLpNumber.of(4L), externalConstant = ExactLpNumber.of(1L shl 60))))
        val model = assertNotNull(trail.state.ownerWorkingModel())
        val results = ArrayList<LpDirectionalValues>()
        for (reuse in listOf(false, true)) {
            RevisedSimplex(model, reuseProbeValues = reuse).use { parent ->
                val point = assertNotNull(parent.solve())
                var reused = 0

                val values = parent.directionalValues(LpProbeSource(model, point.basis), intArrayOf(0), longArrayOf(0),
                    1_000_000L, ExecutionControl.Never) { if (it.numerical?.parentValues == true) reused++ }

                results.add(assertNotNull(values).single())
                assertEquals(if (reuse) 1 else 0, reused)
            }
        }
        assertTrue(results[1].down < 0.0 && results[1].up < 0.0)
        assertEquals(results[0].down, results[1].down, 1e-7)
        assertEquals(results[0].up, results[1].up, 1e-7)
    }

    @Test
    fun `an unfunded private basis solve retires without executing`() {
        val builder = LpBuilder()
        repeat(3) { builder.addVar(0, 1, cost = longArrayOf(3L, 2L, 4L)[it]) }
        repeat(3) { builder.addRow(intArrayOf(it, (it + 1) % 3), longArrayOf(1, 1), Relation.GE, 1L) }
        val model = assertNotNull(LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
            .ownerWorkingModel())
        RevisedSimplex(model).use { parent ->
            val point = assertNotNull(parent.solve())
            val entries = point.basis.basicVars.sumOf {
                if (it < model.n) model.csc.colPtr[it + 1] - model.csc.colPtr[it] else 1
            }.toLong()
            var created = 0
            var closed = 0
            RevisedSimplex(model, basisSolverFactory = { matrix ->
                created++
                val backend = KotlinBasisSolver(matrix)
                object : BasisSolver by backend {
                    override fun ftran(x: IndexedVector, expectedDensity: Double) = error("unfunded FTRAN")
                    override fun btran(x: IndexedVector, expectedDensity: Double) = error("unfunded BTRAN")
                    override fun close() {
                        closed++
                        backend.close()
                    }
                }
            }).use { privateSolver ->
                privateSolver.boundProbeWork(entries)

                assertNull(privateSolver.solve(point.basis))

                assertEquals(LpFloatTermination.WORK, privateSolver.lastTermination)
                assertEquals(entries, privateSolver.lastMetrics.workOps)
                assertEquals(1, created)
                assertEquals(created, closed)
                assertNull(privateSolver.continuationBasis(model))
            }
            assertEquals(4.5, assertNotNull(parent.resolveBounds()).objective, 1e-7)
        }
    }

    @Test
    fun `private query work stays within each positive grant`() {
        for (rowScale in listOf(1L, 1_000_000L)) {
            val builder = LpBuilder()
            repeat(3) { builder.addVar(0, 1, cost = longArrayOf(3L, 2L, 4L)[it]) }
            repeat(3) {
                builder.addRow(intArrayOf(it, (it + 1) % 3), longArrayOf(rowScale, rowScale), Relation.GE, rowScale)
            }
            val model = assertNotNull(LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
                .ownerWorkingModel())
            RevisedSimplex(model).use { parent ->
                val point = assertNotNull(parent.solve())
                val source = LpProbeSource(model, point.basis)
                val before = parent.lastMetrics
                var observedSolve = false
                var observedCompletion = false
                for (grant in listOf(1L, 128L, 256L, 512L, 768L, 1_024L, 1_536L, 2_048L, 4_096L, 16_384L)) {
                    var charged = 0L
                    var completed = 0

                    val values = parent.directionalValues(source, intArrayOf(0, 1), longArrayOf(0, 0),
                        grant, ExecutionControl.Never) {
                        charged += it.work
                        completed += it.completed
                        assertTrue(charged <= grant, "grant=$grant charged=$charged")
                        it.numerical?.let { numerical ->
                            observedSolve = true
                            assertTrue(it.solveWork <= numerical.workAllowance)
                        }
                    }

                    if (values != null) {
                        observedCompletion = true
                        assertEquals(4, completed)
                        assertEquals(6.0, values[0].down, 1e-7)
                        assertEquals(5.0, values[0].up, 1e-7)
                        assertEquals(7.0, values[1].down, 1e-7)
                        assertEquals(5.0, values[1].up, 1e-7)
                    } else {
                        assertTrue(completed < 4)
                    }
                    assertEquals(before, parent.lastMetrics)
                }
                assertTrue(observedSolve)
                assertTrue(observedCompletion)
                assertEquals(4.5, assertNotNull(parent.resolveBounds()).objective, 1e-7)
            }
        }
    }

    @Test
    fun `unfunded factor copies decline before allocating a private owner`() {
        val builder = LpBuilder()
        repeat(3) { builder.addVar(0, 1, cost = longArrayOf(3L, 2L, 4L)[it]) }
        repeat(3) { builder.addRow(intArrayOf(it, (it + 1) % 3), longArrayOf(1, 1), Relation.GE, 1L) }
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        val model = assertNotNull(state.ownerWorkingModel())
        RevisedSimplex(model).use { parent ->
            val optimum = assertNotNull(parent.solve())
            for (entryLimit in listOf(63L, 1_000_000L)) {
                val probes = LpDirectionalProbes(state, optimum.basis, 4.5,
                    sourceView = LpScalingView.create(model), factorPreparation = 128L,
                    factorEntries = if (entryLimit == 63L) 64L else 1_000_000L,
                    factorEntryLimit = entryLimit) { _, _, _, _ -> error("unfunded owner") }
                var preflight: LpProbePreflight? = null
                var spent = 0L
                var solving = 0L
                var completed = 0

                assertNull(probes.run(intArrayOf(0, 1), longArrayOf(0, 0), 10_000L, ExecutionControl.Never) {
                    preflight = it.preflight ?: preflight
                    spent += it.work
                    solving += it.solveWork
                    completed += it.completed
                })

                assertEquals(LpProbePreparationStop.FACTORS, assertNotNull(preflight).stop)
                assertEquals(128L, assertNotNull(preflight).factorCopyWork)
                assertTrue(spent in 1L until 10_000L)
                assertEquals(0L, solving)
                assertEquals(0, completed)
            }
        }
    }

    @Test
    fun `a shared allowance admits a first child requiring a larger numerical grant`() {
        val builder = LpBuilder()
        repeat(3) { builder.addVar(0, 1, cost = longArrayOf(3L, 2L, 4L)[it]) }
        repeat(3) { builder.addRow(intArrayOf(it, (it + 1) % 3), longArrayOf(1, 1), Relation.GE, 1L) }
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        val model = assertNotNull(state.ownerWorkingModel())
        RevisedSimplex(model).use { parent ->
            val point = assertNotNull(parent.solve())
            val probes = LpDirectionalProbes(state, point.basis, 4.5,
                sourceView = LpScalingView.create(model)) { child, grant, _, view ->
                RevisedSimplex(child, workLimit = grant, initialScaling = view, allowUnscaledFallback = false,
                    basisSolverFactory = { matrix ->
                        val backend = KotlinBasisSolver(matrix)
                        object : BasisSolver by backend {
                            override fun ftran(x: IndexedVector, expectedDensity: Double) {
                                if (grant < 2_500L) throw BasisArithmeticException("insufficient numerical funding")
                                backend.ftran(x, expectedDensity)
                            }
                        }
                    })
            }
            var spent = 0L
            var completed = 0
            val numerical = ArrayList<LpProbeNumerical>()

            val gains = assertNotNull(probes.run(intArrayOf(0, 1), longArrayOf(0, 0),
                6_000L, ExecutionControl.Never) {
                spent += it.work
                completed += it.completed
                it.numerical?.let(numerical::add)
            })

            assertEquals(1.5, gains[0].down, 1e-7)
            assertEquals(0.5, gains[0].up, 1e-7)
            assertEquals(2.5, gains[1].down, 1e-7)
            assertEquals(0.5, gains[1].up, 1e-7)
            assertEquals(4, completed)
            assertEquals(4, numerical.size)
            assertTrue(numerical.all { it.workAllowance > 0L && it.entryLimit > 0L })
            assertTrue(numerical.all { it.termination == LpFloatTermination.OPTIMAL_CANDIDATE })
            assertTrue(spent in 1L..6_000L)
            assertEquals(4.5, assertNotNull(parent.resolveBounds()).objective, 1e-7)
        }
    }

    @Test
    fun `preparation refusals retain spent work without constructing private factors`() {
        val builder = LpBuilder()
        repeat(3) { builder.addVar(0, 1, cost = longArrayOf(3L, 2L, 4L)[it]) }
        repeat(3) { builder.addRow(intArrayOf(it, (it + 1) % 3), longArrayOf(1, 1), Relation.GE, 1L) }
        val model = assertNotNull(LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
            .ownerWorkingModel())
        var created = 0
        RevisedSimplex(model, basisSolverFactory = { matrix ->
            created++
            KotlinBasisSolver(matrix)
        }).use { owner ->
            assertNotNull(owner.solve())
            var first: LpProbePreflight? = null
            assertNotNull(owner.directionalGains(model, intArrayOf(0, 1), longArrayOf(0, 0),
                1_000_000L, ExecutionControl.Never) { if (first == null) first = it.preflight })
            val preflight = assertNotNull(first)
            val beforePreflight = 1_000_000L - preflight.offeredWork
            for (stop in listOf(LpProbePreparationStop.WORKSPACE, LpProbePreparationStop.SCALARS)) {
                val allowance = beforePreflight + preflight.workspaceWork +
                    if (stop == LpProbePreparationStop.SCALARS) preflight.scalarWork else 0L
                val beforeCreated = created
                val parentMetrics = owner.lastMetrics
                var work = 0L
                var solving = 0L
                var completed = 0
                var refusal: LpProbePreflight? = null
                var decline: LpProbeDecline? = null

                val gains = owner.directionalGains(model, intArrayOf(0, 1), longArrayOf(0, 0),
                    allowance, ExecutionControl.Never) {
                    work += it.work
                    solving += it.solveWork
                    completed += it.completed
                    it.preflight?.let { p -> refusal = p }
                    decline = it.decline
                }

                assertNull(gains)
                assertEquals(stop, assertNotNull(refusal).stop)
                assertEquals(LpProbeDecline.ALLOWANCE, decline)
                assertEquals(beforePreflight, work)
                assertEquals(0L, solving)
                assertEquals(0, completed)
                assertEquals(beforeCreated, created)
                assertEquals(0L, assertNotNull(refusal).sourceCopyWorkSaved)
                assertEquals(parentMetrics, owner.lastMetrics)
            }
        }
    }

    @Test
    fun `unavailable child probes perform no preparation or numerical work`() {
        for (valueMode in listOf(false, true)) {
            val declines = listOf(
                "unfunded" to LpProbeDecline.ALLOWANCE,
                "zero" to LpProbeDecline.ALLOWANCE,
                "cancelled" to LpProbeDecline.CANCELLED,
                "busy" to LpProbeDecline.BUSY,
                "stale" to LpProbeDecline.STALE_POINT,
                "closed" to LpProbeDecline.NO_OPTIMUM,
                "empty" to LpProbeDecline.SPLIT,
                "duplicate" to LpProbeDecline.SPLIT,
                "shape" to LpProbeDecline.SPLIT,
            )
            for ((decline, expected) in declines) {
                val builder = LpBuilder()
                repeat(3) { builder.addVar(0, 1, cost = longArrayOf(3L, 2L, 4L)[it]) }
                repeat(3) { builder.addRow(intArrayOf(it, (it + 1) % 3), longArrayOf(1, 1), Relation.GE, 1L) }
                val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
                val model = assertNotNull(trail.state.ownerWorkingModel())
                RevisedSimplex(model).use { solver ->
                    val pointResult = assertNotNull(solver.solve())
                    val pending = if (decline == "busy") solver.openSolve() else null
                    if (decline == "closed") solver.close()
                    if (decline == "stale") {
                        assertTrue(trail.assertBound(2, false, ExactLpSide(ExactLpNumber.of(1L)), 1L))
                    }
                    val point = if (decline == "stale") assertNotNull(trail.state.ownerWorkingModel()) else model
                    val columns = when (decline) {
                        "empty" -> intArrayOf()
                        "duplicate" -> intArrayOf(0, 0)
                        else -> intArrayOf(0, 1)
                    }
                    val splits = if (decline == "shape") longArrayOf(0) else LongArray(columns.size)
                    var work = 0L
                    var reason: LpProbeDecline? = null

                    val observe: (LpProbeMetrics) -> Unit = {
                        work += it.work
                        reason = it.decline
                    }
                    val allowance = when (decline) {
                        "unfunded" -> 1L
                        "zero" -> 0L
                        else -> 1_000_000L
                    }
                    val token = ExecutionControl { decline == "cancelled" }
                    val gains = if (valueMode) {
                        solver.directionalValues(LpProbeSource(point, pointResult.basis), columns, splits,
                            allowance, token, observe)
                    } else solver.directionalGains(point, columns, splits, allowance, token, observe)

                    assertNull(gains, decline)
                    assertEquals(0L, work, decline)
                    val wanted = if (valueMode && decline == "closed") LpProbeDecline.NO_POINT else expected
                    assertEquals(wanted, reason, decline)
                    pending?.close()
                }
            }
        }
    }
}
