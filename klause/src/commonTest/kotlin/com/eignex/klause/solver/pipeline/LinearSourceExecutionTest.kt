package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.ir.LinearSourceIndicator
import com.eignex.klause.ir.LinearSourceObjective
import com.eignex.klause.ir.LinearSourceProblem
import com.eignex.klause.ir.LinearSourceRow
import com.eignex.klause.ir.LinearSourceTerm
import com.eignex.klause.ir.LinearSourceVariable
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.ProblemSettings
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.ir.SourceFormat
import com.eignex.klause.ir.SourceSemantics
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.lp.engine.LpZeroObjectivePricing
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.solver.validation.LinearSourceCheck
import com.eignex.klause.solver.validation.LinearSourceWitness
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.parseBigInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LinearSourceExecutionTest {
    @Test
    fun `finite companions should retain finite source completion when numerical funding is disabled`() {
        val source = LinearSourceProblem(
            listOf(LinearSourceVariable("x", true, SemanticRational.ZERO, SemanticRational.ONE),
                LinearSourceVariable("r", false, SemanticRational.ZERO, SemanticRational.ONE)),
            emptyList(), LinearSourceObjective(listOf(LinearSourceTerm(0, SemanticRational.ONE))),
        )
        val invocation = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(source, ProblemSettings()),
            ExecutionOperation.OPTIMIZE, FiniteEngine.MIXED, linearTraversal = LinearTraversal.FINITE_COMPANIONS))
        assertIs<SourceProblemRoute.Finite>(invocation.route)

        val result = SourceSetup.execute(SourceExecutionRequest(invocation,
            ExecutionOptions(ExecutionOption.sourceLpWorkLimit(0), ExecutionOption.maxFlips(0))))

        assertIs<SourceExecutionResult.Finite>(result)
        assertEquals(LinearSourceConclusion.OPTIMAL, result.linearSource?.conclusion)
        assertEquals(BigFraction.ZERO, result.linearSource?.incumbent?.objective)
    }

    @Test
    fun `finite companions should import only floors expressed in their receiver units`() {
        for ((weight, offset) in listOf(1L to 0L, 5L to 7L)) {
            val source = LinearSourceProblem(
                listOf(LinearSourceVariable("x", true, SemanticRational.ZERO, SemanticRational.ofLong(2)),
                    LinearSourceVariable("r", false, SemanticRational.ZERO, SemanticRational.ONE)),
                listOf(LinearSourceRow(listOf(LinearSourceTerm(0, SemanticRational.ONE),
                    LinearSourceTerm(1, SemanticRational.ONE)),
                    lower = SemanticRational.of(bigIntOf(3), bigIntOf(2)))),
                LinearSourceObjective(listOf(LinearSourceTerm(0, SemanticRational.ofLong(weight))),
                    SemanticRational.ofLong(offset)),
            )
            val invocation = SourceSetup.prepare(SourceSetupRequest(
                SourceAttachment(source, ProblemSettings()), ExecutionOperation.OPTIMIZE, FiniteEngine.MIXED,
                linearTraversal = LinearTraversal.NUMERICAL_COMPANIONS))

            val result = assertIs<SourceExecutionResult.Numerical>(SourceSetup.execute(SourceExecutionRequest(
                invocation, theory = TheoryParams(maxLeaves = 3, maxFlips = 0), presolveConfig = PresolveConfig.NONE)))

            assertEquals(BigFraction.ofLong(weight + offset), assertNotNull(result.linearSource?.incumbent).objective)
            val reads = result.result.stats.smt.completeSearchStages.orEmpty()["finiteCompanionBoundReads"] ?: 0L
            if (weight == 1L && offset == 0L) assertTrue(reads > 0L) else assertEquals(0L, reads)
        }
    }

    @Test
    fun `companion admission should retain a root refutation without numerical funding`() {
        val source = LinearSourceProblem(
            listOf(LinearSourceVariable("x", true)),
            listOf(LinearSourceRow(listOf(LinearSourceTerm(0, SemanticRational.ONE)),
                lower = SemanticRational.ONE, upper = SemanticRational.ofLong(-1))),
        )
        val invocation = SourceSetup.prepare(SourceSetupRequest(
            SourceAttachment(source, ProblemSettings()), ExecutionOperation.SATISFY, FiniteEngine.MIXED,
            linearTraversal = LinearTraversal.NUMERICAL_COMPANIONS))

        val result = SourceSetup.execute(SourceExecutionRequest(invocation,
            ExecutionOptions(ExecutionOption.sourceLpWorkLimit(0))))

        assertEquals(LinearSourceConclusion.UNSATISFIABLE, assertNotNull(result.linearSource).conclusion)
    }

    @Test
    fun `a finite companion should supply an original source incumbent after numerical funding stops`() {
        val cases = ObjectiveSense.entries.flatMap { sense ->
            listOf(LinearTraversal.NUMERICAL_COMPANIONS, LinearTraversal.NUMERICAL_COMPANIONS_INDEPENDENT)
                .map { sense to it }
        }
        for ((sense, traversal) in cases) {
            val source = LinearSourceProblem(
                listOf(LinearSourceVariable("x", true, SemanticRational.ZERO, SemanticRational.ONE)),
                emptyList(), LinearSourceObjective(
                    listOf(LinearSourceTerm(0, SemanticRational.of(BIG_ONE, bigIntOf(2)))),
                    SemanticRational.of(bigIntOf(3), bigIntOf(10)), sense),
            )
            val invocation = SourceSetup.prepare(SourceSetupRequest(
                SourceAttachment(source, ProblemSettings()), ExecutionOperation.OPTIMIZE, FiniteEngine.MIXED,
                maximize = sense == ObjectiveSense.MAXIMIZE,
                linearTraversal = traversal))

            val result = assertIs<SourceExecutionResult.Numerical>(SourceSetup.execute(SourceExecutionRequest(
                invocation, ExecutionOptions(ExecutionOption.sourceLpWorkLimit(0), ExecutionOption.maxFlips(0)))))

            val expected = BigFraction.of(bigIntOf(if (sense == ObjectiveSense.MAXIMIZE) 8L else 3L), bigIntOf(10))
            assertEquals(expected, assertNotNull(result.linearSource?.incumbent).objective)
            assertEquals(LinearSourceConclusion.SATISFIABLE, result.linearSource.conclusion)
            assertNull(result.result.terminal)
        }
    }

    @Test
    fun `numerical frontier policy should consume bounded integer sibling coverage`() {
        val source = LinearSourceProblem(
            listOf(LinearSourceVariable("x", true, SemanticRational.ZERO, SemanticRational.ONE)),
            listOf(LinearSourceRow(listOf(LinearSourceTerm(0, SemanticRational.ofLong(3))),
                lower = SemanticRational.ONE)),
            LinearSourceObjective(listOf(LinearSourceTerm(0, SemanticRational.ONE))),
        )
        val invocation = SourceSetup.prepare(SourceSetupRequest(
            SourceAttachment(source, ProblemSettings()), ExecutionOperation.OPTIMIZE, FiniteEngine.FIXED,
            linearTraversal = LinearTraversal.NUMERICAL_FRONTIER,
        ))

        val result = assertIs<SourceExecutionResult.Numerical>(SourceSetup.execute(SourceExecutionRequest(invocation)))

        assertEquals(SourceValue.Rational(BigFraction.ONE),
            assertIs<SourceClaim.Optimal>(assertNotNull(result.result.terminal).claim).value)
        assertEquals(LinearSourceConclusion.OPTIMAL, result.linearSource?.conclusion)
    }

    @Test
    fun `numerical heuristic strategies should publish checked mixed source points without terminal claims`() {
        val source = LinearSourceProblem(
            listOf(LinearSourceVariable("x", true, SemanticRational.ZERO, SemanticRational.ONE),
                LinearSourceVariable("y", true, SemanticRational.ZERO, SemanticRational.ONE),
                LinearSourceVariable("r", false, SemanticRational.of(BIG_ONE, bigIntOf(4)), SemanticRational.ONE)),
            listOf(0, 1).map { integer ->
                LinearSourceRow(listOf(LinearSourceTerm(integer, SemanticRational.ONE),
                    LinearSourceTerm(2, SemanticRational.ofLong(-2))),
                    lower = SemanticRational.ZERO, upper = SemanticRational.ZERO)
            },
            LinearSourceObjective(listOf(LinearSourceTerm(2, SemanticRational.ONE))),
        )
        for (engine in listOf(FiniteEngine.LOCAL_SEARCH, FiniteEngine.ALNS)) {
            val invocation = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(source, ProblemSettings()),
                ExecutionOperation.OPTIMIZE, engine, linearTraversal = LinearTraversal.NUMERICAL))

            val result = assertIs<SourceExecutionResult.Numerical>(SourceSetup.execute(
                SourceExecutionRequest(invocation, theory = TheoryParams(maxFlips = 3))))

            val outcome = assertNotNull(result.linearSource)
            assertEquals(LinearSourceConclusion.SATISFIABLE, outcome.conclusion)
            assertEquals(BigFraction.ofLong(2).reciprocal(), outcome.incumbent?.objective)
            assertNull(result.result.terminal)
            assertNull(result.result.bound)
            assertTrue(result.result.stats.ls.completions.sum > 0)
        }
    }

    @Test
    fun `an interrupted bounded numerical split should withhold terminal authority`() {
        val source = LinearSourceProblem(
            listOf(LinearSourceVariable("x", true, SemanticRational.ZERO, SemanticRational.ONE)),
            listOf(LinearSourceRow(listOf(LinearSourceTerm(0, SemanticRational.ofLong(3))),
                lower = SemanticRational.ONE)),
            LinearSourceObjective(listOf(LinearSourceTerm(0, SemanticRational.ONE))),
        )
        val invocation = SourceSetup.prepare(SourceSetupRequest(
            SourceAttachment(source, ProblemSettings()), ExecutionOperation.OPTIMIZE, FiniteEngine.FIXED,
            linearTraversal = LinearTraversal.NUMERICAL,
        ))

        val result = assertIs<SourceExecutionResult.Numerical>(SourceSetup.execute(SourceExecutionRequest(invocation,
            ExecutionOptions(ExecutionOption.maxDecisions(0)))))

        assertNull(result.result.terminal)
        assertEquals(TerminationReason.BudgetExhausted, result.result.reason)
        assertEquals(LinearSourceConclusion.UNKNOWN, result.linearSource?.conclusion)
    }

    @Test
    fun `nonzero numerical underflow should retain an unsupported numerical result`() {
        val tiny = SemanticRational.of(BIG_ONE, parseBigInt("1" + "0".repeat(400)))
        for (tolerance in listOf(SourceTolerance(), SourceTolerance(
            absolute = SemanticRational.of(BIG_ONE, bigIntOf(10_000_000)),
            relative = SemanticRational.of(BIG_ONE, bigIntOf(10_000_000)),
        ))) {
            val source = LinearSourceProblem(
                listOf(LinearSourceVariable("x", false, SemanticRational.ZERO, SemanticRational.ONE)),
                listOf(LinearSourceRow(listOf(LinearSourceTerm(0, tiny)),
                    lower = SemanticRational.of(BIG_ONE, bigIntOf(10)))),
                LinearSourceObjective(listOf(LinearSourceTerm(0, SemanticRational.ONE))),
                SourceSemantics(SourceFormat.MPS, feasibilityTolerance = tolerance).resolve(),
            )
            val invocation = SourceSetup.prepare(SourceSetupRequest(
                SourceAttachment(source, ProblemSettings()), ExecutionOperation.OPTIMIZE, FiniteEngine.MIXED,
            ))

            val result = SourceSetup.execute(SourceExecutionRequest(invocation,
                finiteRequest = { error("an unsupported numerical source cannot construct a finite consumer") }))

            assertEquals(SourceExecutionKind.NUMERICAL, SourceSetup.executionKind(invocation))
            val native = assertIs<SourceExecutionResult.Numerical>(result).result
            assertEquals(TerminationReason.Unsupported, native.reason)
            assertNull(native.terminal)
            assertNull(native.incumbent)
            assertNull(native.bound)
            assertEquals(LinearSourceConclusion.UNKNOWN, assertNotNull(result.linearSource).conclusion)
        }
    }

    @Test
    fun `an integral real backing should reconstruct an optimized original source point`() {
        val source = LinearSourceProblem(
            listOf(LinearSourceVariable("z", false),
                LinearSourceVariable("x", true, SemanticRational.ZERO, SemanticRational.ONE)),
            listOf(LinearSourceRow(listOf(LinearSourceTerm(0, SemanticRational.ONE),
                LinearSourceTerm(1, SemanticRational.ofLong(-3))),
                SemanticRational.ofLong(2), SemanticRational.ofLong(2))),
            LinearSourceObjective(listOf(LinearSourceTerm(0, SemanticRational.ONE)),
                sense = ObjectiveSense.MAXIMIZE),
        )
        val invocation = SourceSetup.prepare(SourceSetupRequest(
            SourceAttachment(source, ProblemSettings()), ExecutionOperation.OPTIMIZE, FiniteEngine.BACKTRACK,
            maximize = true,
        ))

        val result = SourceSetup.execute(SourceExecutionRequest(invocation, finiteRequest = { shape ->
            FiniteSolveRequest(
                shape, FiniteEngine.BACKTRACK, PresolveConfig(), false, false, Cancellation.Never,
                presolveBudget = null, cores = 1, executionOptions = ExecutionOptions(),
                randomSeed = 17, defaultArms = 1, lpConfig = LpConfig.AUTO,
                zeroObjectivePricing = LpZeroObjectivePricing.MIN_BOUND_SUPPORT,
                nodeBudget = NodeBudget(100),
                solveBudgetMillis = null, allSolutions = false, solutionCap = null,
                deadlineExceeded = { false }, onEvent = null, onPortfolioEvent = null,
            )
        }))

        val finite = assertIs<SourceExecutionResult.Finite>(result)
        val completed = assertIs<FiniteSolveOutcome.Completed>(finite.result.outcome)
        val outcome = assertNotNull(result.linearSource)
        val diagnostic = "native=${outcome.nativeConclusion}, source=${outcome.conclusion}, " +
            "nominal=${outcome.nominalSourceExact}, coverage=${outcome.claimsCoverTolerance}, " +
            "unrestricted=${outcome.nativeClaimsUnrestricted}, exact=${outcome.incumbent?.exact}, " +
            "values=${outcome.incumbent?.values}, objective=${outcome.incumbent?.objective}, " +
            "arms=${completed.stats.portfolio.arms}"
        assertEquals(FiniteSolveVerdict.OPTIMAL, completed.verdict, diagnostic)
        assertEquals(LinearSourceConclusion.OPTIMAL, outcome.conclusion, diagnostic)
        assertEquals(listOf(BigFraction.ofLong(5), BigFraction.ONE), outcome.incumbent?.values)
        assertEquals(BigFraction.ofLong(5), outcome.incumbent?.objective)
    }

    @Test
    fun `a complete finite portfolio should certify an embedded original objective`() {
        val source = LinearSourceProblem(
            listOf(LinearSourceVariable("n", true, SemanticRational.ZERO, SemanticRational.ofLong(2))),
            emptyList(), LinearSourceObjective(listOf(LinearSourceTerm(0, SemanticRational.ONE)),
                sense = ObjectiveSense.MAXIMIZE),
        )
        val invocation = SourceSetup.prepare(SourceSetupRequest(
            SourceAttachment(source, ProblemSettings()), ExecutionOperation.OPTIMIZE, FiniteEngine.BACKTRACK,
            maximize = true,
        ))

        val result = SourceSetup.execute(SourceExecutionRequest(invocation, finiteRequest = { shape ->
            FiniteSolveRequest(
                shape, FiniteEngine.BACKTRACK, PresolveConfig.NONE, false, false, Cancellation.Never,
                presolveBudget = null, cores = 1, executionOptions = ExecutionOptions(),
                randomSeed = 17, defaultArms = 1, lpConfig = LpConfig.AUTO,
                zeroObjectivePricing = LpZeroObjectivePricing.MIN_BOUND_SUPPORT,
                nodeBudget = NodeBudget(100),
                solveBudgetMillis = null, allSolutions = false, solutionCap = null,
                deadlineExceeded = { false }, onEvent = null, onPortfolioEvent = null,
            )
        }))

        val finite = assertIs<SourceExecutionResult.Finite>(result)
        val outcome = assertIs<FiniteSolveOutcome.Completed>(finite.result.outcome)
        assertEquals(FiniteSolveVerdict.OPTIMAL, outcome.verdict)
        assertTrue(outcome.stats.portfolio.arms.any { it.segments > 0L })
        assertEquals(LinearSourceConclusion.OPTIMAL, finite.linearSource?.conclusion)
        assertEquals(BigFraction.ofLong(2), finite.linearSource?.incumbent?.objective)
    }

    @Test
    fun `declining optional bound closure should retain required numerical source execution`() {
        val source = LinearSourceProblem(
            listOf(LinearSourceVariable("n", true, SemanticRational.ZERO)),
            listOf(LinearSourceRow(listOf(LinearSourceTerm(0, SemanticRational.ONE)),
                upper = SemanticRational.ofLong(5))),
            LinearSourceObjective(listOf(LinearSourceTerm(0, SemanticRational.ONE)),
                sense = ObjectiveSense.MAXIMIZE),
        )
        val invocation = SourceSetup.prepare(SourceSetupRequest(
            SourceAttachment(source, ProblemSettings()), ExecutionOperation.OPTIMIZE, FiniteEngine.FIXED,
            maximize = true, boundCancellation = Cancellation { true },
        ))

        val result = SourceSetup.execute(SourceExecutionRequest(invocation,
            finiteRequest = { error("declined closure installed finite execution") }))

        assertIs<SourceExecutionResult.Numerical>(result)
        val outcome = assertNotNull(result.linearSource)
        assertEquals(LinearSourceConclusion.OPTIMAL, outcome.conclusion)
        assertEquals(BigFraction.ofLong(5), outcome.incumbent?.objective)
    }

    @Test
    fun `zero source tolerance should retain an exact one sided real optimum`() {
        val source = LinearSourceProblem(
            listOf(LinearSourceVariable("x", false)),
            listOf(LinearSourceRow(listOf(LinearSourceTerm(0, SemanticRational.ofLong(10))),
                lower = SemanticRational.ONE)),
            LinearSourceObjective(listOf(LinearSourceTerm(0, SemanticRational.ONE))),
        )
        val invocation = SourceSetup.prepare(SourceSetupRequest(
            SourceAttachment(source, ProblemSettings()), ExecutionOperation.OPTIMIZE, FiniteEngine.FIXED,
        ))

        val result = SourceSetup.execute(SourceExecutionRequest(invocation, finiteRequest = { shape ->
            FiniteSolveRequest(
                shape, FiniteEngine.FIXED, PresolveConfig.NONE, false, false, Cancellation.Never,
                presolveBudget = null, cores = 1, executionOptions = ExecutionOptions(),
                randomSeed = 17, defaultArms = 1, lpConfig = LpConfig.AUTO,
                zeroObjectivePricing = LpZeroObjectivePricing.MIN_BOUND_SUPPORT, nodeBudget = null,
                solveBudgetMillis = null, allSolutions = false, solutionCap = null,
                deadlineExceeded = { false }, onEvent = null, onPortfolioEvent = null,
            )
        }))

        val outcome = assertNotNull(result.linearSource)
        assertEquals(LinearSourceConclusion.OPTIMAL, outcome.conclusion)
        assertEquals(BigFraction.ofLong(10).reciprocal(), outcome.incumbent?.objective)
        assertEquals(listOf(BigFraction.ofLong(10).reciprocal()), outcome.incumbent?.values)
    }

    @Test
    fun `an extra exact source filter should remain separate from optimum permission`() {
        val source = LinearSourceProblem(
            listOf(LinearSourceVariable("x", false)),
            listOf(LinearSourceRow(listOf(LinearSourceTerm(0, SemanticRational.ofLong(10))),
                lower = SemanticRational.ONE)),
            LinearSourceObjective(listOf(LinearSourceTerm(0, SemanticRational.ONE))),
        )
        val invocation = SourceSetup.prepare(SourceSetupRequest(
            SourceAttachment(source, ProblemSettings()), ExecutionOperation.OPTIMIZE, FiniteEngine.FIXED,
        ))
        var checked = 0

        val result = SourceSetup.execute(SourceExecutionRequest(invocation, finiteRequest = { shape ->
            FiniteSolveRequest(
                shape, FiniteEngine.FIXED, PresolveConfig.NONE, false, false, Cancellation.Never,
                presolveBudget = null, cores = 1, executionOptions = ExecutionOptions(),
                randomSeed = 17, defaultArms = 1, lpConfig = LpConfig.AUTO,
                zeroObjectivePricing = LpZeroObjectivePricing.MIN_BOUND_SUPPORT, nodeBudget = null,
                solveBudgetMillis = null, allSolutions = false, solutionCap = null,
                deadlineExceeded = { false }, onEvent = null, onPortfolioEvent = null,
                toleranceCheck = { checked++; true },
            )
        }))

        val outcome = assertNotNull(result.linearSource)
        assertTrue(checked > 0)
        assertFalse(outcome.nativeClaimsUnrestricted)
        assertEquals(BigFraction.ofLong(10).reciprocal(), outcome.incumbent?.objective)
        assertEquals(LinearSourceConclusion.OPTIMAL, outcome.nativeConclusion)
        assertEquals(LinearSourceConclusion.SATISFIABLE, outcome.conclusion)
    }

    @Test
    fun `a rebuilt witness should retain typed exact reals and recovered source integers`() {
        val source = LinearSourceProblem(
            listOf(
                LinearSourceVariable("n", true, SemanticRational.ofLong(3), SemanticRational.ofLong(3)),
                LinearSourceVariable("x", false),
            ),
            listOf(
                LinearSourceRow(
                listOf(LinearSourceTerm(1, SemanticRational.ofLong(3))),
                lower = SemanticRational.ONE,
                upper = SemanticRational.ONE,
            )
            ),
        )
        val invocation = SourceSetup.prepare(
            SourceSetupRequest(
            SourceAttachment(source, ProblemSettings()),
            ExecutionOperation.SATISFY,
            FiniteEngine.FIXED,
        )
        )
        val exact = BigFraction.ofLong(3).reciprocal()
        val sample = Sample(
            booleanArrayOf(),
            longArrayOf(0),
            doubleArrayOf(1.0 / 3.0),
            exactReals = listOf(exact),
        )
        val rebuilt = OpenTheoryAssignment.Rebuilt(
            OpenTheoryAssignment.Difference(sample),
            booleanArrayOf(),
            arrayOf(bigIntOf(3)),
        )

        val checked = assertIs<LinearSourceCheck.Accepted>(
            assertNotNull(invocation.linearSource).check(rebuilt, Cancellation.Never),
        )

        assertEquals(listOf(BigFraction.ofLong(3), exact), checked.witness.values)
        assertTrue(checked.witness.exact)
    }

    @Test
    fun `open numerical maximization should retain scaled native costs and original exact units`() {
        val source = LinearSourceProblem(
            listOf(LinearSourceVariable("n", true)),
            listOf(
                LinearSourceRow(
                    listOf(LinearSourceTerm(0, SemanticRational.ofLong(2))),
                    upper = SemanticRational.ofLong(7),
                ),
            ),
            LinearSourceObjective(
                listOf(LinearSourceTerm(0, SemanticRational.ONE)),
                SemanticRational.of(BIG_ONE, bigIntOf(10)),
                ObjectiveSense.MAXIMIZE,
            ),
            SourceSemantics(
                SourceFormat.MPS,
                feasibilityTolerance = SourceTolerance(
                    absolute = SemanticRational.of(BIG_ONE, bigIntOf(1_000_000)),
                ),
            ).resolve(),
        )
        val invocation = SourceSetup.prepare(
            SourceSetupRequest(
                SourceAttachment(source, ProblemSettings()),
                ExecutionOperation.OPTIMIZE,
                FiniteEngine.FIXED,
                maximize = true,
            ),
        )
        val points = mutableListOf<LinearSourceWitness>()
        val nativeValues = mutableListOf<SourceValue>()

        val result = assertIs<SourceExecutionResult.Numerical>(
            SourceSetup.execute(
                SourceExecutionRequest(invocation, finiteRequest = { error("open source has no finite consumer") }),
                SourceExecutionCallbacks(
                    onLinearSourceIncumbent = points::add,
                    onLinearIncumbent = { nativeValues += it.value },
                ),
            ),
        )

        val outcome = assertNotNull(result.linearSource)
        assertSame(source, invocation.source.originalSource)
        assertSame(source.objective, invocation.sourceLinearObjective)
        assertNull(invocation.sourceObjective)
        assertSame(source, assertNotNull(invocation.preparedPlan).originalSource)
        assertEquals(0, assertNotNull(invocation.preparedPlan).sourceRealCount)
        assertEquals(BigFraction.ofLong(10), outcome.objectiveScale)
        assertEquals(BigFraction.ofLong(31), result.result.value)
        assertEquals(BigFraction.ofLong(31), assertIs<SourceValue.Rational>(nativeValues.last()).value)
        assertIs<SourceClaim.Optimal>(assertNotNull(result.result.terminal).claim)
        assertEquals(BigFraction.ofLong(31) * BigFraction.ofLong(10).reciprocal(), points.last().objective)
        assertEquals(points.last().objective, outcome.incumbent?.objective)
        assertEquals(LinearSourceConclusion.OPTIMAL, outcome.conclusion)
        assertTrue(outcome.claimsCoverTolerance)
    }

    @Test
    fun `a nominal rational optimum should report under numerical tolerance policy`() {
        val source = LinearSourceProblem(
            listOf(LinearSourceVariable("n", true), LinearSourceVariable("x", false)),
            listOf(
                LinearSourceRow(
                    listOf(LinearSourceTerm(1, SemanticRational.ofLong(3))),
                    lower = SemanticRational.ONE,
                    upper = SemanticRational.ONE,
                ),
            ),
            LinearSourceObjective(listOf(LinearSourceTerm(1, SemanticRational.ONE))),
            SourceSemantics(
                SourceFormat.MPS,
                feasibilityTolerance = SourceTolerance(
                    absolute = SemanticRational.of(BIG_ONE, bigIntOf(1_000_000)),
                ),
            ).resolve(),
        )
        val invocation = SourceSetup.prepare(
            SourceSetupRequest(
                SourceAttachment(source, ProblemSettings()),
                ExecutionOperation.OPTIMIZE,
                FiniteEngine.FIXED,
            ),
        )

        val result = assertIs<SourceExecutionResult.Numerical>(SourceSetup.execute(SourceExecutionRequest(invocation)))

        val outcome = assertNotNull(result.linearSource)
        assertIs<SourceClaim.Optimal>(assertNotNull(result.result.terminal).claim)
        assertEquals(LinearSourceConclusion.OPTIMAL, outcome.nativeConclusion)
        assertEquals(LinearSourceConclusion.OPTIMAL, outcome.conclusion)
        assertFalse(outcome.claimsCoverTolerance)
        assertTrue(outcome.numericalClaims)
        assertEquals(BigFraction.ofLong(3).reciprocal(), outcome.incumbent?.objective)
        assertTrue(assertNotNull(outcome.acceptedObjectiveBound) < assertNotNull(outcome.incumbent?.objective))
        assertEquals(1, assertNotNull(invocation.preparedPlan).sourceRealCount)
        assertEquals(2, assertNotNull(invocation.preparedPlan).model?.numRealVars)
    }

    @Test
    fun `a stopped source preparation should not construct a finite consumer or revive its scope`() {
        val source = LinearSourceProblem(listOf(LinearSourceVariable("n", true)), emptyList())
        val invocation = SourceSetup.prepare(
            SourceSetupRequest(
                SourceAttachment(source, ProblemSettings()),
                ExecutionOperation.SATISFY,
                FiniteEngine.FIXED,
                cancellation = Cancellation { true },
            ),
        )

        val result = SourceSetup.execute(
            SourceExecutionRequest(
                invocation,
                finiteRequest = { error("stopped preparation constructed a finite consumer") },
            ),
        )

        assertIs<SourceExecutionResult.Declined>(result)
        assertNull(invocation.preparedPlan)
        assertSame(source.semantics, result.semantics)
    }

    @Test
    fun `binding another objective should reject without changing the original source plan`() {
        val source = LinearSourceProblem(
            listOf(LinearSourceVariable("x", false)),
            emptyList(),
            LinearSourceObjective(listOf(LinearSourceTerm(0, SemanticRational.ONE))),
        )
        val invocation = SourceSetup.prepare(
            SourceSetupRequest(
                SourceAttachment(source, ProblemSettings()),
                ExecutionOperation.OPTIMIZE,
                FiniteEngine.FIXED,
            ),
        )
        val plan = assertNotNull(invocation.preparedPlan)

        assertFailsWith<IllegalArgumentException> { plan.withObjective(LinearObjective(constant = 2)) }

        assertSame(source.objective, plan.sourceLinearObjective)
        assertSame(source, plan.originalSource)
    }

    @Test
    fun `finite preparation should preserve original source identity and fractional maximization`() {
        val source = LinearSourceProblem(
            listOf(LinearSourceVariable("n", true, SemanticRational.ZERO, SemanticRational.ofLong(3))),
            emptyList(),
            LinearSourceObjective(
                listOf(LinearSourceTerm(0, SemanticRational.of(BIG_ONE, bigIntOf(3)))),
                SemanticRational.of(BIG_ONE, bigIntOf(10)),
                ObjectiveSense.MAXIMIZE,
            ),
        )
        val invocation = SourceSetup.prepare(
            SourceSetupRequest(
                SourceAttachment(source, ProblemSettings()),
                ExecutionOperation.OPTIMIZE,
                FiniteEngine.FIXED,
                maximize = true,
            ),
        )
        val values = mutableListOf<LinearSourceWitness>()

        val result = assertIs<SourceExecutionResult.Finite>(
            SourceSetup.execute(
                SourceExecutionRequest(invocation, finiteRequest = { shape ->
                    FiniteSolveRequest(
                        shape, FiniteEngine.FIXED, PresolveConfig.NONE, false, false, Cancellation.Never,
                        presolveBudget = null, cores = 1, executionOptions = ExecutionOptions.parse(emptyList()),
                        randomSeed = 17, defaultArms = 1, lpConfig = LpConfig.OFF,
                        zeroObjectivePricing = LpZeroObjectivePricing.MIN_BOUND_SUPPORT, nodeBudget = null,
                        solveBudgetMillis = null, allSolutions = false, solutionCap = null,
                        deadlineExceeded = { false }, onEvent = null, onPortfolioEvent = null,
                    )
                }),
                SourceExecutionCallbacks(onLinearSourceIncumbent = values::add),
            ),
        )

        val outcome = assertNotNull(result.linearSource)
        assertSame(source, outcome.source)
        assertEquals(LinearSourceConclusion.OPTIMAL, outcome.conclusion)
        assertEquals(BigFraction.ofLong(11) * BigFraction.ofLong(10).reciprocal(), outcome.incumbent?.objective)
        assertEquals(outcome.incumbent?.objective, values.last().objective)
        assertEquals(BigFraction.ofLong(30), outcome.objectiveScale)
        assertEquals(
            FiniteSolveVerdict.OPTIMAL,
            assertIs<FiniteSolveOutcome.Completed>(result.result.outcome).verdict,
        )
    }

    @Test
    fun `retained numerical source outcomes should survive a later stopped execution`() {
        val source = LinearSourceProblem(
            listOf(LinearSourceVariable("n", true)),
            emptyList(),
            semantics = SourceSemantics(
                SourceFormat.MPS,
                feasibilityTolerance = SourceTolerance(
                    absolute = SemanticRational.of(BIG_ONE, bigIntOf(1_000_000)),
                ),
            ).resolve(),
        )
        val invocation = SourceSetup.prepare(
            SourceSetupRequest(
                SourceAttachment(source, ProblemSettings()),
                ExecutionOperation.SATISFY,
                FiniteEngine.FIXED,
            ),
        )
        val points = mutableListOf<LinearSourceWitness>()
        val first = SourceSetup.execute(
            SourceExecutionRequest(invocation),
            SourceExecutionCallbacks(onLinearSourceIncumbent = points::add),
        )
        val snapshot = assertNotNull(first.linearSource)
        val retained = assertNotNull(snapshot.incumbent)

        val second = SourceSetup.execute(
            SourceExecutionRequest(
                invocation,
                theory = TheoryParams(cancellation = Cancellation { true }),
            ),
        )

        assertEquals(LinearSourceConclusion.SATISFIABLE, snapshot.conclusion)
        assertEquals(LinearSourceConclusion.UNKNOWN, assertNotNull(second.linearSource).conclusion)
        assertSame(retained, snapshot.incumbent)
        assertEquals(listOf(BigFraction.ZERO), retained.values)
        assertEquals(1, points.size)
    }

    @Test
    fun `a source callback failure should leave the captured numerical source reusable`() {
        val source = LinearSourceProblem(
            listOf(LinearSourceVariable("n", true)),
            emptyList(),
            semantics = SourceSemantics(
                SourceFormat.MPS,
                feasibilityTolerance = SourceTolerance(
                    absolute = SemanticRational.of(BIG_ONE, bigIntOf(1_000_000)),
                ),
            ).resolve(),
        )
        val invocation = SourceSetup.prepare(
            SourceSetupRequest(
                SourceAttachment(source, ProblemSettings()),
                ExecutionOperation.SATISFY,
                FiniteEngine.FIXED,
            ),
        )
        val failure = IllegalStateException("source callback failed")

        val thrown = assertFailsWith<IllegalStateException> {
            SourceSetup.execute(
                SourceExecutionRequest(invocation),
                SourceExecutionCallbacks(onLinearSourceIncumbent = { throw failure }),
            )
        }
        val retry = SourceSetup.execute(SourceExecutionRequest(invocation))

        assertSame(failure, thrown)
        assertEquals(LinearSourceConclusion.SATISFIABLE, assertNotNull(retry.linearSource).conclusion)
    }

    @Test
    fun `finite real publication should exclude a private objective column from original coordinates`() {
        val source = LinearSourceProblem(
            listOf(LinearSourceVariable("x", false, SemanticRational.ZERO, SemanticRational.ONE)),
            emptyList(),
            LinearSourceObjective(
                listOf(LinearSourceTerm(0, SemanticRational.of(BIG_ONE, bigIntOf(10)))),
                SemanticRational.of(bigIntOf(3), bigIntOf(10)),
            ),
            SourceSemantics(
                SourceFormat.MPS,
                feasibilityTolerance = SourceTolerance(
                absolute = SemanticRational.of(BIG_ONE, bigIntOf(1_000_000)),
            )
            ).resolve(),
        )
        val invocation = SourceSetup.prepare(
            SourceSetupRequest(
            SourceAttachment(source, ProblemSettings()),
            ExecutionOperation.OPTIMIZE,
            FiniteEngine.FIXED,
        )
        )

        val result = SourceSetup.execute(
            SourceExecutionRequest(invocation, finiteRequest = { shape ->
            FiniteSolveRequest(
                shape, FiniteEngine.FIXED, PresolveConfig.NONE, false, false, Cancellation.Never,
                presolveBudget = null, cores = 1, executionOptions = ExecutionOptions.parse(emptyList()),
                randomSeed = 17, defaultArms = 1, lpConfig = LpConfig.AUTO,
                zeroObjectivePricing = LpZeroObjectivePricing.MIN_BOUND_SUPPORT, nodeBudget = null,
                solveBudgetMillis = null, allSolutions = false, solutionCap = null,
                deadlineExceeded = { false }, onEvent = null, onPortfolioEvent = null,
            )
        })
        )

        val point = assertNotNull(assertNotNull(result.linearSource).incumbent)
        assertEquals(1, point.values.size)
        assertEquals(BigFraction.ZERO, point.values.single())
        assertEquals(BigFraction.ofLong(3) * BigFraction.ofLong(10).reciprocal(), point.objective)
        assertEquals(1, assertNotNull(invocation.preparedPlan).sourceRealCount)
        assertEquals(2, assertNotNull(invocation.preparedPlan).model?.numRealVars)
    }

    @Test
    fun `objective projection should not hide a refutation of exact source constraints`() {
        val source = LinearSourceProblem(
            listOf(LinearSourceVariable("x", false, SemanticRational.ofLong(2), SemanticRational.ONE)),
            emptyList(),
            LinearSourceObjective(listOf(LinearSourceTerm(0, SemanticRational.of(BIG_ONE, bigIntOf(10))))),
            SourceSemantics(
                SourceFormat.MPS,
                feasibilityTolerance = SourceTolerance(
                absolute = SemanticRational.of(BIG_ONE, bigIntOf(1_000_000)),
            )
            ).resolve(),
        )
        val invocation = SourceSetup.prepare(
            SourceSetupRequest(
            SourceAttachment(source, ProblemSettings()),
            ExecutionOperation.OPTIMIZE,
            FiniteEngine.FIXED,
        )
        )

        val result = SourceSetup.execute(
            SourceExecutionRequest(invocation, finiteRequest = { shape ->
            FiniteSolveRequest(
                shape, FiniteEngine.FIXED, PresolveConfig.NONE, false, false, Cancellation.Never,
                presolveBudget = null, cores = 1, executionOptions = ExecutionOptions.parse(emptyList()),
                randomSeed = 17, defaultArms = 1, lpConfig = LpConfig.AUTO,
                zeroObjectivePricing = LpZeroObjectivePricing.MIN_BOUND_SUPPORT, nodeBudget = null,
                solveBudgetMillis = null, allSolutions = false, solutionCap = null,
                deadlineExceeded = { false }, onEvent = null, onPortfolioEvent = null,
            )
        })
        )

        val outcome = assertNotNull(result.linearSource)
        assertEquals(LinearSourceConclusion.UNSATISFIABLE, outcome.conclusion)
        assertEquals(LinearSourceConclusion.UNSATISFIABLE, outcome.nativeConclusion)
        assertTrue(outcome.nominalConstraintsExact)
        assertFalse(outcome.nominalSourceExact)
        assertTrue(outcome.claimsCoverTolerance)
        assertNull(outcome.incumbent)
    }

    @Test
    fun `an accepted point should not inherit optimality at a different private cost value`() {
        val source = LinearSourceProblem(
            listOf(LinearSourceVariable("x", false, SemanticRational.ZERO, SemanticRational.ONE)),
            emptyList(),
            LinearSourceObjective(listOf(LinearSourceTerm(0, SemanticRational.ONE))),
        )
        val invocation = SourceSetup.prepare(
            SourceSetupRequest(
            SourceAttachment(source, ProblemSettings()),
            ExecutionOperation.OPTIMIZE,
            FiniteEngine.FIXED,
        )
        )
        val point = Sample(
            booleanArrayOf(),
            longArrayOf(),
            doubleArrayOf(1.0, 0.0),
            exactReals = listOf(BigFraction.ONE, BigFraction.ZERO),
        )
        val native = OpenTheoryExecution.Optimize(
            OpenTheoryOptimum.Optimal(
            OpenTheoryAssignment.Sampled(point),
            BigFraction.ZERO,
            SolveStats.EMPTY,
        )
        )

        val result = executeLinearSource(SourceExecutionRequest(invocation), SourceExecutionCallbacks()) { _, _, _ ->
            SourceExecutionResult.Linear(invocation, native)
        }

        val outcome = assertNotNull(result.linearSource)
        assertEquals(LinearSourceConclusion.OPTIMAL, outcome.nativeConclusion)
        assertEquals(LinearSourceConclusion.SATISFIABLE, outcome.conclusion)
        assertEquals(BigFraction.ONE, outcome.incumbent?.objective)
        assertSame(native, assertIs<SourceExecutionResult.Linear>(result).result)
    }

    @Test
    fun `exact integer indicators should retain their admitted numerical source execution`() {
        val source = LinearSourceProblem(
            listOf(
                LinearSourceVariable("guard", true, SemanticRational.ZERO, SemanticRational.ONE),
                LinearSourceVariable("x", false, SemanticRational.ZERO, SemanticRational.ofLong(2)),
            ),
            listOf(
                LinearSourceRow(
                listOf(LinearSourceTerm(1, SemanticRational.ONE)),
                lower = SemanticRational.ONE,
                indicator = LinearSourceIndicator(0, true),
            )
            ),
            LinearSourceObjective(listOf(LinearSourceTerm(1, SemanticRational.ONE))),
        )
        val invocation = SourceSetup.prepare(
            SourceSetupRequest(
            SourceAttachment(source, ProblemSettings()),
            ExecutionOperation.OPTIMIZE,
            FiniteEngine.FIXED,
        )
        )

        val result = SourceSetup.execute(
            SourceExecutionRequest(invocation, finiteRequest = { shape ->
            FiniteSolveRequest(
                shape, FiniteEngine.FIXED, PresolveConfig.NONE, false, false, Cancellation.Never,
                presolveBudget = null, cores = 1, executionOptions = ExecutionOptions.parse(emptyList()),
                randomSeed = 17, defaultArms = 1, lpConfig = LpConfig.AUTO,
                zeroObjectivePricing = LpZeroObjectivePricing.MIN_BOUND_SUPPORT, nodeBudget = null,
                solveBudgetMillis = null, allSolutions = false, solutionCap = null,
                deadlineExceeded = { false }, onEvent = null, onPortfolioEvent = null,
            )
        })
        )

        val outcome = assertNotNull(result.linearSource)
        assertEquals(LinearSourceConclusion.OPTIMAL, outcome.conclusion)
        assertEquals(BigFraction.ZERO, outcome.incumbent?.objective)
        assertEquals(2, assertNotNull(outcome.incumbent).values.size)
        assertSame(source, invocation.source.originalSource)
    }

    @Test
    fun `a finite execution stop should cover original source publication checks`() {
        val source = LinearSourceProblem(
            listOf(LinearSourceVariable("n", true, SemanticRational.ofLong(2), SemanticRational.ofLong(2))),
            emptyList(),
        )
        val invocation = SourceSetup.prepare(
            SourceSetupRequest(
            SourceAttachment(source, ProblemSettings()),
            ExecutionOperation.SATISFY,
            FiniteEngine.FIXED,
        )
        )
        val points = mutableListOf<LinearSourceWitness>()

        val result = executeLinearSource(
            SourceExecutionRequest(invocation),
            SourceExecutionCallbacks(onLinearSourceIncumbent = points::add),
        ) { _, callbacks, bind ->
            assertNotNull(bind).invoke(Cancellation { true }, true)
            callbacks.finite.onSample(Sample(booleanArrayOf(), longArrayOf(2)))
            SourceExecutionResult.Declined(invocation, TerminationReason.Cancelled)
        }

        assertTrue(points.isEmpty())
        assertEquals(LinearSourceConclusion.UNKNOWN, assertNotNull(result.linearSource).conclusion)
        assertEquals(TerminationReason.Cancelled, assertIs<SourceExecutionResult.Declined>(result).reason)
    }

    @Test
    fun `an additional finite acceptance filter should not refute the whole original source`() {
        val source = LinearSourceProblem(
            listOf(LinearSourceVariable("x", false, SemanticRational.ZERO, SemanticRational.ONE)),
            emptyList(),
        )
        val invocation = SourceSetup.prepare(
            SourceSetupRequest(
            SourceAttachment(source, ProblemSettings()),
            ExecutionOperation.SATISFY,
            FiniteEngine.FIXED,
        )
        )

        val result = SourceSetup.execute(
            SourceExecutionRequest(invocation, finiteRequest = { shape ->
            FiniteSolveRequest(
                shape, FiniteEngine.FIXED, PresolveConfig.NONE, false, false, Cancellation.Never,
                presolveBudget = null, cores = 1, executionOptions = ExecutionOptions.parse(emptyList()),
                randomSeed = 17, defaultArms = 1, lpConfig = LpConfig.AUTO,
                zeroObjectivePricing = LpZeroObjectivePricing.MIN_BOUND_SUPPORT, nodeBudget = null,
                solveBudgetMillis = null, allSolutions = false, solutionCap = null,
                deadlineExceeded = { false }, onEvent = null, onPortfolioEvent = null,
                toleranceCheck = { false },
            )
        })
        )

        val outcome = assertNotNull(result.linearSource)
        assertFalse(outcome.nativeClaimsUnrestricted)
        assertTrue(outcome.conclusion != LinearSourceConclusion.UNSATISFIABLE)
        assertSame(source, outcome.source)
    }
}
