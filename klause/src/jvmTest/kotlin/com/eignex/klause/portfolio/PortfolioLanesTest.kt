package com.eignex.klause.portfolio

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.UnresolvedRealLeafFixture
import com.eignex.klause.backtrack.selector.IndomainMax
import com.eignex.klause.backtrack.selector.IndomainMiddle
import com.eignex.klause.backtrack.selector.InputOrder
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.lp.bounding.LpEmphasis
import com.eignex.klause.lp.bounding.LpTechnique
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.ResumableOptimizer
import com.eignex.klause.solver.ResumableSearch
import com.eignex.klause.solver.ResumableSolve
import com.eignex.klause.solver.ResumableSolver
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.MinimizeResult.WithSample
import com.eignex.klause.solver.result.SearchEvent
import com.eignex.klause.util.Cancellation
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.TimeSource

@OptIn(ExperimentalAtomicApi::class)
class PortfolioLanesTest {
    @Test
    fun `a lane's unbounded ray should end the minimize`() {
        // x in [0,3], r >= 0 with x - r <= 1: minimizing -r descends without limit.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 3)),
            factors = arrayOf<Factor>(
                Linear(longArrayOf(1L), intArrayOf(0), doubleArrayOf(-1.0), intArrayOf(0), LinearOp.LE, 1L),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
        ).bake()
        val objective = LinearObjective(realCoefficients = doubleArrayOf(-1.0))
        val workers = List(2) { index ->
            PortfolioWorker.of(
                "backtrack#$index",
                index,
                BacktrackSolver(problem).session(),
                BacktrackParams(),
                objective,
            )
        }

        val result = Portfolio.thompson(workers, lanes = workers.size).use { it.minimize() }

        assertTrue(assertIs<MinimizeResult.Unbounded>(result).direction.single().signum() > 0)
    }

    @Test
    fun `every slice a lane runs should receive the run deadline`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 3)),
            factors = emptyArray(),
        ).bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(1L))
        val deadline = TimeSource.Monotonic.markNow() + Duration.parse("1h")
        val runs = listOf<(Portfolio) -> Unit>(
            { it.solve(Cancellation.until(deadline)) },
            { it.minimize(Cancellation.until(deadline)) },
        )
        for (run in runs) {
            val seen = ArrayList<ComparableTimeMark?>()
            val bt = BacktrackSolver(problem)
            val recording = object : ResumableSolver<BacktrackParams>, ResumableOptimizer<BacktrackParams> {
                override val problem = bt.problem

                override fun solve(params: BacktrackParams): SolveResult = bt.solve(params)

                override fun samples(params: BacktrackParams) = bt.samples(params)

                override fun enumerate(params: BacktrackParams) = bt.enumerate(params)

                override fun minimize(objective: LinearObjective, params: BacktrackParams) =
                    bt.minimize(objective, params)

                override fun resumableSolve(params: BacktrackParams): ResumableSolve {
                    val handle = bt.resumableSolve(params)
                    return object : ResumableSolve by handle {
                        override fun runSlice(global: Cancellation, sliceMillis: Long, sliceNodes: Long) =
                            handle.runSlice(global, sliceMillis, sliceNodes).also { seen += global.deadline() }
                    }
                }

                override fun resumable(objective: LinearObjective, params: BacktrackParams): ResumableSearch {
                    val handle = bt.resumable(objective, params)
                    return object : ResumableSearch by handle {
                        override fun runSlice(
                            global: Cancellation,
                            sliceMillis: Long,
                            sliceNodes: Long,
                            onIncumbent: (MinimizeResult.WithSample) -> Unit,
                        ) = handle.runSlice(global, sliceMillis, sliceNodes, onIncumbent)
                            .also { seen += global.deadline() }
                    }
                }
            }
            val worker =
                PortfolioWorker.of("recording", 0, recording.session(), BacktrackParams(), objective = objective)

            Portfolio.thompson(listOf(worker)).use(run)

            assertTrue(seen.isNotEmpty() && seen.all { it == deadline }, "$seen")
        }
    }

    @Test
    fun `parallel real unresolved arms should not prove infeasibility or optimality`() {
        for (withIncumbent in listOf(false, true)) {
            val fixtures = List(2) { UnresolvedRealLeafFixture(withIncumbent) }
            val workers = fixtures.mapIndexed { index, fixture ->
                PortfolioWorker.of(
                    "real#$index",
                    index,
                    fixture.solver.session(),
                    fixture.params,
                    objective = fixture.objective,
                    withBound = { p, bound -> p.copy(objectiveBoundSupplier = bound) },
                )
            }
            Portfolio.thompson(workers, lanes = workers.size).use { portfolio ->
                var offers = 0
                val result = portfolio.minimize { offers++ }
                if (withIncumbent) {
                    fixtures.first().assertIncumbent(assertIs<MinimizeResult.BestFound>(result).sample)
                    assertEquals(1, offers)
                } else {
                    assertIs<MinimizeResult.Unknown>(result)
                    assertEquals(0, offers)
                }
                fixtures.forEach { it.assertVisitedLeaves() }
            }
        }
    }

    @Test
    fun `an injected btPool should override the curated backtrack arms`() {
        val pool = listOf(
            { BacktrackCatalog.byLabel("free") },
            { BacktrackCatalog.byLabel("conflictDriven") },
        )
        val scenario = PortfolioScenario(
            cores = 1,
            arms = 3,
            kind = Kind.CSP,
            engine = EngineMix.BACKTRACK,
            btPool = pool,
        )
        val labels = PortfolioComposition.compose(scenario).map { it.label }
        assertEquals(
            listOf("free", "conflictDriven", "free"),
            labels,
        )
    }

    @Test
    fun `the annotation arm should take the last backtrack slot and keep the guard`() {
        val annotation = BacktrackParams(randomSeed = 0L, lubyRestartBase = 512L)
        val scenario = PortfolioScenario(
            cores = 1,
            arms = 3,
            kind = Kind.CSP,
            engine = EngineMix.BACKTRACK,
            annotationArm = annotation,
        )
        val labels = PortfolioComposition.compose(scenario).map { it.label }
        assertEquals("satOptimized", labels.first())
        assertEquals("annotation", labels.last())
    }

    @Test
    fun `a lone backtrack slot should keep the guard over the annotation arm`() {
        val scenario = PortfolioScenario(
            cores = 1,
            arms = 1,
            kind = Kind.CSP,
            engine = EngineMix.BACKTRACK,
            annotationArm = BacktrackParams(randomSeed = 0L),
        )
        val labels = PortfolioComposition.compose(scenario).map { it.label }
        assertEquals(listOf("satOptimized"), labels)
    }

    @Test
    fun `the selector-switch arm should wire a fresh restart-switching portfolio per worker`() {
        val arm = BacktrackWorkerConfig.byLabel("selector-switch")
        val a = arm.recipe.build(1L, null)
        val b = arm.recipe.build(2L, null)
        assertEquals(100L, a.lubyRestartBase)
        assertTrue(a.variableSelector !== b.variableSelector)
    }

    @Test
    fun `lp ceiling should cap and toggle the portfolio arms`() {
        fun lpConfigs(ceiling: LpConfig): List<LpConfig?> =
            BacktrackWorkerConfig.diverse(Kind.COP, count = 6, lpCeiling = ceiling)
                .map { it.recipe.build(1L, null).lpConfig }

        val off = lpConfigs(LpConfig(LpEmphasis.OFF))
        assertTrue(off.all { (it?.emphasis ?: LpEmphasis.OFF) == LpEmphasis.OFF })
        val capped = lpConfigs(LpConfig(LpEmphasis.DEFAULT)).mapNotNull { it?.emphasis }
        assertTrue(capped.all { it.ordinal <= LpEmphasis.DEFAULT.ordinal })
        val noCuts = lpConfigs(LpConfig.parse("aggressive,-cuts")).filterNotNull()
        assertTrue(noCuts.all { !it.resolved(LpTechnique.CUTS) })
    }

    @Test
    fun `portfolio solve should return sat on a satisfiable problem`() {
        val problem = exactlyOneOver(4)
        val workers = List(4) { i ->
            PortfolioWorker.of(
                "bt#$i",
                i,
                BacktrackSolver(problem.bake()).session(),
                BacktrackParams(randomSeed = i.toLong()),
            )
        }
        Portfolio.thompson(workers, lanes = workers.size).use { p ->
            val r = p.solve()
            assertTrue(r is SolveResult.Sat, "expected Sat, got $r")
        }
    }

    @Test
    fun `portfolio solve should return unsat on an unsat problem`() {
        val problem = Problem(
            numBoolVars = 1,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, true))),
                Clause(intArrayOf(Lit.make(0, false))),
            ),
        )
        val workers = List(2) { i ->
            PortfolioWorker.of("bt#$i", i, BacktrackSolver(problem.bake()).session(), BacktrackParams(randomSeed = 0L))
        }
        Portfolio.thompson(workers, lanes = workers.size).use { p ->
            val r = p.solve()
            assertIs<SolveResult.Unsat>(r)
            Unit
        }
    }

    @Test
    fun `budget-capped workers should never upgrade the incumbent to a false optimal`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 1000)),
            factors = arrayOf<Factor>(),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(1L))
        val workers = List(2) { i ->
            PortfolioWorker.of(
                "bt#$i",
                i,
                BacktrackSolver(problem.bake()).session(),
                BacktrackParams(
                    randomSeed = i.toLong(),
                    maxDecisions = 4,
                    variableSelector = InputOrder,
                    valueSelector = if (i == 0) IndomainMax else IndomainMiddle,
                ),
                objective = obj,
            ) { params, supplier ->
                params.copy(objectiveBoundSupplier = supplier)
            }
        }
        Portfolio.thompson(workers, lanes = workers.size).use { p ->
            assertIs<MinimizeResult.BestFound>(p.minimize())
            Unit
        }
    }

    @Test
    fun `parallel minimize should return the optimum with a sample that realises it`() {
        val workers = List(3) { i -> smallCopWorker("bt#$i", i) }
        Portfolio.thompson(workers, lanes = workers.size).use { p ->
            val r = p.minimize()
            val ws = assertIs<WithSample>(r)
            assertEquals(3.0, ws.objectiveValue)
            assertEquals(3.0, ws.sample.ints[0] * 1.0 + ws.sample.ints[1] * 2.0)
        }
    }

    @Test
    fun `builder minimize should wire a per-worker objective across a mixed pool`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 5), IntDomain(0, 5)),
            factors = arrayOf<Factor>(
                Linear(coeffs = intArrayOf(1, 1), vars = intArrayOf(0, 1), op = LinearOp.GE, bound = 3),
            ),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(1L, 2L))
        val sawOptimum = AtomicBoolean(false)
        Portfolio.thompson(
            lanes = 4,
            workers = PortfolioBuilder.build(
                problem.bake(),
                PortfolioScenario.parallel(cores = 4, kind = Kind.COP, engine = EngineMix.MIXED, seed = 1L),
                objective = obj,
                onEvent = { _, e ->
                    if (e is SearchEvent.Incumbent && e.objective <= 3.0) sawOptimum.store(true)
                },
            ),
        ).use { p ->
            val fallback = TimeSource.Monotonic.markNow() + Duration.parse("30s")
            val r = p.minimize(cancellation = { sawOptimum.load() || fallback.hasPassedNow() })
            assertEquals(3.0, assertIs<WithSample>(r).objectiveValue)
        }
    }

    @Test
    fun `builder should make a mixed LS plus backtrack portfolio that solves`() {
        val problem = exactlyOneOver(4)
        Portfolio.thompson(
            lanes = 4,
            workers = PortfolioBuilder.build(
                problem.bake(),
                PortfolioScenario.parallel(cores = 4, kind = Kind.CSP, engine = EngineMix.MIXED, seed = 1L),
            ),
        ).use { p ->
            val r = p.solve()
            assertTrue(r is SolveResult.Sat, "got $r")
        }
    }

    @Test
    fun `builder backtrack pool should include the sat-optimized worker and solve`() {
        val problem = pigeonhole(pigeons = 4, holes = 3)
        Portfolio.thompson(
            lanes = 4,
            workers = PortfolioBuilder.buildExplicit(
                problem.bake(),
                emptyList(),
                backtrackWorkers = 1,
                kind = Kind.CSP,
            ),
        ).use { p ->
            assertTrue(p.workers.any { it.label.startsWith("bt/") })
            assertIs<SolveResult.Unsat>(p.solve())
        }
    }

    @Test
    fun `three backtrack workers should cycle through the complete configs and solve`() {
        Portfolio.thompson(
            lanes = 4,
            workers = PortfolioBuilder.buildExplicit(
                exactlyOneOver(5).bake(),
                emptyList(),
                backtrackWorkers = 3,
                kind = Kind.CSP,
            ),
        ).use { p ->
            assertEquals(3, p.workers.count { it.label.startsWith("bt/") })
            assertIs<SolveResult.Sat>(p.solve())
        }
    }

    @Test
    fun `a parallel scenario with fewer arms than cores should replicate the arms across lanes`() {
        val workers = PortfolioBuilder.build(
            exactlyOneOver(5).bake(),
            PortfolioScenario(cores = 6, arms = 2, kind = Kind.CSP, engine = EngineMix.LOCAL_SEARCH),
        )
        assertEquals(6, workers.size)
        val labels = workers.map { it.label }
        assertEquals(2, labels.toSet().size)
        assertEquals(labels[0], labels[2])
        assertEquals(labels[1], labels[3])
        assertTrue(labels[0] != labels[1])
        assertEquals(listOf(0, 1, 0, 1, 0, 1), workers.map { it.armId })
    }

    @Test
    fun `a parallel scenario with arms equal to cores should build one worker per arm`() {
        val workers = PortfolioBuilder.build(
            exactlyOneOver(5).bake(),
            PortfolioScenario.parallel(cores = 4, kind = Kind.CSP, engine = EngineMix.LOCAL_SEARCH),
        )
        assertEquals(4, workers.size)
        assertEquals(listOf(0, 1, 2, 3), workers.map { it.armId })
    }

    @Test
    fun `a sequential scenario should build one worker per arm`() {
        val workers = PortfolioBuilder.build(
            exactlyOneOver(5).bake(),
            PortfolioScenario.sequential(kind = Kind.CSP, engine = EngineMix.LOCAL_SEARCH, arms = 5),
        )
        assertEquals(5, workers.size)
        assertEquals(listOf(0, 1, 2, 3, 4), workers.map { it.armId })
    }

    @Test
    fun `an attributed improvement should carry the producing worker's arm id`() {
        val workers = List(3) { i -> smallCopWorker("bt#$i", i) }
        val byLabel = workers.associate { it.label to it.armId }
        val seen = mutableListOf<AttributedImprovement>()
        Portfolio.thompson(workers, lanes = workers.size).use { p -> p.minimize(onImprovement = { seen += it }) }
        assertTrue(seen.isNotEmpty())
        assertTrue(seen.all { it.armId == byLabel[it.workerLabel] })
    }

    @Test
    fun `improvements reported across lanes should never include a losing arm's stale incumbent`() {
        // Racing arms can each beat the bound they last read; only the one installing the new best may emit.
        val workers = List(4) { i -> smallCopWorker("bt#$i", i) }
        val objectives = ArrayList<Double>()
        Portfolio.thompson(workers, lanes = workers.size).use { p ->
            p.minimize(onImprovement = { objectives += assertIs<WithSample>(it.result).objectiveValue })
        }
        assertTrue(objectives.isNotEmpty())
        assertTrue(
            objectives.zipWithNext().all { (prev, next) -> next < prev },
            "$objectives",
        )
    }

    private fun smallCopWorker(label: String, armId: Int): PortfolioWorker {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 5), IntDomain(0, 5)),
            factors = arrayOf<Factor>(
                Linear(coeffs = intArrayOf(1, 1), vars = intArrayOf(0, 1), op = LinearOp.GE, bound = 3),
            ),
        )
        return PortfolioWorker.of(
            label,
            armId,
            BacktrackSolver(problem.bake()).session(),
            BacktrackParams(randomSeed = 0L),
            objective = LinearObjective(intCoefficients = longArrayOf(1L, 2L)),
        ) { params, supplier -> params.copy(objectiveBoundSupplier = supplier) }
    }

    private fun exactlyOneOver(n: Int): Problem {
        val factor = Cardinality.exactlyOne(IntArray(n) { Lit.make(it, true) })
        return Problem(n, 0, emptyArray(), listOf(factor))
    }

    private fun pigeonhole(pigeons: Int, holes: Int): Problem {
        val factors = ArrayList<Factor>()
        fun v(p: Int, h: Int) = p * holes + h
        for (p in 0 until pigeons) factors.add(Clause(IntArray(holes) { h -> Lit.make(v(p, h), true) }))
        for (h in 0 until holes) {
            for (p1 in 0 until pigeons) {
                for (p2 in p1 + 1 until pigeons) {
                    factors.add(Clause(intArrayOf(Lit.make(v(p1, h), false), Lit.make(v(p2, h), false))))
                }
            }
        }
        return Problem(pigeons * holes, 0, emptyArray(), factors.toTypedArray())
    }

    @Test
    fun `with a lane for every arm each arm should run one segment for the whole solve`() {
        val problem = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(Cardinality.atLeastOne(IntArray(3) { Lit.make(it, true) })),
        )
        val objective = LinearObjective(boolWeights = longArrayOf(1L, 1L, 1L))
        val arms = List(2) { i ->
            PortfolioWorker.of(
                "ls$i",
                i,
                LocalSearchSolver(problem.bake()).session(),
                LocalSearchParams(randomSeed = i.toLong()),
                objective = objective,
                withInstructionBudget = { p, limit -> p.copy(maxInstructions = limit) },
            )
        }
        val stop = TimeSource.Monotonic.markNow() + Duration.parse("100ms")

        val r = Portfolio.thompson(arms, lanes = 2).use { it.minimize(cancellation = { stop.hasPassedNow() }) }

        assertEquals(listOf(1L, 1L), r.stats.portfolio.arms.map { it.segments })
    }
}
