package com.eignex.klause.presolve

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.ir.SourceFormat
import com.eignex.klause.ir.SourceSemantics
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.meta.coreguided.CoreGuidedSearch
import com.eignex.klause.portfolio.Kind
import com.eignex.klause.portfolio.PortfolioBuilder
import com.eignex.klause.portfolio.PortfolioScenario
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.pipeline.ExecutionOperation
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.FiniteSolveOutcome
import com.eignex.klause.solver.pipeline.FiniteSolveVerdict
import com.eignex.klause.solver.pipeline.SourceAttachment
import com.eignex.klause.solver.pipeline.SourceExecutionPolicy
import com.eignex.klause.solver.pipeline.SourceExecutionRequest
import com.eignex.klause.solver.pipeline.SourceExecutionResult
import com.eignex.klause.solver.pipeline.SourceSetup
import com.eignex.klause.solver.pipeline.SourceSetupRequest
import com.eignex.klause.solver.pipeline.execute
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BinaryObjectiveViewTest {
    @Test
    fun `objective symmetry should consume substituted signed Boolean costs`() {
        val source = Problem(
            0, 3, Array(3) { IntDomain(0, 1) },
            listOf(Linear(longArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.GE, 1)),
        )
        val original = LinearObjective(intCoefficients = longArrayOf(-3, -3, -3), constant = 7)
        val config = PresolveConfig.parse("off,+binary-columns,+objective-symmetry")

        val prepared = PresolvePipeline.run(source, original, config, false)
        val objective = checkNotNull(prepared.objective)
        val representatives = BacktrackSolver(prepared.problem.bake()).enumerate().toList()

        assertTrue(objective.boolWeights.any { it != 0L })
        assertEquals(3, representatives.map { prepared.reconstruct(it).ints.toList() }.toSet().size)
        for (sample in representatives) prepared.mapping.requireObjectivePreserved(original, objective, sample)
    }

    @Test
    fun `signed channels should preserve costs without adding literals`() {
        for (bound in listOf(0L, 1L)) {
            val problem = Problem(
                1, 1, arrayOf(IntDomain(0, 1)),
                listOf(ReifiedLinear(0, longArrayOf(1), intArrayOf(0), LinearOp.EQ, bound)),
            ).bake()
            val original = LinearObjective(longArrayOf(7), longArrayOf(-3), constant = 11)

            val view = checkNotNull(BinaryObjectiveView.create(problem, original))

            assertEquals(1, view.numBoolVars)
            assertEquals(1, view.factors.size)
            for (value in 0L..1L) {
                val sample = Sample(booleanArrayOf(value == bound), longArrayOf(value))
                assertEquals(original.evaluateExact(sample), view.objective.evaluateExact(sample))
            }
        }
    }

    @Test
    fun `fixed binary costs should enter the constant exactly`() {
        val problem = Problem(0, 2, arrayOf(IntDomain(0, 0), IntDomain(1, 1)), emptyList()).bake()
        val original = LinearObjective(intCoefficients = longArrayOf(9, -4), constant = 12)

        val view = checkNotNull(BinaryObjectiveView.create(problem, original))

        assertEquals(8L, view.objective.constant)
        assertEquals(0, view.numBoolVars)
        assertTrue(view.objective.intCoefficients.all { it == 0L })
    }

    @Test
    fun `overflowing signed reuse should retain exact costs through a fresh channel`() {
        val problem = Problem(
            1, 1, arrayOf(IntDomain(0, 1)),
            listOf(ReifiedLinear(0, longArrayOf(1), intArrayOf(0), LinearOp.EQ, 0)),
        ).bake()
        val objectives = listOf(
            LinearObjective(intCoefficients = longArrayOf(Long.MIN_VALUE)),
            LinearObjective(longArrayOf(Long.MAX_VALUE), longArrayOf(-1)),
            LinearObjective(intCoefficients = longArrayOf(1), constant = Long.MAX_VALUE),
        )

        for (objective in objectives) {
            val view = checkNotNull(BinaryObjectiveView.create(problem, objective))
            assertEquals(2, view.numBoolVars)
            for (value in 0L..1L) {
                val sample = Sample(booleanArrayOf(value == 0L, value == 1L), longArrayOf(value))
                assertEquals(objective.evaluateExact(sample), view.objective.evaluateExact(sample))
            }
        }
    }

    @Test
    fun `a fixed cost beyond constant storage should remain an exact Boolean term`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(1, 1)), emptyList()).bake()
        val original = LinearObjective(intCoefficients = longArrayOf(1), constant = Long.MAX_VALUE)

        val view = checkNotNull(BinaryObjectiveView.create(problem, original))
        val sample = Sample(booleanArrayOf(true), longArrayOf(1))

        assertEquals(1, view.numBoolVars)
        assertEquals(original.evaluateExact(sample), view.objective.evaluateExact(sample))
    }

    @Test
    fun `continuous models without a Boolean consumer should retain their objective form`() {
        val problem = Problem(
            0, 1, arrayOf(IntDomain(0, 1)), emptyList(),
            numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0),
        ).bake()

        val view = BinaryObjectiveView.create(problem, LinearObjective(intCoefficients = longArrayOf(1)))

        assertNull(view)
    }

    @Test
    fun `a nonbinary cost should retain the integer objective`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 2)), emptyList()).bake()

        val view = BinaryObjectiveView.create(problem, LinearObjective(intCoefficients = longArrayOf(1)))

        assertNull(view)
    }

    @Test
    fun `mixed integer readers should admit the core worker and preserve source costs`() {
        val original = LinearObjective(intCoefficients = longArrayOf(3, 5), constant = -2)
        val source = Problem(
            0, 3, arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 2)),
            listOf(Linear(longArrayOf(2, 2, 1), intArrayOf(0, 1, 2), LinearOp.GE, 3)),
        )
        val config = PresolveConfig(overrides = PresolvePass.entries.associateWith {
            it == PresolvePass.SUBSTITUTE_BINARY_COLUMNS
        })
        val outcome = PresolvePipeline.run(source, original, config, false)
        val objective = checkNotNull(outcome.objective)
        val problem = outcome.problem.bake()
        val workers = PortfolioBuilder.build(problem, PortfolioScenario.sequential(Kind.COP), objective)
        try {
            assertTrue(workers.any { it.label == "bt/core-guided" })
        } finally {
            workers.forEach { it.close() }
        }
        val core = CoreGuidedSearch(problem, objective, BacktrackParams())
        val result = try {
            assertIs<MinimizeResult.Optimal>(core.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) {})
        } finally {
            core.close()
        }
        val sample = outcome.reconstruct(result.sample)

        assertEquals(0, sample.numBoolVars)
        assertEquals(1L, original.evaluateLong(sample))
        assertEquals(original.evaluateExact(sample), objective.evaluateExact(result.sample))
        assertTrue(result.stats.coreGuided.cores > 0)
        assertTrue(2 * sample.intValue(0) + 2 * sample.intValue(1) + sample.intValue(2) >= 3)
    }

    @Test
    fun `approximate and solution sensitive invocations should retain integer costs`() {
        val original = LinearObjective(intCoefficients = longArrayOf(3, 5))
        val source = Problem(0, 2, Array(2) { IntDomain(0, 1) }, emptyList())
        val approximate = source.withSourceSemantics(SourceSemantics(
            SourceFormat.IR,
            feasibilityTolerance = SourceTolerance(absolute = SemanticRational.ofLong(1)),
        ).resolve())
        val config = PresolveConfig(overrides = PresolvePass.entries.associateWith {
            it == PresolvePass.SUBSTITUTE_BINARY_COLUMNS
        })
        for ((problem, sensitive) in listOf(approximate to false, source to true)) {
            val outcome = PresolvePipeline.run(problem, original, config, sensitive)
            assertEquals(original, outcome.objective ?: original)
            assertEquals(0, outcome.problem.numBoolVars)
        }
    }

    @Test
    fun `Boolean cutoffs should preserve mixed integer feasibility and signed costs`() {
        val source = Problem(
            0, 3, arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 2)),
            listOf(Linear(longArrayOf(2, 2, 1), intArrayOf(0, 1, 2), LinearOp.GE, 3)),
        ).bake()
        val original = LinearObjective(intCoefficients = longArrayOf(-3, 5), constant = 7)
        val transformed = checkNotNull(BinaryColumnSubstitution.substitute(
            source, setOf(0, 1), BakeConfig.NONE, original,
        ))
        val objective = checkNotNull(transformed.objective)

        val result = assertIs<MinimizeResult.Optimal>(BacktrackSolver(transformed.problem).minimize(
            objective, BacktrackParams(nativeSat = false, pbObjectiveCutoff = true),
        ))
        val sample = transformed.reconstruct(result.sample)

        assertEquals(4L, original.evaluateLong(sample))
        assertTrue(2 * sample.intValue(0) + 2 * sample.intValue(1) + sample.intValue(2) >= 3)
    }

    @Test
    fun `shared opposite channels should coalesce their signed costs exactly`() {
        val problem = Problem(
            1, 2, Array(2) { IntDomain(0, 1) },
            listOf(
                ReifiedLinear(0, longArrayOf(1), intArrayOf(0), LinearOp.EQ, 1),
                ReifiedLinear(0, longArrayOf(1), intArrayOf(1), LinearOp.EQ, 0),
            ),
        ).bake()
        val original = LinearObjective(longArrayOf(7), longArrayOf(3, 5), constant = 11)

        val view = checkNotNull(BinaryObjectiveView.create(problem, original))

        assertEquals(listOf(5L), view.objective.boolWeights.toList())
        assertEquals(16L, view.objective.constant)
        for (value in 0L..1L) {
            val sample = Sample(booleanArrayOf(value == 1L), longArrayOf(value, 1L - value))
            assertEquals(original.evaluateExact(sample), view.objective.evaluateExact(sample))
        }
    }

    @Test
    fun `source execution should retain original objective direction and coordinates`() {
        val source = Problem(
            0, 2, Array(2) { IntDomain(0, 1) },
            listOf(Linear(longArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1)),
        )
        val objective = LinearObjective(intCoefficients = longArrayOf(-3, 5), constant = 7)
        val config = PresolveConfig(overrides = PresolvePass.entries.associateWith {
            it == PresolvePass.SUBSTITUTE_BINARY_COLUMNS
        })
        for (maximize in listOf(false, true)) {
            val invocation = SourceSetup.prepare(SourceSetupRequest(
                SourceAttachment(source), ExecutionOperation.OPTIMIZE, FiniteEngine.BACKTRACK, objective, maximize,
            ))
            val policy = SourceExecutionPolicy(portfolioArms = 1, explicitPresolve = true)

            val result = assertIs<SourceExecutionResult.Finite>(SourceSetup.execute(SourceExecutionRequest(
                invocation, presolveConfig = config,
                finiteRequest = { shape -> policy.finiteRequest(shape, FiniteEngine.BACKTRACK, config) },
            )))
            val optimum = assertIs<FiniteSolveOutcome.Completed>(result.result.outcome)
            val sample = checkNotNull(optimum.bestSample)

            assertEquals(FiniteSolveVerdict.OPTIMAL, optimum.verdict)
            assertEquals(if (maximize) 12L else 4L, objective.evaluateLong(sample))
            assertEquals(0, sample.numBoolVars)
            assertEquals(2, sample.numIntVars)
            val plan = checkNotNull(result.result.preparation.executionPlan)
            assertEquals(objective, plan.objective)
            assertEquals(maximize, plan.maximize)
        }
    }

    @Test
    fun `objective views should retain scoped Boolean root pins across either rebuild`() {
        for (mixed in listOf(false, true)) {
            val factors = if (mixed) {
                listOf(Linear(longArrayOf(2, 1), intArrayOf(0, 1), LinearOp.GE, 1))
            } else {
                emptyList()
            }
            val source = BakedProblem(
                numBoolVars = 1, numIntVars = 2,
                intDomains = arrayOf(IntDomain(0, 1), IntDomain(0, 2)), factors = factors,
                seedDeductions = PropagationResult.Implied(
                    intArrayOf(0), booleanArrayOf(true), intArrayOf(), longArrayOf(),
                ),
            )
            val original = LinearObjective(longArrayOf(5), longArrayOf(3))
            val transformed = checkNotNull(BinaryColumnSubstitution.substitute(
                source, setOf(0), BakeConfig.NONE, original,
            ))

            val result = assertIs<MinimizeResult.Optimal>(BacktrackSolver(transformed.problem).minimize(
                checkNotNull(transformed.objective),
            ))
            val restored = transformed.reconstruct(result.sample)

            assertTrue(restored.boolValue(0))
            assertEquals(5L, original.evaluateLong(restored))
        }
    }

    @Test
    fun `a clausal binary cost view should execute ordinary LP bounding`() {
        val source = Problem(
            0, 2, Array(2) { IntDomain(0, 1) },
            listOf(Linear(longArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1)),
        )
        val original = LinearObjective(intCoefficients = longArrayOf(1, 2))
        val config = PresolveConfig(overrides = PresolvePass.entries.associateWith {
            it == PresolvePass.SUBSTITUTE_BINARY_COLUMNS
        })
        val prepared = PresolvePipeline.run(source, original, config, false)
        val objective = checkNotNull(prepared.objective)

        val result = assertIs<MinimizeResult.Optimal>(BacktrackSolver(prepared.problem.bake()).minimize(
            objective, BacktrackParams(lpConfig = LpConfig.AGGRESSIVE),
        ))

        assertEquals(1L, original.evaluateLong(prepared.reconstruct(result.sample)))
        assertTrue(result.stats.lp.rootPasses.sum > 0.0)
    }

    @Test
    fun `substituted objective columns should reconstruct every original cost`() {
        val source = Problem(
            0, 2, Array(2) { IntDomain(0, 1) },
            listOf(Linear(longArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1)),
        ).bake()
        val original = LinearObjective(intCoefficients = longArrayOf(-3, 5), constant = 7)

        val transformed = checkNotNull(BinaryColumnSubstitution.substitute(
            source, setOf(0, 1), BakeConfig.NONE, original,
        ))
        val objective = checkNotNull(transformed.objective)
        val samples = BacktrackSolver(transformed.problem).enumerate().toList()

        assertEquals(2, transformed.columns)
        assertEquals(3, samples.size)
        for (sample in samples) {
            val restored = transformed.reconstruct(sample)
            assertEquals(0, restored.numBoolVars)
            assertEquals(original.evaluateExact(restored), objective.evaluateExact(sample))
            assertTrue(restored.intValue(0) + restored.intValue(1) >= 1)
        }
    }
}
