package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.basis.BasisArithmeticException
import com.eignex.klause.simplex.basis.BasisRepair
import com.eignex.klause.simplex.basis.BasisRepairControl
import com.eignex.klause.simplex.basis.BasisSolver
import com.eignex.klause.simplex.basis.IndexedVector
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
    fun `cancellation after a seeded child withholds advice and retains work`() {
        val n = 12
        val diagonal = n / 2 + 2L
        val rhs = diagonal + n - 1L
        val builder = LpBuilder()
        repeat(n) { builder.addVar(0, 1, cost = 2L * rhs) }
        repeat(n) { row ->
            builder.addRow(IntArray(n) { it }, LongArray(n) {
                2L * if (it == row) diagonal else 1L
            }, Relation.GE, rhs)
        }
        val model = assertNotNull(LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
            .ownerWorkingModel())
        RevisedSimplex(model).use { parent ->
            val point = assertNotNull(parent.solve())
            val before = parent.lastMetrics
            var cancelled = false
            var charged = 0L
            var completed = 0
            var decline: LpProbeDecline? = null

            val gains = parent.directionalGains(model, intArrayOf(0), longArrayOf(0), 1_000_000L,
                Cancellation { cancelled }) {
                charged += it.work
                completed += it.completed
                decline = it.decline
                if (it.numerical?.parentValues == true) cancelled = true
            }

            assertNull(gains)
            assertEquals(1, completed)
            assertEquals(LpProbeDecline.CANCELLED, decline)
            assertTrue(charged in 1L..1_000_000L)
            assertEquals(before, parent.lastMetrics)
            assertEquals(point.objective, assertNotNull(parent.resolveBounds()).objective, 1e-7)
        }
    }

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
                    val objective = parent.probeObjective(Cancellation.Never)
                    var charged = 0L
                    var solving = 0L
                    val numerical = ArrayList<LpProbeNumerical>()
                    val preflights = ArrayList<LpProbePreflight>()

                    val values = parent.directionalValues(LpProbeSource(model, point.basis), intArrayOf(0, 1),
                        longArrayOf(0, 0), 1_000_000L, Cancellation.Never) {
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
                    assertEquals(objective, parent.probeObjective(Cancellation.Never))
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
                    50_000L, Cancellation.Never) {
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
                    1_000_000L, Cancellation.Never) { if (it.numerical?.parentValues == true) reused++ }

                results.add(assertNotNull(values).single())
                assertEquals(if (reuse) 1 else 0, reused)
            }
        }
        assertTrue(results[1].down < 0.0 && results[1].up < 0.0)
        assertEquals(results[0].down, results[1].down, 1e-7)
        assertEquals(results[0].up, results[1].up, 1e-7)
    }

    @Test
    fun `strict private probes decline repair with the failed work retained`() {
        val builder = LpBuilder()
        repeat(3) { builder.addVar(0, 1, cost = longArrayOf(3L, 2L, 4L)[it]) }
        repeat(3) { builder.addRow(intArrayOf(it, (it + 1) % 3), longArrayOf(1, 1), Relation.GE, 1L) }
        val model = assertNotNull(LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
            .ownerWorkingModel())
        var closed = 0
        RevisedSimplex(model, basisSolverFactory = { matrix ->
            val backend = KotlinBasisSolver(matrix)
            object : BasisSolver by backend {
                override fun refactorize(basicIndex: IntArray): Boolean = false
                override fun refactorizeRepairing(basicIndex: IntArray, control: BasisRepairControl): BasisRepair? =
                    error("unfunded repair")
                override fun close() { closed++; backend.close() }
            }
        }).use { privateSolver ->
            privateSolver.boundProbeWork(1_000L)

            assertNull(privateSolver.solve())

            assertEquals(LpFloatTermination.WORK, privateSolver.lastTermination)
            assertTrue(privateSolver.lastMetrics.workOps in 1L..1_000L)
            assertEquals(1, closed)
            assertNull(privateSolver.continuationBasis(model))
        }
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
                    override fun close() { closed++; backend.close() }
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
                        grant, Cancellation.Never) {
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

                assertNull(probes.run(intArrayOf(0, 1), longArrayOf(0, 0), 10_000L, Cancellation.Never) {
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
    fun `cancellation after an imported child withholds the comparison and preserves its charge`() {
        val builder = LpBuilder()
        repeat(3) { builder.addVar(0, 1, cost = longArrayOf(3L, 2L, 4L)[it]) }
        repeat(3) { builder.addRow(intArrayOf(it, (it + 1) % 3), longArrayOf(1, 1), Relation.GE, 1L) }
        val model = assertNotNull(LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
            .ownerWorkingModel())
        RevisedSimplex(model).use { parent ->
            assertNotNull(parent.solve())
            val metrics = parent.lastMetrics
            var cancelled = false
            var work = 0L
            var completed = 0
            var copies = 0
            var decline: LpProbeDecline? = null

            assertNull(parent.directionalGains(model, intArrayOf(0, 1), longArrayOf(0, 0),
                10_000L, Cancellation { cancelled }) {
                work += it.work
                completed += it.completed
                if (it.numerical?.factorCopy == true) copies++
                decline = it.decline
                if (completed == 1) cancelled = true
            })

            assertEquals(1, completed)
            assertEquals(1, copies)
            assertEquals(LpProbeDecline.CANCELLED, decline)
            assertTrue(work in 1L..10_000L)
            assertEquals(metrics, parent.lastMetrics)
            assertEquals(4.5, assertNotNull(parent.resolveBounds()).objective, 1e-7)
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
                6_000L, Cancellation.Never) {
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
    fun `pooled child values match independent queries with less work`() {
        for (rowScale in listOf(1L, 1_000_000L)) for (columns in listOf(intArrayOf(0, 1), intArrayOf(1, 0))) {
            val builder = LpBuilder()
            repeat(3) { builder.addVar(0, 1, cost = longArrayOf(3L, 2L, 4L)[it]) }
            repeat(3) {
                builder.addRow(intArrayOf(it, (it + 1) % 3), longArrayOf(rowScale, rowScale), Relation.GE, rowScale)
            }
            val model = assertNotNull(LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
                .ownerWorkingModel())
            RevisedSimplex(model).use { owner ->
                val point = assertNotNull(owner.solve())
                assertEquals(rowScale != 1L, owner.scalingMetrics.applied)
                val source = LpProbeSource(model, point.basis)
                var independentWork = 0L
                val independent = columns.map { column ->
                    assertNotNull(owner.directionalValues(source, intArrayOf(column), longArrayOf(0),
                        1_000_000L, Cancellation.Never) { independentWork += it.work }).single()
                }
                var pooledWork = 0L
                val preflights = ArrayList<LpProbePreflight>()
                val numerical = ArrayList<LpProbeNumerical>()
                var decline: LpProbeDecline? = null

                val result = owner.directionalValues(source, columns, longArrayOf(0, 0),
                    independentWork - 1L, Cancellation.Never) {
                        pooledWork += it.work
                        it.preflight?.let(preflights::add)
                        it.numerical?.let(numerical::add)
                        decline = it.decline
                    }
                val pooled = assertNotNull(result,
                    "scale=$rowScale columns=${columns.toList()} independent=$independentWork work=$pooledWork " +
                        "decline=$decline preflights=$preflights")

                assertEquals(independent.size, pooled.size)
                for (index in pooled.indices) {
                    assertEquals(independent[index].down, pooled[index].down, 1e-7)
                    assertEquals(independent[index].up, pooled[index].up, 1e-7)
                }
                assertTrue(pooledWork in 1L until independentWork)
                assertEquals(4, preflights.size)
                assertEquals(4, numerical.size)
                assertEquals(0, numerical.count { it.orderHint })
                assertEquals(1, numerical.count { it.factorCopy })
                assertTrue(numerical.first().copiedFactorEntries > 0L)
                assertEquals(0L, numerical.first().basisBuildWork)
                assertTrue(preflights.first().factorCopyWork > 0L)
                assertEquals(36L, preflights.first().sourceCopyWorkSaved)
                assertEquals(0L, preflights.sumOf { it.parentValueWork })
                assertTrue(numerical.none { it.parentValues })
                assertEquals(0L, preflights.drop(1).sumOf { it.sourceCopyWorkSaved })
                assertEquals(280L + preflights.first().factorCopyWork, preflights.first().workspaceWork)
                assertEquals(numerical.first().copiedFactorEntries, preflights.first().factorCopyEntries)
                assertTrue(numerical.first().entryLimit >= numerical.first().copiedFactorEntries)
                assertEquals(3, preflights.count { it.ownerReused })
                val reuse = if (rowScale == 1L) LpProbeReuse.IDENTITY else LpProbeReuse.SCALED
                assertTrue(preflights.all { it.reuse == reuse && it.stop == null })
            }
        }
    }

    @Test
    fun `cancellation after a pooled child retires private factors`() {
        for (rowScale in listOf(1L, 1_000_000L)) {
            val builder = LpBuilder()
            repeat(3) { builder.addVar(0, 1, cost = longArrayOf(3L, 2L, 4L)[it]) }
            repeat(3) {
                builder.addRow(intArrayOf(it, (it + 1) % 3), longArrayOf(rowScale, rowScale), Relation.GE, rowScale)
            }
            val model = assertNotNull(LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
                .ownerWorkingModel())
            var created = 0
            var closed = 0
            RevisedSimplex(model, basisSolverFactory = { matrix ->
                created++
                val backend = KotlinBasisSolver(matrix)
                object : BasisSolver by backend {
                    private var retired = false
                    override fun close() {
                        if (!retired) { retired = true; closed++ }
                        backend.close()
                    }
                }
            }).use { owner ->
                assertNotNull(owner.solve())
                val parentLive = created - closed
                var cancelled = false
                var completed = 0
                var work = 0L
                var decline: LpProbeDecline? = null

                val gains = owner.directionalGains(model, intArrayOf(0, 1), longArrayOf(0, 0),
                    1_000_000L, Cancellation { cancelled }) {
                    work += it.work
                    completed += it.completed
                    decline = it.decline
                    if (it.completed > 0) cancelled = true
                }

                assertNull(gains)
                assertEquals(1, completed)
                assertEquals(LpProbeDecline.CANCELLED, decline)
                assertEquals(parentLive, created - closed)
                assertTrue(work in 1L..1_000_000L)
                assertEquals(4.5, assertNotNull(owner.resolveBounds()).objective, 1e-7)
            }
            assertEquals(created, closed)
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
        RevisedSimplex(model, basisSolverFactory = { matrix -> created++; KotlinBasisSolver(matrix) }).use { owner ->
            assertNotNull(owner.solve())
            var first: LpProbePreflight? = null
            assertNotNull(owner.directionalGains(model, intArrayOf(0, 1), longArrayOf(0, 0),
                1_000_000L, Cancellation.Never) { if (first == null) first = it.preflight })
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
                    allowance, Cancellation.Never) {
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
    fun `scaled child failures decline without constructing an unscaled retry`() {
        val builder = LpBuilder()
        repeat(3) { builder.addVar(0, 1, cost = longArrayOf(3L, 2L, 4L)[it]) }
        repeat(3) {
            builder.addRow(intArrayOf(it, (it + 1) % 3), longArrayOf(1_000_000, 1_000_000), Relation.GE, 1_000_000L)
        }
        val model = assertNotNull(LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
            .ownerWorkingModel())
        var probing = false
        var created = 0
        var closed = 0
        RevisedSimplex(model, basisSolverFactory = { matrix ->
            created++
            val backend = KotlinBasisSolver(matrix)
            val fail = probing
            object : BasisSolver by backend {
                private var retired = false
                override fun ftran(x: IndexedVector, expectedDensity: Double) {
                    backend.ftran(x, expectedDensity)
                    if (fail) throw BasisArithmeticException("injected private child failure")
                }
                override fun close() {
                    if (!retired) { retired = true; closed++ }
                    backend.close()
                }
            }
        }).use { owner ->
            assertNotNull(owner.solve())
            assertTrue(owner.scalingMetrics.applied)
            val beforeCreated = created
            val beforeClosed = closed
            val parentMetrics = owner.lastMetrics
            val parentScaling = owner.scalingMetrics
            probing = true
            var work = 0L
            var decline: LpProbeDecline? = null

            val gains = owner.directionalGains(model, intArrayOf(0, 1), longArrayOf(0, 0),
                1_000_000L, Cancellation.Never) { work += it.work; decline = it.decline }

            assertNull(gains)
            assertEquals(LpProbeDecline.CHILD, decline)
            assertEquals(beforeCreated + 1, created)
            assertEquals(beforeClosed + 1, closed)
            assertTrue(work in 1L..1_000_000L)
            assertEquals(parentMetrics, owner.lastMetrics)
            assertEquals(parentScaling, owner.scalingMetrics)
        }
        assertEquals(created, closed)
    }

    @Test
    fun `child values preserve normalized source units without a common objective constant`() {
        for (sense in listOf(Sense.MINIMIZE, Sense.MAXIMIZE)) for (scale in listOf(1L, 4L)) {
            for (origin in listOf(0L, 7L, -3L)) for (negative in listOf(false, true)) {
                val builder = LpBuilder()
                val sign = (if (sense == Sense.MAXIMIZE) -1L else 1L) * (if (negative) -1L else 1L)
                repeat(3) { builder.addVar(origin, origin + 1L, cost = longArrayOf(3L, 2L, 4L)[it] * sign) }
                repeat(3) {
                    builder.addRow(intArrayOf(it, (it + 1) % 3), longArrayOf(1, 1),
                        if (negative) Relation.LE else Relation.GE, 2L * origin + 1L)
                }
                val trail = LpBoundTrail(assertNotNull(builder.build(sense).authoritativeModel()))
                val objective = trail.state.model.objective
                assertTrue(trail.replaceObjective(ExactLpObjective(List(objective.size) { objective.cost(it) },
                    scale = ExactLpNumber.of(scale), externalConstant = ExactLpNumber.of(1L shl 60))))
                val model = assertNotNull(trail.state.ownerWorkingModel())
                RevisedSimplex(model).use { owner ->
                    val point = assertNotNull(owner.solve())
                    var completed = 0

                    val values = owner.directionalValues(LpProbeSource(model, point.basis), intArrayOf(0, 1),
                        longArrayOf(origin, origin), 1_000_000L, Cancellation.Never) { completed += it.completed }

                    assertNotNull(values)
                    assertEquals((if (negative) -4.0 else 6.0) / scale, values[0].down, 1e-7)
                    assertEquals((if (negative) -3.0 else 5.0) / scale, values[0].up, 1e-7)
                    assertEquals((if (negative) -4.0 else 7.0) / scale, values[1].down, 1e-7)
                    assertEquals((if (negative) -2.0 else 5.0) / scale, values[1].up, 1e-7)
                    assertEquals(4, completed)
                }
            }
        }
    }

    @Test
    fun `cancellation during the parent objective scan retains its work charge`() {
        val builder = LpBuilder()
        repeat(3) { builder.addVar(0, 1, cost = longArrayOf(3L, 2L, 4L)[it]) }
        repeat(3) { builder.addRow(intArrayOf(it, (it + 1) % 3), longArrayOf(1, 1), Relation.GE, 1L) }
        val model = assertNotNull(LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
            .ownerWorkingModel())
        RevisedSimplex(model).use { solver ->
            assertNotNull(solver.solve())
            var cancelled = false
            var work = 0L
            var completed = 0
            var decline: LpProbeDecline? = null

            val gains = solver.directionalGains(model, intArrayOf(0, 1), longArrayOf(0, 0),
                1_000_000L, Cancellation { cancelled }) {
                work += it.work
                completed += it.completed
                decline = it.decline
                if (it.preparationWork > 0L) cancelled = true
            }

            assertNull(gains)
            assertEquals(LpProbeDecline.CANCELLED, decline)
            assertTrue(work in 1L..1_000_000L)
            assertEquals(0, completed)
            assertEquals(4.5, assertNotNull(solver.resolveBounds()).objective, 1e-7)
        }
    }

    @Test
    fun `a retained child state supplies complete probe gains after bound resolution`() {
        val builder = LpBuilder()
        repeat(3) { builder.addVar(0, 1, cost = longArrayOf(3L, 2L, 4L)[it]) }
        val auxiliary = builder.addVar(0, 1)
        repeat(3) { builder.addRow(intArrayOf(it, (it + 1) % 3), longArrayOf(1, 1), Relation.GE, 1L) }
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        LpScopedSolver(state).use { owner ->
            assertTrue(assertNotNull(owner.solveFloat()?.second).optimal)
            assertTrue(owner.push())
            assertTrue(owner.assertBound(auxiliary, false, ExactLpSide(ExactLpNumber.of(1L)), 1L))
            assertTrue(assertNotNull(owner.solveFloat()?.second).optimal)
            val point = assertNotNull(owner.state.ownerWorkingModel())
            var decline: LpProbeDecline? = null
            var completed = 0

            val gains = owner.directionalGains(point, intArrayOf(0, 1), longArrayOf(0, 0),
                1_000_000L, Cancellation.Never) { decline = it.decline; completed += it.completed }

            assertNotNull(gains, decline.toString())
            assertEquals(4, completed)
            assertEquals(1.5, gains[0].down, 1e-7)
            assertEquals(0.5, gains[0].up, 1e-7)
            assertEquals(2.5, gains[1].down, 1e-7)
            assertEquals(0.5, gains[1].up, 1e-7)
        }
    }

    @Test
    fun `child probes preserve objective units across sense scale and common constants`() {
        for (sense in listOf(Sense.MINIMIZE, Sense.MAXIMIZE)) for (scale in listOf(1L, 4L)) {
            for (origin in listOf(0L, 7L, -3L)) {
                val builder = LpBuilder()
                val sign = if (sense == Sense.MAXIMIZE) -1L else 1L
                repeat(3) { builder.addVar(origin, origin + 1L, cost = longArrayOf(3L, 2L, 4L)[it] * sign) }
                repeat(3) {
                    builder.addRow(intArrayOf(it, (it + 1) % 3), longArrayOf(1, 1), Relation.GE, 2L * origin + 1L)
                }
                val trail = LpBoundTrail(assertNotNull(builder.build(sense).authoritativeModel()))
                val objective = trail.state.model.objective
                assertTrue(trail.replaceObjective(ExactLpObjective(List(objective.size) { objective.cost(it) },
                    scale = ExactLpNumber.of(scale), externalConstant = ExactLpNumber.of(1L shl 60))))
                val model = assertNotNull(trail.state.ownerWorkingModel())
                RevisedSimplex(model).use { solver ->
                    assertNotNull(solver.solve())
                    var work = 0L
                    var completed = 0

                    val gains = assertNotNull(solver.directionalGains(
                        model, intArrayOf(0, 1), longArrayOf(origin, origin),
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
    }

    @Test
    fun `cancelled comparisons with a completed child withhold all gains`() {
        for (valueMode in listOf(false, true)) {
            val builder = LpBuilder()
            repeat(3) { builder.addVar(0, 1, cost = longArrayOf(3L, 2L, 4L)[it]) }
            repeat(3) { builder.addRow(intArrayOf(it, (it + 1) % 3), longArrayOf(1, 1), Relation.GE, 1L) }
            val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
            val model = assertNotNull(state.ownerWorkingModel())
            RevisedSimplex(model).use { parent ->
                val optimum = assertNotNull(parent.solve())
                var children = 0
                var work = 0L
                var completed = 0
                val token = Cancellation { children == 2 }
                val probes = LpDirectionalProbes(state, optimum.basis, 4.5) { child, grant, _, view ->
                    children++
                    RevisedSimplex(child, cancellation = token, workLimit = grant, initialScaling = view)
                }

                val observe: (LpProbeMetrics) -> Unit = { work += it.work; completed += it.completed }
                val gains = if (valueMode) {
                    probes.runValues(intArrayOf(0, 1), longArrayOf(0, 0), 1_000_000L, token, observe)
                } else probes.run(intArrayOf(0, 1), longArrayOf(0, 0), 1_000_000L, token, observe)

                assertNull(gains)
                assertEquals(1, completed)
                assertTrue(work in 1L..1_000_000L)
                assertEquals(4.5, assertNotNull(parent.resolveBounds()).objective, 1e-7)
            }
        }
    }

    @Test
    fun `child probes leave the parent state optimum basis and metrics unchanged`() {
        for (rowScale in listOf(1L, 1_000_000L)) {
            for (valueMode in listOf(false, true)) {
                val builder = LpBuilder()
                repeat(3) { builder.addVar(0, 1, cost = longArrayOf(3L, 2L, 4L)[it]) }
                repeat(3) {
                    builder.addRow(intArrayOf(it, (it + 1) % 3), longArrayOf(rowScale, rowScale), Relation.GE, rowScale)
                }
                val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
                val state = trail.state
                val model = assertNotNull(state.ownerWorkingModel())
                RevisedSimplex(model).use { solver ->
                    val before = assertNotNull(solver.solve())
                    val basic = before.basis.basicVars.copyOf()
                    val statuses = before.basis.status.copyOf()
                    val metrics = solver.lastMetrics

                    val result = if (valueMode) {
                        solver.directionalValues(LpProbeSource(model, before.basis), intArrayOf(0, 1),
                            longArrayOf(0, 0), 1_000_000L, Cancellation.Never) {}
                    } else {
                        solver.directionalGains(model, intArrayOf(0, 1), longArrayOf(0, 0),
                            1_000_000L, Cancellation.Never) {}
                    }
                    assertNotNull(result)

                    assertTrue(trail.state === state)
                    assertContentEquals(basic, assertNotNull(solver.retainedBasis()).basicVars)
                    assertContentEquals(statuses, assertNotNull(solver.retainedBasis()).status)
                    assertContentEquals(basic, before.basis.basicVars)
                    assertContentEquals(statuses, before.basis.status)
                    assertEquals(metrics, solver.lastMetrics)
                    assertEquals(4.5, assertNotNull(solver.resolveBounds()).objective, 1e-7)
                }
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

                    val observe: (LpProbeMetrics) -> Unit = { work += it.work; reason = it.decline }
                    val allowance = when (decline) { "unfunded" -> 1L; "zero" -> 0L; else -> 1_000_000L }
                    val token = Cancellation { decline == "cancelled" }
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

    @Test
    fun `a malformed child value basis retains its validation work without solving children`() {
        val builder = LpBuilder()
        repeat(3) { builder.addVar(0, 1, cost = longArrayOf(3L, 2L, 4L)[it]) }
        repeat(3) { builder.addRow(intArrayOf(it, (it + 1) % 3), longArrayOf(1, 1), Relation.GE, 1L) }
        val model = assertNotNull(LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
            .ownerWorkingModel())
        RevisedSimplex(model).use { owner ->
            val point = assertNotNull(owner.solve())
            val metrics = owner.lastMetrics
            val headings = point.basis.basicVars.copyOf()
            headings[1] = headings[0]
            val source = LpProbeSource(model, Basis(headings, point.basis.status))
            var work = 0L
            var completed = 0
            var decline: LpProbeDecline? = null

            val values = owner.directionalValues(source, intArrayOf(0, 1), longArrayOf(0, 0),
                1_000_000L, Cancellation.Never) { work += it.work; completed += it.completed; decline = it.decline }

            assertNull(values)
            assertEquals(LpProbeDecline.SPLIT, decline)
            assertTrue(work in 1L..1_000_000L)
            assertEquals(0, completed)
            assertEquals(metrics, owner.lastMetrics)
        }
    }

    @Test
    fun `cancelled child queries retire scratch factors and preserve the parent`() {
        for (valueMode in listOf(false, true)) {
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
                val point = assertNotNull(solver.solve())
                val beforeCreated = created
                val beforeLive = created - closed
                probing = true
                var work = 0L

                val token = Cancellation { cancelled }
                val observe: (LpProbeMetrics) -> Unit = { work += it.work }
                val gains = if (valueMode) {
                    solver.directionalValues(LpProbeSource(model, point.basis), intArrayOf(0, 1), longArrayOf(0, 0),
                        1_000_000L, token, observe)
                } else solver.directionalGains(model, intArrayOf(0, 1), longArrayOf(0, 0), 1_000_000L, token, observe)

                assertNull(gains)
                assertTrue(created > beforeCreated)
                assertEquals(beforeLive, created - closed)
                assertTrue(work > 0L)
                assertEquals(4.5, assertNotNull(solver.resolveBounds()).objective, 1e-7)
            }
            assertEquals(created, closed)
        }
    }

    @Test
    fun `stopped child probes keep spent work and do not publish partial gains`() {
        for (valueMode in listOf(false, true)) {
            val builder = LpBuilder()
            repeat(3) { builder.addVar(0, 1, cost = longArrayOf(3L, 2L, 4L)[it]) }
            repeat(3) { builder.addRow(intArrayOf(it, (it + 1) % 3), longArrayOf(1, 1), Relation.GE, 1L) }
            val model = assertNotNull(LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
                .ownerWorkingModel())
            RevisedSimplex(model).use { solver ->
                val point = assertNotNull(solver.solve())
                var work = 0L
                var completed = 0
                var decline: LpProbeDecline? = null

                val observe: (LpProbeMetrics) -> Unit = {
                    work += it.work
                    completed += it.completed
                    decline = it.decline
                }
                val gains = if (valueMode) {
                    solver.directionalValues(LpProbeSource(model, point.basis), intArrayOf(0, 1), longArrayOf(0, 0),
                        100L, Cancellation.Never, observe)
                } else solver.directionalGains(model, intArrayOf(0, 1), longArrayOf(0, 0),
                    100L, Cancellation.Never, observe)

                assertNull(gains)
                assertTrue(work in 1L..100L)
                assertEquals(0, completed)
                assertEquals(LpProbeDecline.ALLOWANCE, decline)
                assertEquals(4.5, assertNotNull(solver.resolveBounds()).objective, 1e-7)
            }
        }
    }
}
