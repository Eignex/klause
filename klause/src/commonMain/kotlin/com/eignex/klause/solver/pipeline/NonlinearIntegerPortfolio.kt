package com.eignex.klause.solver.pipeline

import com.eignex.klause.portfolio.ArmFamily
import com.eignex.klause.portfolio.PortfolioEvidence
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.incumbent.CandidateVerifier
import com.eignex.klause.solver.incumbent.EvidenceKind
import com.eignex.klause.solver.incumbent.EngineInvocation
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason

internal class NonlinearIntegerPortfolio(
    private val request: NonlinearIntegerTheoryRequest,
    private val params: NonlinearIntegerTheoryParams,
    private val cores: Int,
) {
    fun execute(): PolynomialTheoryExecution {
        val plan = request.preparedPlan
        if (!plan.capabilities.admission(FiniteEngine.MIXED).runnable) return PolynomialTheoryExecution(
            OpenTheoryResult.Unknown(TerminationReason.Unsupported, SolveStats.EMPTY), PolynomialTheoryStats())
        val polynomial = params.polynomial
        val scope = QuadraticNativeScope(polynomial)
        val identity = ModelIdentity.of(request.problem)
        val binding = EngineInvocation(plan, identity, sourceAssumptions = polynomial.strategy.assumptions)
        val workers = ArrayList<NonlinearIntegerInvocation>()
        val families = listOf(ArmFamily.Backtrack, ArmFamily.LocalSearch, ArmFamily.Lns)
        val factories = families.mapIndexed { arm, family ->
            PortfolioArmFactory(plan, arm, family) { slot ->
                val native = scope.worker(slot.index, polynomial.maximumWork?.let(slot::nativeAllowance))
                val strategy = polynomial.strategy
                val controls = params.copy(
                    polynomial = polynomial.copy(cancellation = native.polynomial, maximumWork = null,
                        strategy = strategy.copy(
                            backtrack = strategy.backtrack.copy(randomSeed = slot.seed,
                                maxDecisions = slot.nativeAllowance(strategy.backtrack.maxDecisions),
                                nodeBudget = slot.nodeBudget),
                            localSearch = strategy.localSearch.copy(randomSeed = slot.seed,
                                maxFlips = slot.heuristicAllowance(strategy.localSearch.maxFlips),
                                maxInstructions = strategy.localSearch.maxInstructions?.let(slot::heuristicAllowance),
                                nodeBudget = slot.nodeBudget))),
                    maximumCandidates = slot.nativeAllowance(params.maximumCandidates),
                    maximumMoves = slot.heuristicAllowance(params.maximumMoves), nodeBudget = slot.nodeBudget,
                )
                val engine = when (arm) {
                    0 -> FiniteEngine.BACKTRACK
                    1 -> FiniteEngine.LOCAL_SEARCH
                    else -> FiniteEngine.ALNS
                }
                val worker = NonlinearIntegerInvocation(request, controls, engine)
                workers += worker
                slot.own(AutoCloseable { native.observe(worker.nativeStats) })
                worker.worker(arm, slot).bindInvocation(binding)
            }
        }
        val invocation = PortfolioInvocation(plan, factories,
            PortfolioInvocationControls(parallelism = cores,
                seed = polynomial.strategy.localSearch.randomSeed ?: 1L, cancellation = scope.polynomial,
                nodeBudget = params.nodeBudget ?: polynomial.strategy.backtrack.nodeBudget
                    ?: polynomial.strategy.localSearch.nodeBudget, reseedStaleThreshold = 0),
            PortfolioEvidence(identity, CandidateVerifier { candidate ->
                val certificate = candidate.assignment.witnessCertificate
                if (certificate?.kind == EvidenceKind.Witness && certificate.model.sameModel(identity)) {
                    Verification.Accepted(candidate)
                } else Verification.Indeterminate("native nonlinear witness has no matching source check")
            }).bindInvocation(binding, scope.polynomial)) { materialized ->
            val complete = materialized.indices.filter { materialized[it].family == ArmFamily.Backtrack }
            PortfolioInvocationPolicy(shares = DoubleArray(materialized.size) {
                if (it in complete) 0.75 / complete.size else 0.0
            })
        }
        val result = invocation.use { it.solve(scope.polynomial) }
        val published = when (result) {
            is SolveResult.Sat -> OpenTheoryResult.Sat(nonlinearIntegerAssignment(result.assignment), result.stats)
            is SolveResult.Unsat -> OpenTheoryResult.Unsat(result.stats)
            is SolveResult.Unknown -> OpenTheoryResult.Unknown(if (polynomial.timeout()) TerminationReason.Timeout
                else if (polynomial.cancellation()) TerminationReason.Cancelled else result.reason, result.stats)
        }
        return PolynomialTheoryExecution(published, scope.polynomialStats, workers.firstNotNullOfOrNull { it.resource })
    }
}
