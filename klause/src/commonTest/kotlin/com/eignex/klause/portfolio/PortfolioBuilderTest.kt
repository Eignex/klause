package com.eignex.klause.portfolio

import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.backtrack.toBacktrackParams
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.circuit.Circuit
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
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.ProblemProfile
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.objective.LinearObjective
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
    fun `an auxiliary LP worker should wait for a finite objective incumbent`() {
        val scenario = PortfolioScenario.sequential(Kind.COP)
        val workers = PortfolioBuilder.build(
            continuous,
            scenario,
            objective = LinearObjective(intCoefficients = longArrayOf(1L)),
        )

        try {
            assertEquals(listOf("bt/lp-default"), workers.filter { it.improvementOnly }.map { it.label })
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
