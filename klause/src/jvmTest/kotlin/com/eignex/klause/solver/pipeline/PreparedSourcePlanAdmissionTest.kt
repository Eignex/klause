package com.eignex.klause.solver.pipeline

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.RationalPolynomial
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.formats.smtlib.SmtLibPolynomial
import com.eignex.klause.formats.smtlib.realSource
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.NonlinearIntegerProblem
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.lp.engine.LpZeroObjectivePricing
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Bits
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.parseBigInt
import com.eignex.klause.util.times
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class PreparedSourcePlanAdmissionTest(private val engine: FiniteEngine) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun engines(): List<FiniteEngine> =
            listOf(FiniteEngine.BACKTRACK, FiniteEngine.LOCAL_SEARCH, FiniteEngine.ALNS, FiniteEngine.MIXED)
    }

    @Test
    fun `admitted strategy should solve a finite source`() {
        val source = Problem(
            0,
            2,
            arrayOf(IntDomain(0, 2), IntDomain(0, 2)),
            arrayOf(
                AllDifferent(intArrayOf(0, 1), 0, 3),
                Linear(longArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 1),
            ),
        )
        val plan = source.prepareExecutionPlan(preferFinite = true)
        var sample: Sample? = null

        val execution = FinitePipeline.solve(
            FiniteSolveRequest(
                shape = FiniteSolveShape(
                    source, false, false, null, null, null, null,
                    componentPlan = assertNotNull(plan.componentPlan), preparedPlan = plan,
                ),
                engine = engine, presolveConfig = PresolveConfig.NONE, explicitPresolveConfig = true,
                solutionSetSensitive = false, cancellation = Cancellation.Never, presolveBudget = null,
                cores = 1, executionOptions = ExecutionOptions(), randomSeed = 17, defaultArms = 1,
                lpConfig = LpConfig(), zeroObjectivePricing = LpZeroObjectivePricing.MIN_BOUND_SUPPORT,
                nodeBudget = null, solveBudgetMillis = null, allSolutions = false, solutionCap = null,
                deadlineExceeded = { false }, onEvent = null, onPortfolioEvent = null,
            ),
            FiniteSolveCallbacks(onSample = { sample = it }, onImprovement = { sample = it.sample }),
        )

        val result = assertIs<FiniteSolveOutcome.Completed>(execution.outcome)
        assertEquals(FiniteSolveVerdict.SAT, result.verdict)
        val witness = assertNotNull(sample)
        assertEquals(1L, witness.intValue(0) - witness.intValue(1))
        assertAdmitted(plan, result.stats, finite = true)
    }

    @Test
    fun `admitted strategy should solve a polynomial real source`() {
        val input = SmtLibPolynomial.parse("(declare-const x Real) (assert (= (* x x) 2))").realSource()
        val request = input.pipelineRequest()

        val execution = OpenTheoryPipeline.execute(
            request,
            PolynomialTheoryParams(
                strategy = PolynomialStrategyParams(
                    localSearch = LocalSearchParams(randomSeed = 17, maxFlips = 12),
                    alnsIterations = 2,
                ),
            ),
            engine,
        )

        val witness = assertIs<OpenTheoryResult.Sat>(execution.result).assignment
        assertEquals(
            0,
            AlgebraicArithmetic().signAt(
                RationalPolynomial.of(-2, 0, 1),
                assertNotNull(witness.algebraicRealValue(0)),
            ),
        )
        assertAdmitted(request.preparedPlan, execution.result.stats)
    }

    @Test
    fun `admitted strategy should solve a nonlinear integer source`() {
        val input = assertIs<NonlinearIntegerProblem>((
            SmtLib.parse("(declare-const n Int) (assert (= (* n n) 4))")).source.originalSource)

        val execution = OpenTheoryPipeline.execute(input.pipelineRequest(), engine = engine)

        val value = parseBigInt(assertIs<OpenTheoryResult.Sat>(execution.result).assignment.intValue(0))
        assertEquals(parseBigInt("4"), value * value)
        assertAdmitted(input.prepareExecutionPlan(), execution.result.stats)
    }

    @Test
    fun `admitted strategy should solve a mixed wide integer and real source`() {
        val open = Bits(1).also { it.set(0) }
        val wide = parseBigInt("9223372036854775808")
        val source = Problem(
            0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(0), open, open),
            factors = arrayOf(
                Linear(intArrayOf(0), arrayOf(BIG_ONE), LinearOp.EQ, wide),
                Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(3.0), LinearOp.EQ, 1.0),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
        )
        val request = OpenTheoryRequest(source)

        val execution = assertIs<OpenTheoryExecution.Satisfy>(
            OpenTheoryPipeline.execute(
                request,
                TheoryParams(openWorkLimit = 1000, maxFlips = 20, randomSeed = 17, alnsIterations = 2),
                engine,
            ),
        )

        val witness = assertIs<OpenTheoryResult.Sat>(execution.result).assignment
        assertEquals("1/3", witness.realValue(0))
        assertEquals(wide.toString(), witness.intValue(0))
        assertAdmitted(request.preparedPlan, execution.result.stats)
    }

    private fun assertAdmitted(plan: PreparedSourcePlan, stats: SolveStats, finite: Boolean = false) {
        val admission = plan.capabilities.admission(engine)
        assertTrue(admission.runnable)
        if (engine == FiniteEngine.LOCAL_SEARCH || engine == FiniteEngine.ALNS) {
            assertTrue(stats.ls.moves.sum > 0 || (finite && stats.ls.incumbentViolation == 0.0))
            assertEquals(setOf(ExecutionClaimAuthority.WITNESS), admission.claimAuthorities)
        }
    }
}
