package com.eignex.klause.portfolio

import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.backtrack.toBacktrackParams
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.circuit.Circuit
import com.eignex.klause.factor.scheduling.Diffn
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SearchGroup
import com.eignex.klause.ir.SearchHints
import com.eignex.klause.ir.VariableSelection
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.lp.bounding.LpTechnique
import com.eignex.klause.lp.engine.LpZeroObjectivePricing
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.ProblemProfile
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.pipeline.ExecutionStrategy
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.FinitePipeline
import com.eignex.klause.solver.pipeline.FinitePipelineRequest
import com.eignex.klause.solver.pipeline.ObjectiveCoordinateView
import com.eignex.klause.solver.pipeline.PortfolioPlan
import com.eignex.klause.solver.pipeline.PortfolioPlanRequest
import com.eignex.klause.solver.pipeline.planPortfolio
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PortfolioBuilderTest {
    @Test
    fun `materialized workers select the pool layout from integer coordinates`() {
        for (integerCount in listOf(0, 1)) {
            val problem = Problem(2, integerCount, Array(integerCount) { IntDomain(0, 1) },
                listOf(Clause(intArrayOf(0, 2)))).bake()
            val scenario = PortfolioScenario(1, 2, Kind.CSP, EngineMix.BACKTRACK, sharingMemory = true)
            val workers = PortfolioBuilder.build(problem, scenario)

            try {
                workers.forEach { it.solve(Cancellation.Never) }

                val expected = if (integerCount == 0) 1L else 0L
                assertTrue(workers.all {
                    requireNotNull(it.sharingMeter).snapshot().clauses.getValue("peakPoolBooleanOnly") == expected
                })
            } finally {
                workers.forEach { it.close() }
            }
        }
    }

    @Test
    fun `materialized workers enforce the configured shared byte limit`() {
        val problem = Problem(2, 0, emptyArray(), listOf(
            Clause(intArrayOf(0, 2)), Clause(intArrayOf(0, 3)),
            Clause(intArrayOf(1, 2)), Clause(intArrayOf(1, 3)),
        )).bake()
        val scenario = PortfolioScenario(1, 2, Kind.CSP, EngineMix.BACKTRACK,
            sharingMemory = true, sharingMemoryBytes = 0L)
        val workers = PortfolioBuilder.build(problem, scenario)

        try {
            workers.forEach { it.solve(Cancellation.Never) }
            val samples = workers.map { requireNotNull(it.sharingMeter).snapshot().clauses }
            assertTrue(samples.any { (it["seenDeclined"] ?: 0L) + (it["poolDeclined"] ?: 0L) > 0L })
            assertTrue(samples.all { it.getValue("peakPoolOwnedBytes") == 0L })
        } finally {
            workers.forEach { it.close() }
        }
    }

    @Test
    fun `memory observation is independently controlled through materialized workers`() {
        for (enabled in listOf(false, true)) {
            val problem = Problem(2, 0, emptyArray(), listOf(Clause(intArrayOf(0, 2)))).bake()
            val scenario = PortfolioScenario(1, 2, Kind.CSP, EngineMix.BACKTRACK,
                diagnosticWorkLimit = 1000L, sharingMemory = enabled, sharingMemoryWork = 1L)
            val workers = PortfolioBuilder.build(problem, scenario)

            try {
                for (worker in workers) {
                    worker.solve(Cancellation.Never)
                    val stats = requireNotNull(worker.sharingMeter).snapshot().clauses
                    if (enabled) {
                        assertTrue(stats.getValue("accountingWork") > 0L)
                        assertEquals(1L, stats.getValue("peakMemorySamplePartial"))
                    } else {
                        assertFalse("memorySampleWork" in stats)
                    }
                }
            } finally {
                workers.forEach { it.close() }
            }
        }
    }

    @Test
    fun `early core admission should preserve its pool and ordinary worker admission`() {
        val problem = Problem(2, 0, emptyArray(), emptyArray()).bake()
        val objective = LinearObjective(longArrayOf(1, 2))
        val scenario = PortfolioScenario.sequential(Kind.COP)
        val baseline = PortfolioBuilder.build(problem, scenario, objective)
        val early = PortfolioBuilder.build(problem, scenario.copy(earlyCoreAdmission = true), objective)

        try {
            assertEquals(baseline.map { it.label }, early.map { it.label })
            assertEquals(baseline.filter { it.label != "bt/core-guided" }.map { it.improvementOnly },
                early.filter { it.label != "bt/core-guided" }.map { it.improvementOnly })
            val ordinary = baseline.single { it.label == "bt/core-guided" }
            val candidate = early.single { it.label == "bt/core-guided" }
            assertTrue(ordinary.improvementOnly)
            assertFalse(candidate.improvementOnly)
            assertTrue(ordinary.auxiliaryProof && candidate.auxiliaryProof)
            assertTrue(ordinary.retainInitialProofCoverage && candidate.retainInitialProofCoverage)
            assertFalse(ordinary.earlyCoreTrial)
            assertTrue(candidate.earlyCoreTrial)
        } finally {
            baseline.forEach { it.close() }
            early.forEach { it.close() }
        }
    }

    @Test
    fun `early core admission should retain the auxiliary time allowance`() {
        val problem = Problem(2, 0, emptyArray(), emptyArray()).bake()
        val objective = LinearObjective(longArrayOf(1, 2))
        val scenario = PortfolioScenario.sequential(Kind.COP).copy(earlyCoreAdmission = true)
        val workers = PortfolioBuilder.build(problem, scenario, objective)

        try {
            val core = workers.single { it.label == "bt/core-guided" }
            requireNotNull(core.newResumableSearch({ Double.POSITIVE_INFINITY }, Cancellation.Never)).use {
                assertEquals(2_000L, it.stats.coreGuided.activeMillisLimit)
            }
        } finally {
            workers.forEach { it.close() }
        }
    }


    @Test
    fun `private objective coordinates should use the full slot beside the canonical primary`() {
        val source = Problem(0, 5, arrayOf(
            IntDomain(0, 5), IntDomain(1, 6), IntDomain(0, 5), IntDomain(0, 0), IntDomain(0, 0),
        ), listOf(
            Linear(longArrayOf(1, -1), intArrayOf(1, 0), LinearOp.EQ, 1),
            Diffn(intArrayOf(0, 2), intArrayOf(3, 4), longArrayOf(1, 1), longArrayOf(1, 1)),
        ))
        val sourceObjective = LinearObjective(intCoefficients = longArrayOf(0, 1, 0, 0, 0))
        val preparations = listOf(true, false).map { rewrite ->
            FinitePipeline.prepare(FinitePipelineRequest(
                source, FiniteEngine.MIXED, sourceObjective, PresolveConfig.parse("off,+affine"),
                transformObjective = rewrite,
            ))
        }
        val canonical = preparations[0]
        val view = assertNotNull(ObjectiveCoordinateView.create(canonical, preparations[1], null, Cancellation.Never))
        val problem = canonical.executableProblem()
        val scenario = PortfolioScenario.sequential(Kind.COP)
        val profile = ProblemProfile.of(problem, true)
        val baseline = PortfolioBuilder.factories(problem, scenario, canonical.objective, null, null, null, profile)
        val factories = PortfolioBuilder.factories(problem, scenario, canonical.objective, null, null, null,
            profile, false, objectiveCoordinates = view)

        val workers = factories.mapIndexed { index, factory -> factory.materialize(index, 3L, null) }

        try {
            assertEquals(baseline.size, workers.size)
            assertEquals("ls/cbls-primary-plateau/ils-basin", workers[2].label)
            assertEquals("ls/cbls-plateau/ils-basin/objective-coordinates", workers[3].label)
            assertTrue(workers[3].firstWitnessOnly)
            assertTrue(workers.all { it.evidenceModel?.matches(view.canonicalModel) == true })
        } finally {
            workers.forEach { it.close() }
        }
    }

    @Test
    fun `complete only selected pools should not request native heuristic views`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 2)), emptyArray()).bake()
        for ((engine, params) in listOf(
            FiniteEngine.BACKTRACK to emptyList(),
            FiniteEngine.MIXED to listOf("ls=0", "bt=1"),
        )) {
            val plan = assertIs<PortfolioPlan.Execute>(
                FinitePipeline.planPortfolio(
                    PortfolioPlanRequest(
                        engine, false, 1, params, 3, 1, LpConfig.OFF,
                        LpZeroObjectivePricing.MIN_BOUND_SUPPORT, null, null,
                    ),
                ),
            )
            var views = 0

            val factories = PortfolioBuilder.factories(
                problem, plan.scenario, null, null, null, null,
                ProblemProfile.of(problem, false), false, sourceHints = {
                    views++
                    null
                },
            )

            assertTrue(factories.isNotEmpty())
            assertTrue(factories.all { it.family == ArmFamily.Backtrack })
            assertEquals(0, views)
        }
    }

    @Test
    fun `selected heuristic pools should request their native view once`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 2)), emptyArray()).bake()
        for ((engine, expected) in listOf(
            EngineMix.LOCAL_SEARCH to ExecutionStrategy.LOCAL_SEARCH,
            EngineMix.ALNS to ExecutionStrategy.ALNS,
        )) {
            var views = 0
            var selected = emptySet<ExecutionStrategy>()
            val scenario = PortfolioScenario(1, 2, Kind.CSP, engine)

            val factories = PortfolioBuilder.factories(
                problem, scenario, null, null, null, null,
                ProblemProfile.of(problem, false), false, sourceHints = { strategies ->
                    views++
                    selected = strategies
                    null
                },
            )

            assertTrue(factories.isNotEmpty())
            assertEquals(setOf(expected), selected)
            assertEquals(1, views)
        }
    }

    @Test
    fun `annotated circuit diversity should have initial admission and complete source authority`() {
        val annotation = SearchHints(
            listOf(
                SearchGroup(intArrayOf(), intArrayOf(0, 1, 2)),
                SearchGroup(intArrayOf(), intArrayOf(3), VariableSelection.InputOrder),
            ),
        ).toBacktrackParams(0, 4)
        val scenario = PortfolioScenario.sequential(Kind.COP).copy(annotationArm = annotation)
        val objective = LinearObjective(intCoefficients = longArrayOf(0L, 0L, 0L, 1L))
        for (subcircuit in listOf(false, true)) {
            val problem = Problem(
                0,
                4,
                Array(4) { IntDomain(0, if (it == 3) 1 else 2) },
                arrayOf(Circuit(intArrayOf(0, 1, 2), subcircuit = subcircuit)),
            ).bake()

            val workers = PortfolioBuilder.build(problem, scenario, objective)

            try {
                val generic = workers.single { it.label == "bt/first-fail" }
                assertFalse(generic.improvementOnly)
                assertEquals(ArmFamily.Backtrack, generic.family)
                assertTrue(generic.permitsSourceProofs)
                assertTrue(generic.controlsObjectiveCutoff)
                assertNotNull(generic.sourceBinding)
                assertSame(workers.first().sharingInvocation, generic.sharingInvocation)
            } finally {
                workers.forEach { it.close() }
            }
        }
    }

    @Test
    fun `numerical completion should materialize finite heuristic workers without theory neighborhoods`() {
        val problem = Problem(
            0,
            0,
            emptyArray(),
            emptyArray(),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(1.0),
        ).bake()
        for (engine in listOf(EngineMix.LOCAL_SEARCH, EngineMix.ALNS)) {
            val scenario = PortfolioScenario(cores = 1, arms = 1, kind = Kind.CSP, engine = engine)
            val factory = PortfolioBuilder.factories(
                problem,
                scenario,
                null,
                null,
                null,
                null,
                ProblemProfile.of(problem, false),
                numericalCompletion = true,
            ).single()
            val worker = factory.materialize(0, 3L, null)

            worker.use {
                val result = worker.solve(Cancellation.Never, maxInstructions = 10_000)

                assertFalse("exact-neighborhood" in worker.label)
                assertNotNull(assertIs<SolveResult.Sat>(result).assignment.exactReals)
            }
        }
    }

    @Test
    fun `numerical heuristic worker copies should retain the configured source tolerance guarantee`() {
        val problem = Problem(
            0,
            0,
            emptyArray(),
            emptyArray(),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(1.0),
        ).bake()
        for (engine in listOf(EngineMix.LOCAL_SEARCH, EngineMix.ALNS)) {
            val scenario = PortfolioScenario(
                cores = 1,
                arms = 1,
                kind = Kind.CSP,
                engine = engine,
                reportLsResiduals = true,
                reportLsPhaseTimes = true,
                toleranceCheck = { it.reals.size == 1 && it.reals[0] in 0.0..1.0 },
            )
            val factory = PortfolioBuilder.factories(
                problem,
                scenario,
                null,
                null,
                null,
                null,
                ProblemProfile.of(problem, false),
                numericalCompletion = true,
            ).single()
            val worker = factory.materialize(0, 3L, NodeBudget(10_000))

            worker.use {
                val result = worker.solve(Cancellation.Never, maxInstructions = 10_000)

                val sample = assertIs<SolveResult.Sat>(result).assignment
                assertNull(sample.exactReals)
                assertTrue(sample.reals.single() in 0.0..1.0)
            }
        }
    }

    @Test
    fun `an exhausted containing allowance should not admit a tolerance completion`() {
        val problem = Problem(
            0,
            0,
            emptyArray(),
            emptyArray(),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(1.0),
        ).bake()
        var checks = 0
        val scenario = PortfolioScenario(
            cores = 1,
            arms = 1,
            kind = Kind.CSP,
            engine = EngineMix.LOCAL_SEARCH,
            toleranceCheck = {
                checks++
                true
            },
        )
        val factory = PortfolioBuilder.factories(
            problem,
            scenario,
            null,
            null,
            null,
            null,
            ProblemProfile.of(problem, false),
            numericalCompletion = true,
        ).single()
        val worker = factory.materialize(0, 3L, NodeBudget(0))

        worker.use {
            val result = worker.solve(Cancellation.Never, maxInstructions = 10_000)

            assertIs<SolveResult.Unknown>(result)
            assertEquals(0, checks)
        }
    }

    @Test
    fun `automatic core worker should wait for sibling incumbent discovery`() {
        val problem = Problem(2, 0, emptyArray(), emptyArray()).bake()
        val objective = LinearObjective(longArrayOf(1, 2))
        val workers = PortfolioBuilder.build(problem, PortfolioScenario.sequential(Kind.COP), objective)

        try {
            assertTrue(workers.single { it.label == "bt/core-guided" }.improvementOnly)
            assertTrue(workers.any { it.family == ArmFamily.Backtrack && !it.improvementOnly })
            assertTrue(workers.any { it.family == ArmFamily.LocalSearch && !it.improvementOnly })
        } finally {
            workers.forEach { it.close() }
        }
    }

    @Test
    fun `sole complete core worker should join initial admission`() {
        val problem = Problem(2, 0, emptyArray(), emptyArray()).bake()
        val objective = LinearObjective(longArrayOf(1, 2))
        val workers = PortfolioBuilder.build(problem, PortfolioScenario.sequential(Kind.COP, arms = 1), objective)

        try {
            assertFalse(workers.single { it.label == "bt/core-guided" }.improvementOnly)
        } finally {
            workers.forEach { it.close() }
        }
    }

    @Test
    fun `explicit core selection should join initial admission`() {
        val problem = Problem(2, 0, emptyArray(), emptyArray()).bake()
        val objective = LinearObjective(longArrayOf(1, 2))
        val scenario = PortfolioScenario(
            cores = 1,
            arms = 1,
            kind = Kind.COP,
            engine = EngineMix.BACKTRACK,
            btPool = listOf({ BacktrackCatalog.byLabel("core-guided") }),
        )
        val workers = PortfolioBuilder.build(problem, scenario, objective)

        try {
            assertFalse(workers.single().improvementOnly)
        } finally {
            workers.forEach { it.close() }
        }
    }

    @Test
    fun `a restricted public worker should not refute its feasible containing source`() {
        val problem = Problem(
            1,
            0,
            emptyArray(),
            arrayOf(Clause(intArrayOf(Lit.make(0, false)))),
        ).bake()
        val scenario = PortfolioScenario(
            cores = 1,
            arms = 1,
            kind = Kind.CSP,
            engine = EngineMix.BACKTRACK,
            btEdit = { it.copy(assumptions = Assumptions(bools = mapOf(0 to true))) },
        )
        val workers = PortfolioBuilder.build(problem, scenario)

        val result = Portfolio.thompson(workers).use { it.solve() }

        assertIs<SolveResult.Unknown>(result)
    }

    @Test
    fun `a portfolio local-search arm should report its phase times when requested`() {
        val problem = Problem(0, 0, emptyArray(), emptyArray()).bake()
        val scenario = PortfolioScenario(
            cores = 1,
            arms = 1,
            kind = Kind.COP,
            engine = EngineMix.LOCAL_SEARCH,
            reportLsResiduals = true,
            reportLsPhaseTimes = true,
        )
        val worker = PortfolioBuilder.build(problem, scenario, objective = LinearObjective()).single()

        worker.use {
            val result = it.improvements({ Double.POSITIVE_INFINITY }, Cancellation.Never, maxInstructions = 0).last()

            assertEquals(
                setOf("preparation", "seeding", "selection", "application", "other"),
                result.stats.ls.phaseNanos.keys,
            )
            assertEquals(0L, result.stats.ls.bestResidual?.cost)
        }
    }

    @Test
    fun `clause sharing should require another backtrack worker including replicas`() {
        val problem = Problem(0, 0, emptyArray(), emptyArray()).bake()
        for (cores in listOf(1, 2)) {
            val scenario = PortfolioScenario(cores, 1, Kind.CSP, EngineMix.BACKTRACK)

            val workers = PortfolioBuilder.build(problem, scenario)

            try {
                for (worker in workers) assertEquals(cores > 1, Signal.ClauseUses in worker.signals)
            } finally {
                workers.forEach { it.close() }
            }
        }
    }

    @Test
    fun `only added variants should wait for an incumbent in an expanded curated pool`() {
        val scenario = PortfolioScenario.sequential(Kind.COP, arms = 12)

        val problem = Problem(0, 1, arrayOf(IntDomain(0, 2)), emptyArray()).bake()
        val workers = PortfolioBuilder.build(problem, scenario)

        try {
            assertEquals(
                listOf(
                    "ls/cbls-chain/ils-basin",
                    "ls/cbls-chain-noinv/fixed",
                    "ls/cbls-notabu/fixed",
                    "ls/cbls-lonoise/fixed",
                ),
                workers.filter { it.improvementOnly && it.family == ArmFamily.LocalSearch }.map { it.label },
            )
            assertTrue(workers.filter { !it.improvementOnly }.any { it.label == "bt/satOptimized" })
        } finally {
            workers.forEach { it.close() }
        }
    }

    private val continuous = Problem(
        numBoolVars = 0,
        numIntVars = 1,
        intDomains = arrayOf(IntDomain(0, 5)),
        factors = arrayOf<Factor>(
            Linear(longArrayOf(1L), intArrayOf(0), doubleArrayOf(1.0), intArrayOf(0), LinearOp.GE, 2L),
        ),
        numRealVars = 1,
        realLower = doubleArrayOf(0.0),
        realUpper = doubleArrayOf(3.0),
    ).bake()

    @Test
    fun `the default continuous optimization pool should append node LP after its incumbent workers`() {
        val scenario = PortfolioScenario.sequential(Kind.COP)

        val workers = PortfolioBuilder.build(
            continuous,
            scenario,
            objective = LinearObjective(realCoefficients = doubleArrayOf(1.0)),
        )

        try {
            assertEquals(
                listOf("bt/satOptimized", "bt/conflictDriven", "bt/lp-default"),
                workers.filter { it.label.startsWith("bt/") }.map { it.label },
            )
            assertEquals(4, workers.count { it.family == ArmFamily.LocalSearch })
            assertEquals("bt/lp-default", workers.last().label)
            assertFalse(workers.last().improvementOnly)
        } finally {
            workers.forEach { it.close() }
        }
    }

    @Test
    fun `a retained LP worker should search before a finite objective incumbent`() {
        val scenario = PortfolioScenario.sequential(Kind.COP)
        val workers = PortfolioBuilder.build(
            continuous,
            scenario,
            objective = LinearObjective(intCoefficients = longArrayOf(1L)),
        )

        try {
            assertEquals("bt/lp-default", workers.last().label)
            assertFalse(workers.last().improvementOnly)
        } finally {
            workers.forEach { it.close() }
        }
    }

    @Test
    fun `a disabled bounding ceiling should keep the continuous incumbent pool`() {
        val ceilings = listOf(LpConfig.OFF, LpConfig(overrides = mapOf(LpTechnique.BOUNDING to false)))

        for (ceiling in ceilings) {
            val scenario = PortfolioScenario.sequential(Kind.COP).copy(lpCeiling = ceiling)
            val workers = PortfolioBuilder.build(continuous, scenario)

            try {
                assertEquals(
                    listOf("bt/satOptimized", "bt/conflictDriven"),
                    workers.filter { it.label.startsWith("bt/") }.map { it.label },
                )
                assertEquals(4, workers.count { it.family == ArmFamily.LocalSearch })
            } finally {
                workers.forEach { it.close() }
            }
        }
    }

    @Test
    fun `a mixed portfolio over continuous variables should build local-search arms`() {
        val scenario = PortfolioScenario(cores = 1, arms = 4, kind = Kind.COP, engine = EngineMix.MIXED)

        val workers = PortfolioBuilder.build(continuous, scenario)

        assertTrue(workers.any { it.label.startsWith("ls/") }, workers.map { it.label }.toString())
    }

    @Test
    fun `a local-search arm over continuous columns should report certified real values`() {
        val scenario = PortfolioScenario(cores = 1, arms = 1, kind = Kind.CSP, engine = EngineMix.LOCAL_SEARCH)
        val worker = PortfolioBuilder.build(continuous, scenario).single()

        val result = worker.solve(Cancellation.Never, maxInstructions = 10_000)

        assertNotNull(assertIs<SolveResult.Sat>(result).assignment.exactReals)
    }
}
