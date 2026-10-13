package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.QuadraticObjectiveSpec
import com.eignex.klause.ir.QuadraticVariable
import com.eignex.klause.ir.QuadraticCoefficient
import com.eignex.klause.ir.QuadraticObjectiveTerm
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.portfolio.PortfolioWorker
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.solver.evidence.QuadraticEngineDerivation
import com.eignex.klause.solver.evidence.quadraticEngineInvocation
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.incumbent.EvidenceCertificate
import com.eignex.klause.solver.incumbent.QuadraticWitnessPublication
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.QuadraticCertificate
import com.eignex.klause.solver.result.QuadraticClaim
import com.eignex.klause.solver.result.QuadraticIncumbent
import com.eignex.klause.solver.result.QuadraticOptimizationResult
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class QuadraticPortfolioInvocationTest {
    @Test
    fun `a foreign containing source invocation cannot start quadratic workers`() {
        val source = Problem(0, 0, emptyArray(), emptyArray())
        val foreign = Problem(0, 0, emptyArray(), emptyArray())
        val objective = QuadraticObjectiveSpec(emptyList())
        val plan = source.preparePolynomialExecutionPlan().withObjective(objective)
        val invocation = requireNotNull(foreign.preparePolynomialExecutionPlan().quadraticEngineInvocation(
            objective, ObjectiveSense.MINIMIZE, Assumptions.None,
        ))
        val params = PolynomialTheoryParams()
        var entered = 0

        val result = QuadraticPortfolioInvocation(plan, objective, ObjectiveSense.MINIMIZE, params,
            QuadraticNativeScope(params), FiniteEngine.MIXED, 1, Assumptions.None, invocation)
            .execute { _, _, _, _ ->
                entered++
                error("foreign invocation constructed a worker")
            }

        assertIs<QuadraticOptimizationResult.Unknown>(result)
        assertEquals(TerminationReason.Unsupported, result.reason)
        assertEquals(0, entered)
    }

    @Test
    fun `a copied native publication cannot admit a different quadratic point`() {
        val source = Problem(0, 0, emptyArray(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(2.0))
        val objective = QuadraticObjectiveSpec(listOf(QuadraticObjectiveTerm(QuadraticCoefficient.ONE,
            QuadraticVariable.Real(SourceRealId(0)))))
        val original = assertIs<QuadraticOptimizationResult.WithIncumbent>(OpenTheoryPipeline.minimize(
            source.pipelineRequest(), objective, engine = FiniteEngine.BACKTRACK).result).incumbent
        val point = Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(2.0),
            exactReals = listOf(BigFraction.ofLong(2)))
        point.witnessCertificate = original.sample.witnessCertificate
        val plan = source.preparePolynomialExecutionPlan().withObjective(objective)
        val identity = ModelIdentity.of(source, objective)
        val params = PolynomialTheoryParams()

        val result = QuadraticPortfolioInvocation(plan, objective, ObjectiveSense.MINIMIZE, params,
            QuadraticNativeScope(params), FiniteEngine.LOCAL_SEARCH, 1, Assumptions.None)
            .execute { arm, _, _, _ ->
                PortfolioWorker.ofMinimize("copied", arm) { _, _, _, _ ->
                    sequenceOf(MinimizeResult.BestFound(point, 2.0, TerminationReason.BudgetExhausted))
                }.bindEvidence(identity, provesResults = false)
            }

        assertIs<QuadraticOptimizationResult.Unknown>(result)
    }

    @Test
    fun `conditional source refutation cannot finish a weaker quadratic invocation`() {
        val source = Problem(1, 0, emptyArray(), arrayOf(Clause(intArrayOf(Lit.make(0, true)))))
        val objective = QuadraticObjectiveSpec(emptyList())
        val plan = source.preparePolynomialExecutionPlan().withObjective(objective)
        val identity = ModelIdentity.of(source, objective)
        val conditional = requireNotNull(plan.quadraticEngineInvocation(objective, ObjectiveSense.MINIMIZE,
            Assumptions(bools = mapOf(0 to false))))
        val certificate = QuadraticCertificate.engine(identity, conditional, QuadraticClaim.Infeasible,
            QuadraticEngineDerivation.Branches(emptyList()))
        val terminal = QuadraticOptimizationResult.Infeasible(certificate, ObjectiveSense.MINIMIZE, SolveStats.EMPTY)
        val params = PolynomialTheoryParams()
        val native = QuadraticNativeScope(params)
        var entered = 0

        val result = QuadraticPortfolioInvocation(plan, objective, ObjectiveSense.MINIMIZE, params, native,
            FiniteEngine.BACKTRACK, 1, Assumptions.None).execute { arm, _, _, _ ->
            PortfolioWorker.ofMinimize("conditional", arm, resumable = {
                object : QuadraticPortfolioSearch {
                    private var done = false
                    override val completion: QuadraticOptimizationResult? get() = if (done) terminal else null
                    override val isDone: Boolean get() = done
                    override val stats: SolveStats get() = SolveStats.EMPTY
                    override fun runSlice(global: Cancellation, sliceMillis: Long, sliceNodes: Long,
                        onIncumbent: (MinimizeResult.WithSample) -> Unit): MinimizeResult {
                        entered++
                        done = true
                        return MinimizeResult.Unknown(TerminationReason.BudgetExhausted)
                    }
                }
            }) { _, _, _, _ -> error("fixture runs by slice") }.bindEvidence(identity)
        }

        assertIs<QuadraticOptimizationResult.Unknown>(result)
        assertEquals(1, entered)
        assertEquals(1L, result.stats.portfolio.arms.single().faults)
    }

    @Test
    fun `foreign source refutation cannot finish a quadratic invocation`() {
        val source = Problem(1, 0, emptyArray(), arrayOf(Clause(intArrayOf(Lit.make(0, true)))))
        val objective = QuadraticObjectiveSpec(emptyList())
        val plan = source.preparePolynomialExecutionPlan().withObjective(objective)
        val identity = ModelIdentity.of(source, objective)
        val foreign = Problem(1, 0, emptyArray(), arrayOf(Clause(intArrayOf(Lit.make(0, true)))))
        val foreignPlan = foreign.preparePolynomialExecutionPlan().withObjective(objective)
        val invocation = requireNotNull(foreignPlan.quadraticEngineInvocation(objective, ObjectiveSense.MINIMIZE,
            Assumptions.None))
        val certificate = QuadraticCertificate.engine(ModelIdentity.of(foreign, objective), invocation,
            QuadraticClaim.Infeasible, QuadraticEngineDerivation.Branches(emptyList()))
        val terminal = QuadraticOptimizationResult.Infeasible(certificate, ObjectiveSense.MINIMIZE, SolveStats.EMPTY)
        val params = PolynomialTheoryParams()
        val native = QuadraticNativeScope(params)
        var entered = 0

        val result = QuadraticPortfolioInvocation(plan, objective, ObjectiveSense.MINIMIZE, params, native,
            FiniteEngine.BACKTRACK, 1, Assumptions.None).execute { arm, _, _, _ ->
            PortfolioWorker.ofMinimize("conditional", arm, resumable = {
                object : QuadraticPortfolioSearch {
                    private var done = false
                    override val completion: QuadraticOptimizationResult? get() = if (done) terminal else null
                    override val isDone: Boolean get() = done
                    override val stats: SolveStats get() = SolveStats.EMPTY
                    override fun runSlice(global: Cancellation, sliceMillis: Long, sliceNodes: Long,
                        onIncumbent: (MinimizeResult.WithSample) -> Unit): MinimizeResult {
                        entered++
                        done = true
                        return MinimizeResult.Unknown(TerminationReason.BudgetExhausted)
                    }
                }
            }) { _, _, _, _ -> error("fixture runs by slice") }.bindEvidence(identity)
        }

        assertIs<QuadraticOptimizationResult.Unknown>(result)
        assertEquals(1, entered)
        assertEquals(1L, result.stats.portfolio.arms.single().faults)
    }
    @Test
    fun `heuristic worker cannot finish a quadratic source proof`() {
        val source = Problem(1, 0, emptyArray(), arrayOf(Clause(intArrayOf(Lit.make(0, true)))))
        val objective = QuadraticObjectiveSpec(emptyList())
        val plan = source.preparePolynomialExecutionPlan().withObjective(objective)
        val identity = ModelIdentity.of(source, objective)
        val conditional = requireNotNull(plan.quadraticEngineInvocation(objective, ObjectiveSense.MINIMIZE,
            Assumptions.None))
        val certificate = QuadraticCertificate.engine(identity, conditional, QuadraticClaim.Infeasible,
            QuadraticEngineDerivation.Branches(emptyList()))
        val terminal = QuadraticOptimizationResult.Infeasible(certificate, ObjectiveSense.MINIMIZE, SolveStats.EMPTY)
        val params = PolynomialTheoryParams()
        val native = QuadraticNativeScope(params)
        var entered = 0

        val result = QuadraticPortfolioInvocation(plan, objective, ObjectiveSense.MINIMIZE, params, native,
            FiniteEngine.LOCAL_SEARCH, 1, Assumptions.None).execute { arm, _, _, _ ->
            PortfolioWorker.ofMinimize("conditional", arm, resumable = {
                object : QuadraticPortfolioSearch {
                    private var done = false
                    override val completion: QuadraticOptimizationResult? get() = if (done) terminal else null
                    override val isDone: Boolean get() = done
                    override val stats: SolveStats get() = SolveStats.EMPTY
                    override fun runSlice(global: Cancellation, sliceMillis: Long, sliceNodes: Long,
                        onIncumbent: (MinimizeResult.WithSample) -> Unit): MinimizeResult {
                        entered++
                        done = true
                        return MinimizeResult.Unknown(TerminationReason.BudgetExhausted)
                    }
                }
            }) { _, _, _, _ -> error("fixture runs by slice") }.bindEvidence(identity, provesResults = false)
        }

        assertIs<QuadraticOptimizationResult.Unknown>(result)
        assertEquals(1, entered)
        assertEquals(1L, result.stats.portfolio.arms.single().faults)
    }

    @Test
    fun `a ray certificate retains its private witness without republishing it over the accepted incumbent`() {
        val source = Problem(0, 0, emptyArray(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))
        val x = QuadraticVariable.Real(SourceRealId(0))
        val objective = QuadraticObjectiveSpec(listOf(QuadraticObjectiveTerm(QuadraticCoefficient.ofLong(-1), x)))
        val terminal = assertIs<QuadraticOptimizationResult.Unbounded>(OpenTheoryPipeline.minimize(
            source.pipelineRequest(), objective, engine = FiniteEngine.BACKTRACK).result)
        val arithmetic = AlgebraicArithmetic()
        val point = Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(2.0),
            algebraicReals = listOf(arithmetic.rational(BigFraction.ofLong(2))))
        val plan = source.preparePolynomialExecutionPlan().withObjective(objective)
        val identity = ModelIdentity.of(source, objective)
        val invocation = requireNotNull(plan.quadraticEngineInvocation(objective, ObjectiveSense.MINIMIZE,
            Assumptions.None))
        point.witnessCertificate = EvidenceCertificate.published(identity, QuadraticWitnessPublication(invocation,
            QuadraticIncumbent(point, arithmetic.rational(BigFraction.ofLong(-2)))))
        val params = PolynomialTheoryParams()
        val observer = QuadraticPublicationObserver(null)

        val result = QuadraticPortfolioInvocation(plan, objective, ObjectiveSense.MINIMIZE, params,
            QuadraticNativeScope(params), FiniteEngine.BACKTRACK, 1, Assumptions.None)
            .execute(onAccepted = { observer.accepted(it.objective, it.version) }) { arm, _, _, _ ->
                PortfolioWorker.ofMinimize("complete", arm, resumable = {
                    object : QuadraticPortfolioSearch {
                        private var done = false
                        override val completion: QuadraticOptimizationResult? get() = if (done) terminal else null
                        override val isDone: Boolean get() = done
                        override val stats: SolveStats get() = SolveStats.EMPTY
                        override fun runSlice(global: Cancellation, sliceMillis: Long, sliceNodes: Long,
                            onIncumbent: (MinimizeResult.WithSample) -> Unit): MinimizeResult {
                            onIncumbent(MinimizeResult.BestFound(point, -2.0, TerminationReason.BudgetExhausted))
                            done = true
                            return MinimizeResult.BestFound(terminal.incumbent.sample, 0.0,
                                TerminationReason.BudgetExhausted)
                        }
                    }
                }) { _, _, _, _ -> error("fixture runs by slice") }.bindEvidence(identity)
            }
        val execution = QuadraticOptimizationExecution(result, PolynomialTheoryStats(), 0, 0).also {
            it.retainPublication(observer.latest)
        }

        assertSame(point, execution.acceptedIncumbent?.incumbent?.sample)
        assertEquals(BigFraction.ofLong(-2), execution.acceptedIncumbent?.incumbent?.value?.rational)
        assertSame(terminal.incumbent.sample, assertIs<QuadraticOptimizationResult.Unbounded>(result).incumbent.sample)
        assertEquals(1L, execution.acceptedIncumbent?.version)
    }
}
