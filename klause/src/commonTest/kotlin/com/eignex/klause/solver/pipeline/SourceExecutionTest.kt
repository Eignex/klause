package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearSourceProblem
import com.eignex.klause.ir.LinearSourceVariable
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.ProblemSettings
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.lp.engine.LpZeroObjectivePricing
import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.presolve.PresolvePass
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.PresolvePreparationStage
import com.eignex.klause.solver.result.QuadraticOptimizationResult
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.theory.BuiltInEncoderProvider
import com.eignex.klause.theory.Encoder
import com.eignex.klause.theory.EncoderProvider
import com.eignex.klause.theory.EncoderSupport
import com.eignex.klause.util.Bits
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration

class SourceExecutionTest {
    @Test
    fun `polynomial source encoder rebuilding should retain its containing work`() {
        val source = Problem(2, 0, emptyArray(), arrayOf(
            Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
            Clause(intArrayOf(Lit.make(0, true), Lit.make(1, false))),
            ContinuousProduct(0, 0, 1),
        ), numRealVars = 2, realLower = doubleArrayOf(1.0, 1.0), realUpper = doubleArrayOf(1.0, 1.0))
        val original = source.pipelineRequest()
        val budget = PresolveBudget(1_000_000)
        val preparation = original.prepareSource(
            PresolveConfig.parse("off,+bve"), null, Cancellation.Never, Cancellation.Never, budget,
        )

        val prepared = original.afterSourcePreparation(preparation, Cancellation.Never)

        assertNotNull(prepared)
        assertTrue(preparation.changed)
        val stage = budget.preparationStages.single { it.stage == PresolvePreparationStage.SOURCE_ENCODER }
        assertTrue(stage.nativeWork > 0L)
        assertEquals(0L, stage.work)
    }

    @Test
    fun `polynomial source probing should reduce Booleans beside a nonlinear real factor`() {
        val source = Problem(2, 0, emptyArray(), arrayOf(
            Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
            Clause(intArrayOf(Lit.make(0, true), Lit.make(1, false))),
            ContinuousProduct(0, 0, 1),
        ), numRealVars = 2, realLower = doubleArrayOf(1.0, 1.0), realUpper = doubleArrayOf(1.0, 1.0))
        val invocation = SourceSetup.prepare(SourceSetupRequest(
            SourceAttachment(source), ExecutionOperation.SATISFY, FiniteEngine.BACKTRACK,
        ))
        val budget = PresolveBudget(1_000_000)

        val result = assertIs<SourceExecutionResult.Polynomial>(SourceSetup.execute(SourceExecutionRequest(
            invocation, presolveConfig = PresolveConfig.parse("off,+probe-open"), presolveBudget = budget,
        )))

        assertTrue(assertIs<OpenTheoryResult.Sat>(result.result.result).assignment.boolValue(0))
        val stats = assertNotNull(result.result.result.stats.presolve)
        assertTrue(PresolvePass.PROBE_OPEN_RANGES.id in stats.passes)
        val effort = assertNotNull(stats.effort)
        assertEquals(budget.spent(), effort.work)
        assertEquals(budget.passCalls, effort.passCalls)
        assertTrue(assertNotNull(effort.rounds) > 0)
    }

    @Test
    fun `polynomial source rewrites should reconstruct the original Boolean assignment`() {
        val source = Problem(2, 0, emptyArray(), arrayOf(
            Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
            Clause(intArrayOf(Lit.make(0, true), Lit.make(1, false))),
            ContinuousProduct(0, 0, 1),
        ), numRealVars = 2, realLower = doubleArrayOf(1.0, 1.0), realUpper = doubleArrayOf(1.0, 1.0))
        val invocation = SourceSetup.prepare(SourceSetupRequest(
            SourceAttachment(source), ExecutionOperation.SATISFY, FiniteEngine.BACKTRACK,
        ))

        val result = assertIs<SourceExecutionResult.Polynomial>(SourceSetup.execute(SourceExecutionRequest(
            invocation, presolveConfig = PresolveConfig.parse("off,+bve"),
        )))

        val point = assertIs<OpenTheoryResult.Sat>(result.result.result).assignment
        assertTrue(point.boolValue(0))
        assertEquals(BigFraction.ONE, assertNotNull(point.algebraicRealValue(0)).rational)
        assertTrue(assertNotNull(result.preparationStats).constraintsRemoved > 0)
    }

    @Test
    fun `polynomial optimization should publish reconstructed source incumbents`() {
        val source = Problem(2, 0, emptyArray(), arrayOf(
            Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
            Clause(intArrayOf(Lit.make(0, true), Lit.make(1, false))),
            ContinuousProduct(0, 0, 1),
        ), numRealVars = 2, realLower = doubleArrayOf(1.0, 1.0), realUpper = doubleArrayOf(1.0, 1.0))
        val invocation = SourceSetup.prepare(SourceSetupRequest(
            SourceAttachment(source), ExecutionOperation.OPTIMIZE, FiniteEngine.BACKTRACK,
            objective = LinearObjective(boolWeights = longArrayOf(0, 1)), maximize = true,
        ))
        val points = mutableListOf<Sample>()

        val result = assertIs<SourceExecutionResult.Quadratic>(SourceSetup.execute(SourceExecutionRequest(
            invocation, presolveConfig = PresolveConfig.parse("off,+bve"),
        ), SourceExecutionCallbacks(onQuadraticIncumbent = { points += it.incumbent.sample })))

        val optimum = assertIs<QuadraticOptimizationResult.Optimal>(result.result.result)
        assertTrue(optimum.incumbent.sample.boolValue(0))
        assertTrue(optimum.incumbent.sample.boolValue(1))
        assertTrue(points.isNotEmpty())
        assertTrue(points.all { it.boolValue(0) })
    }

    @Test
    fun `reconstructed polynomial witness checking should spend the containing native allowance`() {
        val source = Problem(2, 0, emptyArray(), arrayOf(
            Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
            Clause(intArrayOf(Lit.make(0, true), Lit.make(1, false))),
            ContinuousProduct(0, 0, 1),
        ), numRealVars = 2, realLower = doubleArrayOf(1.0, 1.0), realUpper = doubleArrayOf(1.0, 1.0))
        val original = source.pipelineRequest()
        val preparation = original.prepareSource(PresolveConfig.parse("off,+bve"), null,
            Cancellation.Never, Cancellation.Never, null)
        val prepared = assertNotNull(original.afterSourcePreparation(preparation, Cancellation.Never))
        val sample = Sample(booleanArrayOf(false, false), LongArray(0), doubleArrayOf(1.0, 1.0),
            exactReals = listOf(BigFraction.ONE, BigFraction.ONE))
        val params = PolynomialTheoryParams(maximumWork = 1)
        val native = QuadraticNativeScope(params)

        val reconstructed = prepared.reconstructSource(sample, params, native.polynomial)

        assertTrue(preparation.changed)
        assertNull(reconstructed)
        assertTrue(native.work > 0)
        assertTrue(native.exhausted)
        assertEquals(TerminationReason.BudgetExhausted, params.sourceVerificationReason(native.polynomial))
    }

    @Test
    fun `open source execution should retain an exhausted preparation allowance`() {
        val invocation = SourceSetup.prepare(SourceSetupRequest(
            SmtLib.parse("(declare-const x Real) (assert (> x 0))").source,
            ExecutionOperation.SATISFY, FiniteEngine.BACKTRACK,
        ))
        val budget = PresolveBudget(3).apply { charge(3) }

        val result = assertIs<SourceExecutionResult.Linear>(SourceSetup.execute(SourceExecutionRequest(
            invocation, presolveBudget = budget,
        )))

        val outcome = assertIs<OpenTheoryExecution.Satisfy>(result.result).result
        assertIs<OpenTheoryResult.Sat>(outcome)
        val effort = assertNotNull(assertNotNull(outcome.stats.presolve).effort)
        assertEquals(3L, effort.work)
        assertEquals(3L, effort.allowance)
        assertEquals(0, effort.rounds)
        assertTrue(effort.passCalls.isEmpty())
    }

    @Test
    fun `fixed source execution should reject parallel cores across source families`() {
        val sources = listOf(
            SourceExecutionKind.FINITE to "(declare-const b Bool) (assert b)",
            SourceExecutionKind.LINEAR to "(declare-const x Real) (assert (> x 0))",
            SourceExecutionKind.POLYNOMIAL to "(declare-const x Real) (assert (= (* x x) 2))",
            SourceExecutionKind.NONLINEAR_INTEGER to "(declare-const x Int) (assert (= (* x x) 2))",
            SourceExecutionKind.FUNCTIONS to "(declare-const x Real) (assert (= (sin x) 0))",
        ).map { (kind, text) -> kind to SmtLib.parse(text).source } +
            (SourceExecutionKind.NUMERICAL to SourceAttachment(
                LinearSourceProblem(listOf(LinearSourceVariable("x", true)), emptyList()), ProblemSettings(),
            ))
        for ((kind, source) in sources) {
            val invocation = SourceSetup.prepare(SourceSetupRequest(
                source, ExecutionOperation.SATISFY, FiniteEngine.FIXED,
            ))
            assertEquals(kind, SourceSetup.executionKind(invocation))

            val failure = assertFailsWith<PipelineConfigException> {
                SourceExecutionRequest(invocation, cores = 2)
            }

            assertTrue("single-core" in assertNotNull(failure.message))
        }
    }

    @Test
    fun `finite optimization should retain native interruption after another invocation completes`() {
        val source = Problem(2, 0, emptyArray(), arrayOf(Clause(
            intArrayOf(Lit.make(0, true), Lit.make(1, true)),
        )))
        val attachment = SourceAttachment(source)
        val objective = LinearObjective(boolWeights = longArrayOf(1, 2), constant = 7)
        val stopped = SourceSetup.prepare(SourceSetupRequest(
            attachment, ExecutionOperation.OPTIMIZE, FiniteEngine.BACKTRACK, objective,
        ))
        val completed = SourceSetup.prepare(SourceSetupRequest(
            attachment, ExecutionOperation.OPTIMIZE, FiniteEngine.BACKTRACK, objective,
        ))

        val first = assertIs<SourceExecutionResult.Finite>(SourceSetup.execute(SourceExecutionRequest(
            stopped, finiteRequest = { shape -> FiniteSolveRequest(shape, SourceFiniteControls.Backtrack(
                BacktrackParams(maxDecisions = 0L, nativeSat = false),
            )) },
        )))
        val second = assertIs<SourceExecutionResult.Finite>(SourceSetup.execute(SourceExecutionRequest(
            completed, finiteRequest = { shape -> FiniteSolveRequest(shape, SourceFiniteControls.Backtrack(
                BacktrackParams(),
            )) },
        )))

        val retained = assertIs<MinimizeResult.BestFound>(first.result.nativeSourceMinimum)
        assertEquals(TerminationReason.BudgetExhausted, retained.reason)
        val outcome = assertIs<FiniteSolveOutcome.Completed>(first.result.outcome)
        assertEquals(FiniteSolveVerdict.BEST_FOUND, outcome.verdict)
        assertEquals(retained.sample, outcome.bestSample)
        assertIs<MinimizeResult.Optimal>(second.result.nativeSourceMinimum)
        assertEquals(FiniteSolveVerdict.OPTIMAL, assertIs<FiniteSolveOutcome.Completed>(second.result.outcome).verdict)
        assertSame(stopped, first.invocation)
        assertSame(completed, second.invocation)
    }

    @Test
    fun `finite dispatch should retain source publication and native outcome`() {
        val source = Problem(0, 1, arrayOf(IntDomain(2, 2)), emptyArray())
        val invocation = SourceSetup.prepare(
            SourceSetupRequest(
                SourceAttachment(source),
                ExecutionOperation.SATISFY,
                FiniteEngine.MIXED,
            ),
        )
        val samples = mutableListOf<Sample>()

        val result = assertIs<SourceExecutionResult.Finite>(
            SourceSetup.execute(
                SourceExecutionRequest(invocation, finiteRequest = { shape ->
                    FiniteSolveRequest(
                        shape, FiniteEngine.MIXED, PresolveConfig.NONE, false, false, Cancellation.Never,
                        presolveBudget = null, cores = 1, executionOptions = ExecutionOptions.parse(emptyList()),
                        randomSeed = 17, defaultArms = 1, lpConfig = LpConfig.OFF,
                        zeroObjectivePricing = LpZeroObjectivePricing.MIN_BOUND_SUPPORT, nodeBudget = null,
                        solveBudgetMillis = null,
                        allSolutions = false,
                        solutionCap = null,
                        deadlineExceeded = { false },
                        onEvent = null, onPortfolioEvent = null,
                    )
                }),
                SourceExecutionCallbacks(finite = FiniteSolveCallbacks(samples::add, { samples += it.sample })),
            ),
        )

        val outcome = assertIs<FiniteSolveOutcome.Completed>(result.result.outcome)
        assertEquals(FiniteSolveVerdict.SAT, outcome.verdict)
        assertEquals(1L, outcome.solutions)
        assertEquals(2L, samples.last().intValue(0))
        assertSame(source.semantics, result.semantics)
        assertSame(invocation, result.invocation)
    }

    @Test
    fun `linear dispatch should retain exact source values and accepted publications`() {
        val source = Problem(
            0,
            0,
            emptyArray(),
            arrayOf(
                Linear(
                    intVars = intArrayOf(),
                    intCoeffs = doubleArrayOf(),
                    realVars = intArrayOf(0),
                    realCoeffs = doubleArrayOf(3.0),
                    op = LinearOp.EQ,
                    bound = 1.0,
                ),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(1.0),
        )
        val objective = LinearObjective(realCoefficients = doubleArrayOf(1.0), constant = 7)
        val invocation = SourceSetup.prepare(
            SourceSetupRequest(
                SourceAttachment(source),
                ExecutionOperation.OPTIMIZE,
                FiniteEngine.BACKTRACK,
                objective,
            ),
        )
        val values = mutableListOf<SourceValue>()

        val result = assertIs<SourceExecutionResult.Linear>(
            SourceSetup.execute(
                SourceExecutionRequest(
                    invocation,
                    finiteRequest = { error("an unused finite consumer was constructed") },
                ),
                SourceExecutionCallbacks(onLinearIncumbent = { values += it.value }),
            ),
        )

        val native = assertIs<OpenTheoryExecution.Optimize>(result.result)
        val optimum = assertIs<OpenTheoryOptimum.Optimal>(native.result)
        val expected = BigFraction.ofLong(22) * BigFraction.ofLong(3).reciprocal()
        assertEquals(expected, optimum.value)
        assertEquals(expected, assertIs<SourceValue.Rational>(values.last()).value)
        assertNotNull(native.acceptedIncumbent)
        assertSame(source.semantics, result.semantics)
    }

    @Test
    fun `polynomial dispatch should retain its native completion and resource telemetry`() {
        val source = Problem(
            0,
            0,
            emptyArray(),
            arrayOf(ContinuousProduct(0, 0, 1)),
            numRealVars = 2,
            realLower = doubleArrayOf(1.0, 1.0),
            realUpper = doubleArrayOf(1.0, 1.0),
        )
        val invocation = SourceSetup.prepare(
            SourceSetupRequest(
                SourceAttachment(source),
                ExecutionOperation.SATISFY,
                FiniteEngine.BACKTRACK,
            ),
        )

        val result = assertIs<SourceExecutionResult.Polynomial>(SourceSetup.execute(SourceExecutionRequest(invocation)))

        val point = assertIs<OpenTheoryResult.Sat>(result.result.result).assignment
        assertEquals(BigFraction.ONE, assertNotNull(point.algebraicRealValue(0)).rational)
        assertEquals(BigFraction.ONE, assertNotNull(point.algebraicRealValue(1)).rational)
        assertTrue(result.result.stats.workSpent > 0)
        assertSame(source.semantics, result.result.result.semantics)
    }

    @Test
    fun `polynomial maximization should retain original objective direction and native optimum`() {
        val source = Problem(
            0,
            0,
            emptyArray(),
            arrayOf(ContinuousProduct(0, 0, 1)),
            numRealVars = 2,
            realLower = doubleArrayOf(1.0, 1.0),
            realUpper = doubleArrayOf(1.0, 1.0),
        )
        val objective = LinearObjective(realCoefficients = doubleArrayOf(0.0, 1.0), constant = 7)
        val invocation = SourceSetup.prepare(
            SourceSetupRequest(
                SourceAttachment(source),
                ExecutionOperation.OPTIMIZE,
                FiniteEngine.BACKTRACK,
                objective,
                maximize = true,
            ),
        )

        val result = assertIs<SourceExecutionResult.Quadratic>(SourceSetup.execute(SourceExecutionRequest(invocation)))

        val optimum = assertIs<QuadraticOptimizationResult.Optimal>(result.result.result)
        assertEquals(ObjectiveSense.MAXIMIZE, optimum.sense)
        assertEquals(BigFraction.ofLong(8), optimum.incumbent.value.rational)
        assertSame(objective, result.invocation.sourceObjective)
    }

    @Test
    fun `a stopped source invocation should not resume through fresh execution controls`() {
        val source = Problem(
            0,
            0,
            emptyArray(),
            emptyArray(),
            numRealVars = 1,
            realLower = doubleArrayOf(1.0),
            realUpper = doubleArrayOf(1.0),
        )
        var stopped = false
        val invocation = SourceSetup.prepare(
            SourceSetupRequest(
                SourceAttachment(source),
                ExecutionOperation.SATISFY,
                FiniteEngine.BACKTRACK,
                cancellation = Cancellation { stopped },
            ),
        )
        stopped = true

        val first = assertIs<SourceExecutionResult.Linear>(SourceSetup.execute(SourceExecutionRequest(invocation)))
        stopped = false
        val second = assertIs<SourceExecutionResult.Linear>(SourceSetup.execute(SourceExecutionRequest(invocation)))

        for (result in listOf(first, second)) {
            val native = assertIs<OpenTheoryExecution.Satisfy>(result.result)
            assertEquals(TerminationReason.Cancelled, assertIs<OpenTheoryResult.Unknown>(native.result).reason)
            assertSame(source.semantics, result.semantics)
        }
    }

    @Test
    fun `nonfinite enumeration should reject before constructing an execution consumer`() {
        val source = Problem(
            0,
            0,
            emptyArray(),
            emptyArray(),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(1.0),
        )
        val invocation = SourceSetup.prepare(
            SourceSetupRequest(
                SourceAttachment(source),
                ExecutionOperation.ENUMERATE,
                FiniteEngine.MIXED,
            ),
        )

        assertFailsWith<PipelineConfigException> {
            SourceSetup.execute(
                SourceExecutionRequest(
                    invocation,
                    finiteRequest = { error("an incompatible finite consumer was constructed") },
                ),
            )
        }
    }

    @Test
    fun `finite dispatch should reject a request bound to another source`() {
        val source = Problem(0, 1, arrayOf(IntDomain(2, 2)), emptyArray())
        val first = SourceSetup.prepare(
            SourceSetupRequest(
                SourceAttachment(source),
                ExecutionOperation.SATISFY,
                FiniteEngine.FIXED,
            ),
        )
        val second = SourceSetup.prepare(
            SourceSetupRequest(
                SourceAttachment(source),
                ExecutionOperation.SATISFY,
                FiniteEngine.FIXED,
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            SourceSetup.execute(
                SourceExecutionRequest(first, finiteRequest = {
                    FiniteSolveRequest(
                        second.finiteShape(), FiniteEngine.FIXED, PresolveConfig.NONE,
                        false, false, Cancellation.Never, presolveBudget = null, cores = 1,
                        executionOptions = ExecutionOptions.parse(emptyList()), randomSeed = 17,
                        defaultArms = 1, lpConfig = LpConfig.OFF,
                        zeroObjectivePricing = LpZeroObjectivePricing.MIN_BOUND_SUPPORT, nodeBudget = null,
                        solveBudgetMillis = null, allSolutions = false, solutionCap = null,
                        deadlineExceeded = { false }, onEvent = null, onPortfolioEvent = null,
                    )
                }),
            )
        }
    }

    @Test
    fun `a refused complete route should retain its source checked heuristic witness`() {
        val source = Problem(
            0,
            2,
            arrayOf(IntDomain(0, 0), IntDomain(0, 2)),
            arrayOf(AllDifferent(intArrayOf(0, 1), 0, 3)),
            openIntHi = booleanArrayOf(false, true),
        )
        val invocation = SourceSetup.prepare(
            SourceSetupRequest(
                SourceAttachment(source),
                ExecutionOperation.SATISFY,
                FiniteEngine.LOCAL_SEARCH,
                providers = FactorProjectionProviders(encoder = EncoderProvider { factor, target ->
                    if (factor is AllDifferent) Encoder(EncoderSupport.UNSUPPORTED)
                    else BuiltInEncoderProvider.select(factor, target)
                }),
            ),
        )
        assertEquals(SourceExecutionKind.HEURISTIC, SourceSetup.executionKind(invocation))

        val result = assertIs<SourceExecutionResult.Linear>(
            SourceSetup.execute(
                SourceExecutionRequest(
                    invocation,
                    theory = TheoryParams(maxFlips = 32, randomSeed = 17),
                ),
            ),
        )

        val native = assertIs<OpenTheoryExecution.Satisfy>(result.result)
        val point = assertIs<OpenTheoryResult.Sat>(native.result).assignment
        assertEquals("0", point.intValue(0))
        assertTrue(point.intValue(1).toLong() > 0)
        assertSame(source, assertNotNull(invocation.preparedPlan).originalSource)
    }

    @Test
    fun `source refutation should finish empty enumeration without constructing a worker`() {
        val open = Bits(1).also { it.set(0) }
        val source = Problem(
            0,
            IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(0), open, open),
            arrayOf<Factor>(
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 3),
                Linear(intArrayOf(-1), intArrayOf(0), LinearOp.LE, -5),
            ),
        )
        val invocation = SourceSetup.prepare(
            SourceSetupRequest(
                SourceAttachment(source),
                ExecutionOperation.ENUMERATE,
                FiniteEngine.MIXED,
            ),
        )

        val result = SourceSetup.execute(
            SourceExecutionRequest(
                invocation,
                finiteRequest = { error("a refuted source constructed a worker") },
            ),
        )

        assertIs<SourceExecutionResult.Refuted>(result)
        assertSame(source, result.invocation.source.originalSource)
        assertEquals(ExecutionOperation.ENUMERATE, result.invocation.operation)
    }
    @Test
    fun `a captured source deadline should retain its native timeout reason`() {
        val source = Problem(
            0, 0, emptyArray(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0),
        )
        val invocation = SourceSetup.prepare(
            SourceSetupRequest(
                SourceAttachment(source), ExecutionOperation.SATISFY, FiniteEngine.BACKTRACK,
                cancellation = Cancellation.after(Duration.ZERO),
            ),
        )

        val result = assertIs<SourceExecutionResult.Linear>(
            SourceSetup.execute(SourceExecutionRequest(invocation)),
        )

        val native = assertIs<OpenTheoryExecution.Satisfy>(result.result)
        assertEquals(TerminationReason.Timeout, assertIs<OpenTheoryResult.Unknown>(native.result).reason)
        assertSame(source.semantics, result.semantics)
    }

}
