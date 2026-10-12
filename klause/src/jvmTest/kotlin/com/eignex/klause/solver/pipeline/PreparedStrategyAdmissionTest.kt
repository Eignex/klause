package com.eignex.klause.solver.pipeline

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.RationalPolynomial
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.formats.smtlib.SmtLibAdmission
import com.eignex.klause.formats.smtlib.SmtLibInput
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.lp.engine.LpZeroObjectivePricing
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Bits
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.parseBigInt
import com.eignex.klause.util.plus
import com.eignex.klause.util.times
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class PreparedStrategyAdmissionTest(private val model: String, private val engine: FiniteEngine) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}-{1}")
        fun cases(): List<Array<Any>> = listOf("finite", "open", "wide", "real", "mixed", "polynomial", "nonlinear")
            .flatMap { model -> listOf(FiniteEngine.BACKTRACK, FiniteEngine.LOCAL_SEARCH,
                FiniteEngine.ALNS, FiniteEngine.MIXED).map { arrayOf<Any>(model, it) } }
    }

    @Test
    fun `admitted strategies execute their original source constraints`() {
        val stats: SolveStats
        val plan: PreparedSourcePlan
        if (model == "finite") {
            val source = Problem(0, 2, arrayOf(IntDomain(0, 2), IntDomain(0, 2)), arrayOf(
                AllDifferent(intArrayOf(0, 1), 0, 3),
                Linear(longArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 1),
            ))
            plan = source.prepareExecutionPlan(preferFinite = true)
            var sample: Sample? = null
            val execution = FinitePipeline.solve(FiniteSolveRequest(
                shape = FiniteSolveShape(source, false, false, null, null, null, null,
                    componentPlan = assertNotNull(plan.componentPlan), preparedPlan = plan),
                engine = engine, presolveConfig = PresolveConfig.NONE, explicitPresolveConfig = true,
                solutionSetSensitive = false, cancellation = Cancellation.Never, presolveBudget = null,
                cores = 1, engineParams = emptyList(), randomSeed = 17, defaultArms = 1,
                lpConfig = LpConfig(), zeroObjectivePricing = LpZeroObjectivePricing.MIN_BOUND_SUPPORT,
                nodeBudget = null, solveBudgetMillis = null, allSolutions = false, solutionCap = null,
                deadlineExceeded = { false }, onEvent = null, onPortfolioEvent = null,
            ), FiniteSolveCallbacks(onSample = { sample = it }, onImprovement = { sample = it.sample }))
            val result = assertIs<FiniteSolveOutcome.Completed>(execution.outcome)
            assertEquals(FiniteSolveVerdict.SAT, result.verdict)
            val witness = assertNotNull(sample)
            assertEquals(1L, witness.intValue(0) - witness.intValue(1))
            assertTrue(witness.intValue(0) != witness.intValue(1))
            stats = result.stats
        } else if (model == "polynomial" || model == "nonlinear") {
            val input = SmtLibAdmission.parse(if (model == "polynomial")
                "(declare-const x Real) (assert (= (* x x) 2))"
            else "(declare-const n Int) (assert (= (* n n) 4))")
            val execution = when (input) {
                is SmtLibInput.Polynomial -> {
                    val request = input.problem.pipelineRequest()
                    plan = request.preparedPlan
                    assertSame(input.problem, plan.originalSource)
                    OpenTheoryPipeline.execute(request, PolynomialTheoryParams(strategy = PolynomialStrategyParams(
                        localSearch = com.eignex.klause.localsearch.LocalSearchParams(randomSeed = 17, maxFlips = 12),
                        alnsIterations = 2,
                    )), engine)
                }
                is SmtLibInput.NonlinearInteger -> {
                    plan = input.problem.prepareExecutionPlan()
                    assertSame(input.problem, plan.originalSource)
                    OpenTheoryPipeline.execute(input.problem.pipelineRequest(), engine = engine)
                }
                else -> error("fixture did not retain its polynomial source")
            }
            val witness = assertIs<OpenTheoryResult.Sat>(execution.result).assignment
            if (model == "polynomial") {
                assertEquals(0, AlgebraicArithmetic().signAt(RationalPolynomial.of(-2, 0, 1),
                    assertNotNull(witness.algebraicRealValue(0))))
            } else {
                val value = parseBigInt(witness.intValue(0))
                assertEquals(parseBigInt("4"), value * value)
            }
            assertTrue(execution.stats.workSpent > 0)
            stats = execution.result.stats
        } else {
            val open = Bits(if (model == "open") 2 else 1).also { bits ->
                if (model != "real") for (v in 0 until bits.size) bits.set(v)
            }
            val wide = parseBigInt("9223372036854775808")
            val source = when (model) {
                "real" -> Problem(0, 0, emptyArray(), arrayOf(Linear(intArrayOf(), doubleArrayOf(),
                    intArrayOf(0), doubleArrayOf(3.0), LinearOp.EQ, 1.0)), numRealVars = 1,
                    realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
                    realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))
                "open" -> Problem(0, intBounds = IntBounds.fromModelBounds(longArrayOf(0, 0),
                    longArrayOf(0, 0), open, open), factors = arrayOf(
                    Linear(longArrayOf(1, 1), intArrayOf(0, 1), LinearOp.EQ, 4)))
                "wide" -> Problem(0, intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(0), open, open),
                    factors = arrayOf(Linear(intArrayOf(0), arrayOf(BIG_ONE), LinearOp.EQ, wide)))
                else -> Problem(0, intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(0), open, open),
                    factors = arrayOf(Linear(intArrayOf(0), arrayOf(BIG_ONE), LinearOp.EQ, wide),
                        Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(3.0), LinearOp.EQ, 1.0)),
                    numRealVars = 1, realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
                    realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))
            }
            val request = OpenTheoryRequest(source)
            plan = request.preparedPlan
            val execution = assertIs<OpenTheoryExecution.Satisfy>(OpenTheoryPipeline.execute(request,
                TheoryParams(openWorkLimit = 1000, maxFlips = 20, randomSeed = 17, alnsIterations = 2), engine))
            val witness = assertIs<OpenTheoryResult.Sat>(execution.result).assignment
            assertSame(source, plan.originalSource)
            if (model == "real" || model == "mixed") assertEquals("1/3", witness.realValue(0))
            if (model == "wide" || model == "mixed") assertEquals(wide.toString(), witness.intValue(0))
            if (model == "open") assertEquals(parseBigInt("4"),
                parseBigInt(witness.intValue(0)) + parseBigInt(witness.intValue(1)))
            stats = execution.result.stats
        }
        val admission = plan.capabilities.admission(engine)
        assertTrue(admission.runnable)
        if (engine == FiniteEngine.LOCAL_SEARCH || engine == FiniteEngine.ALNS) {
            assertTrue(stats.ls.moves.sum > 0 || (model == "finite" && stats.ls.incumbentViolation == 0.0))
            assertEquals(setOf(ExecutionClaimAuthority.WITNESS), admission.claimAuthorities)
        }
    }
}
