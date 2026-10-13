package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.portfolio.ArmFamily
import com.eignex.klause.portfolio.PortfolioEvidence
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.incumbent.CandidateVerifier
import com.eignex.klause.solver.incumbent.EvidenceKind
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.util.Cancellation

/** Finite strategy controls for polynomial satisfaction and finite-objective optimization. */
data class PolynomialStrategyParams(
    /** Complete finite traversal parameters; theory components are supplied by the pipeline. */
    val backtrack: BacktrackParams = BacktrackParams(),
    /** Finite heuristic walk parameters. Continuous completion is owned by the pipeline. */
    val localSearch: LocalSearchParams = LocalSearchParams(),
    /** Common finite pins captured for this invocation. */
    val assumptions: Assumptions = Assumptions.None,
    /** ALNS destroy/repair iteration cap. */
    val alnsIterations: Int = 50,
) {
    init { require(alnsIterations >= 0) }
}

internal class PolynomialStrategyAdapters(
    private val plan: PreparedSourcePlan,
    private val model: SharedPolynomialModel,
    private val scope: PolynomialInvocationScope,
    private val params: PolynomialStrategyParams,
    private val cancellation: Cancellation,
    private val parallelism: Int,
) {
    fun solve(engine: FiniteEngine): SolveResult = if (engine == FiniteEngine.MIXED) {
        portfolio().use { it.solve(cancellation) }
    } else standalone(engine) { it.solve(engine) }

    fun minimize(objective: LinearObjective, engine: FiniteEngine): MinimizeResult =
        if (engine == FiniteEngine.MIXED) {
            portfolio(objective).use { it.minimize(cancellation) }
        } else standalone(engine) { it.minimize(objective, engine) }

    private fun <T> standalone(engine: FiniteEngine, action: (PolynomialWorkerAdapter) -> T): T {
        val complete = engine == FiniteEngine.BACKTRACK || engine == FiniteEngine.FIXED
        val budget = if (complete) params.backtrack.nodeBudget else params.localSearch.nodeBudget
        return PortfolioInvocationResources(1, 1, params.localSearch.randomSeed ?: 0,
            cancellation, budget, if (complete) setOf(0) else emptySet()).use { resources ->
            val slot = resources.slots.single()
            action(PolynomialWorkerAdapter(model, scope.worker(slot), params, scope.workerCancellation(slot)))
        }
    }

    private fun workerParams(slot: PortfolioWorkerSlot): PolynomialStrategyParams = params.copy(
        backtrack = params.backtrack.copy(randomSeed = slot.seed,
            maxDecisions = slot.nativeAllowance(params.backtrack.maxDecisions), nodeBudget = slot.nodeBudget),
        localSearch = params.localSearch.copy(randomSeed = slot.seed,
            maxFlips = slot.heuristicAllowance(params.localSearch.maxFlips),
            maxInstructions = params.localSearch.maxInstructions?.let(slot::heuristicAllowance),
            nodeBudget = slot.nodeBudget),
    )

    private fun portfolio(objective: LinearObjective? = null): PortfolioInvocation {
        val identity = ModelIdentity.of(model.source, objective)
        val families = listOf(ArmFamily.Backtrack, ArmFamily.LocalSearch, ArmFamily.Lns)
        val factories = families.mapIndexed { arm, family ->
            PortfolioArmFactory(plan, arm, family) { slot ->
                PolynomialWorkerAdapter(model, scope.worker(slot), workerParams(slot), scope.workerCancellation(slot))
                    .worker(arm, objective, slot)
            }
        }
        return PortfolioInvocation(plan, factories,
            PortfolioInvocationControls(parallelism = parallelism,
                seed = params.localSearch.randomSeed ?: 0, cancellation = cancellation,
                nodeBudget = params.backtrack.nodeBudget ?: params.localSearch.nodeBudget, reseedStaleThreshold = 0),
            PortfolioEvidence(identity, CandidateVerifier { candidate ->
                val certificate = candidate.assignment.witnessCertificate
                if (certificate?.kind == EvidenceKind.Witness &&
                    certificate.model.sameModel(ModelIdentity.of(model.source))) Verification.Accepted(candidate)
                else Verification.Indeterminate("polynomial source witness has no matching exact certificate")
            }), objective)
    }
}
