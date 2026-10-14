package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.factor.arithmetic.RealProduct
import com.eignex.klause.formats.smtlib.SmtLibAdmission
import com.eignex.klause.formats.smtlib.SmtLibInput
import com.eignex.klause.formats.smtlib.SmtLibPolynomial
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.propagation.BuiltInPropagationProjectionProvider
import com.eignex.klause.propagation.NoPropagator
import com.eignex.klause.propagation.PropagationProjection
import com.eignex.klause.propagation.PropagationProjectionProvider
import com.eignex.klause.solver.AssignmentCheckCapability
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.solver.PropagationCapability
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.incumbent.EngineInvocation
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.theory.qfnra.QfNraParams
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PolynomialStrategyAdaptersTest {
    @Test
    fun `polynomial finite preparation opens the selected source deduction`() {
        val clause = Clause(intArrayOf(0))
        val source = Problem(1, 0, emptyArray(), arrayOf(clause, RealProduct(0, 0, 0, 1.0, 1.0)),
            numRealVars = 1, realLower = doubleArrayOf(1.0), realUpper = doubleArrayOf(1.0))
        var opened = 0
        val providers = FactorProjectionProviders(propagation = PropagationProjectionProvider { factor ->
            if (factor !== clause) BuiltInPropagationProjectionProvider.select(factor)
            else PropagationProjection(PropagationCapability.INERT, AssignmentCheckCapability.SOURCE_CERTIFICATION,
                "The original clause is checked at complete points.") {
                opened++
                NoPropagator
            }
        })
        val request = source.pipelineRequest().copy(preparedPlan = source.prepareExecutionPlan(providers))

        val result = OpenTheoryPipeline.execute(request, engine = FiniteEngine.BACKTRACK).result

        assertEquals(true, assertIs<OpenTheoryResult.Sat>(result).assignment.boolValue(0))
        assertTrue(opened > 0)
    }

    @Test
    fun `shared finite objective keeps exact completion across optimization strategies`() {
        val source = Problem(
            numBoolVars = 0, numIntVars = 1, intDomains = arrayOf(IntDomain(0, 2)),
            factors = arrayOf(RealProduct(0, 0, 0, 1.0, 1.0)),
            numRealVars = 1, realLower = doubleArrayOf(1.0), realUpper = doubleArrayOf(1.0),
        )
        val request = source.pipelineRequest()
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val params = PolynomialTheoryParams(strategy = PolynomialStrategyParams(
            backtrack = BacktrackParams(randomSeed = 17),
            localSearch = LocalSearchParams(randomSeed = 17, maxFlips = 30),
            alnsIterations = 2,
        ))
        val engines = listOf(FiniteEngine.BACKTRACK, FiniteEngine.LOCAL_SEARCH, FiniteEngine.ALNS, FiniteEngine.MIXED)

        for (engine in engines) {
            val result = OpenTheoryPipeline.minimize(request, objective, params, engine).result

            val incumbent = assertIs<MinimizeResult.WithSample>(result)
            assertEquals(1L, incumbent.sample.intValue(0))
            assertEquals(1.0, incumbent.objective)
            assertEquals(1.0, assertNotNull(incumbent.sample.algebraicReals).single().rational?.toDouble())
            if (engine == FiniteEngine.BACKTRACK || engine == FiniteEngine.MIXED) {
                assertIs<MinimizeResult.Optimal>(result, "$engine: $result")
            } else {
                assertIs<MinimizeResult.BestFound>(result)
            }
        }
    }


    @Test
    fun `complete finite exhaustion refutes every conditioned polynomial branch`() {
        val formula = SmtLibPolynomial.parse("(declare-const x Real) (assert (= (* x x) 2))")
        val source = Problem(
            numBoolVars = 0, numIntVars = 1, intDomains = arrayOf(IntDomain(0, 0)),
            factors = arrayOf(RealProduct(0, 0, 0, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)),
            numRealVars = 1, realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
        )
        val shared = assertNotNull(SharedPolynomialModel.assemble(source, formula.formula))

        shared.service.invocation().use { invocation ->
            assertIs<SolveResult.Unsat>(PolynomialWorkerAdapter(
                shared, invocation, PolynomialStrategyParams(),
                EngineInvocation(source.prepareExecutionPlan(), ModelIdentity.of(source)), Cancellation.Never,
            ).solve(FiniteEngine.BACKTRACK))
        }
    }

    @Test
    fun `heuristic failure carries no global refutation`() {
        val formula = SmtLibPolynomial.parse("(declare-const x Real) (assert (= (* x x) (- 1)))")
        val source = Problem(
            numBoolVars = 0, numIntVars = 0, intDomains = emptyArray(), factors = emptyArray(),
            numRealVars = 1, realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
        )
        val shared = assertNotNull(SharedPolynomialModel.assemble(source, formula.formula))

        shared.service.invocation().use { invocation ->
            assertIs<SolveResult.Unknown>(PolynomialWorkerAdapter(
                shared, invocation,
                PolynomialStrategyParams(localSearch = LocalSearchParams(maxFlips = 2)),
                EngineInvocation(source.prepareExecutionPlan(), ModelIdentity.of(source)), Cancellation.Never,
            ).solve(FiniteEngine.LOCAL_SEARCH))
        }
    }

    @Test
    fun `finite assumptions cannot leak across polynomial invocations`() {
        val formula = SmtLibPolynomial.parse("(declare-const x Real) (assert (= (* x x) 2))")
        val source = Problem(
            numBoolVars = 0, numIntVars = 1, intDomains = arrayOf(IntDomain(0, 1)),
            factors = arrayOf(RealProduct(0, 0, 0, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)),
            numRealVars = 1, realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
        )
        val shared = assertNotNull(SharedPolynomialModel.assemble(source, formula.formula))

        for (pin in listOf(0L, 1L, 0L)) {
            shared.service.invocation(QfNraParams()).use { invocation ->
                val params = PolynomialStrategyParams(assumptions = Assumptions(ints = mapOf(0 to pin)))
                val binding = EngineInvocation(source.prepareExecutionPlan(), ModelIdentity.of(source),
                    sourceAssumptions = params.assumptions)
                val result = PolynomialWorkerAdapter(shared, invocation, params, binding, Cancellation.Never)
                    .solve(FiniteEngine.BACKTRACK)
                if (pin == 0L) assertIs<SolveResult.Unsat>(result) else assertIs<SolveResult.Sat>(result)
            }
        }
    }
    @Test
    fun `pure real heuristic arms repair coordinates before publishing algebraic witnesses`() {
        val input = assertIs<SmtLibInput.Polynomial>(SmtLibAdmission.parse(
            "(declare-const x Real) (assert (= (* x x) 2))",
        ))
        val request = input.problem.pipelineRequest()
        val params = PolynomialTheoryParams(strategy = PolynomialStrategyParams(
            localSearch = LocalSearchParams(randomSeed = 17, maxFlips = 12), alnsIterations = 0,
        ))

        for (engine in listOf(FiniteEngine.LOCAL_SEARCH, FiniteEngine.ALNS)) {
            val execution = OpenTheoryPipeline.execute(request, params, engine)

            val result = assertIs<OpenTheoryResult.Sat>(execution.result, "$engine: $execution")
            assertNotNull(result.assignment.algebraicRealValue(0))
            kotlin.test.assertTrue(result.stats.ls.completionsRefuted.sum > 0)
            kotlin.test.assertTrue(result.stats.ls.moves.sum > 1)
        }
    }

    @Test
    fun `pure real heuristic arms respect caller cancellation and exhausted node allowances`() {
        val input = assertIs<SmtLibInput.Polynomial>(SmtLibAdmission.parse(
            "(declare-const x Real) (assert (= (* x x) 2))",
        ))
        val request = input.problem.pipelineRequest()
        val controls = listOf(
            LocalSearchParams(cancellation = Cancellation { true }),
            LocalSearchParams(nodeBudget = NodeBudget(0)),
        )

        for (local in controls) for (engine in listOf(FiniteEngine.LOCAL_SEARCH, FiniteEngine.ALNS)) {
            val execution = OpenTheoryPipeline.execute(request,
                PolynomialTheoryParams(strategy = PolynomialStrategyParams(localSearch = local)), engine)

            assertIs<OpenTheoryResult.Unknown>(execution.result)
            assertEquals(0.0, execution.result.stats.ls.completions.sum)
            assertEquals(0L, execution.stats.workSpent)
        }
    }

    @Test
    fun `complete polynomial portfolios retain finite cost improvements below floating precision`() {
        val source = Problem(0, 2, arrayOf(IntDomain(0, 2), IntDomain(0, 2)),
            arrayOf(RealProduct(0, 0, 0, 1.0, 1.0)), numRealVars = 1,
            realLower = doubleArrayOf(1.0), realUpper = doubleArrayOf(1.0))
        val request = source.pipelineRequest()
        val params = PolynomialTheoryParams(strategy = PolynomialStrategyParams(
            backtrack = BacktrackParams(randomSeed = 17),
            localSearch = LocalSearchParams(randomSeed = 17, maxFlips = 30), alnsIterations = 2,
        ))

        for (offset in listOf(false, true)) {
            val objective = if (offset) LinearObjective(intCoefficients = longArrayOf(0, -1), constant = 1L shl 62)
                else LinearObjective(intCoefficients = longArrayOf(1L shl 62, -1))
            for (engine in listOf(FiniteEngine.BACKTRACK, FiniteEngine.FIXED, FiniteEngine.MIXED)) {
                val result = OpenTheoryPipeline.minimize(request, objective, params, engine).result

                val sample = assertIs<MinimizeResult.Optimal>(result).sample
                assertEquals(1L, sample.intValue(0))
                assertEquals(2L, sample.intValue(1))
            }
        }
    }

    @Test
    fun `parallel polynomial replicas preserve original finite and algebraic coordinates`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 2)),
            arrayOf(RealProduct(0, 0, 0, 1.0, 1.0)), numRealVars = 1,
            realLower = doubleArrayOf(1.0), realUpper = doubleArrayOf(1.0))
        val request = source.pipelineRequest()
        val params = PolynomialTheoryParams(strategy = PolynomialStrategyParams(
            backtrack = BacktrackParams(randomSeed = 17),
            localSearch = LocalSearchParams(randomSeed = 17, maxFlips = 64), alnsIterations = 8,
        ))

        for (engine in listOf(FiniteEngine.MIXED, FiniteEngine.BACKTRACK,
            FiniteEngine.LOCAL_SEARCH, FiniteEngine.ALNS)) {
            val result = OpenTheoryPipeline.execute(request, params, engine, 8).result

            val sat = assertIs<OpenTheoryResult.Sat>(result)
            assertEquals("1", sat.assignment.intValue(0))
            assertEquals(1.0, assertNotNull(sat.assignment.algebraicRealValue(0)).rational?.toDouble())
            assertEquals(8, sat.stats.portfolio.arms.size)
        }
    }

    @Test
    fun `parallel finite polynomial optimization preserves exact cost below floating precision`() {
        val source = Problem(0, 2, arrayOf(IntDomain(0, 2), IntDomain(0, 2)),
            arrayOf(RealProduct(0, 0, 0, 1.0, 1.0)), numRealVars = 1,
            realLower = doubleArrayOf(1.0), realUpper = doubleArrayOf(1.0))
        val objective = LinearObjective(intCoefficients = longArrayOf(1L shl 62, -1))
        val params = PolynomialTheoryParams(strategy = PolynomialStrategyParams(
            backtrack = BacktrackParams(randomSeed = 17),
            localSearch = LocalSearchParams(randomSeed = 17, maxFlips = 8), alnsIterations = 1,
        ))

        val result = OpenTheoryPipeline.minimize(source.pipelineRequest(), objective, params, FiniteEngine.MIXED, 4)

        val optimal = assertIs<MinimizeResult.Optimal>(result.result)
        assertEquals(1L, optimal.sample.intValue(0))
        assertEquals(2L, optimal.sample.intValue(1))
        assertEquals(4, optimal.stats.portfolio.arms.size)
    }

    @Test
    fun `fixed polynomial execution ignores additional worker requests`() {
        val source = Problem(0, 1, arrayOf(IntDomain(1, 1)),
            arrayOf(RealProduct(0, 0, 0, 1.0, 1.0)), numRealVars = 1,
            realLower = doubleArrayOf(1.0), realUpper = doubleArrayOf(1.0))

        val result = OpenTheoryPipeline.execute(source.pipelineRequest(), PolynomialTheoryParams(),
            FiniteEngine.FIXED, 8).result

        assertIs<OpenTheoryResult.Sat>(result)
        assertTrue(result.stats.portfolio.arms.isEmpty())
    }

}
