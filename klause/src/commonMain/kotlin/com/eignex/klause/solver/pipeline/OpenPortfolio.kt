package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchEngine
import com.eignex.klause.localsearch.LocalSearchModel
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.localSearchSupports
import com.eignex.klause.portfolio.Kind
import com.eignex.klause.portfolio.LeafRealCompletion
import com.eignex.klause.portfolio.LocalSearchCatalog
import com.eignex.klause.portfolio.Portfolio
import com.eignex.klause.portfolio.PortfolioWorker
import com.eignex.klause.portfolio.WitnessCheck
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation

/**
 * Decides an open model's satisfiability on the shared [Portfolio] harness: the complete theory route as one arm,
 * local-search arms over the model's source columns as the rest.
 *
 * The theory arm has no pause point, so each segment it is scheduled reruns it, preparation included, under a
 * growing time slice, through the same entry point the default open route uses.
 *
 * Local search proposes witnesses only, never refuting an open model, and every witness it proposes is checked
 * against the source model before it counts. A model no theory decides ([request] null) runs local search alone,
 * so it can be shown satisfiable and never shown unsatisfiable.
 */
internal class OpenPortfolio(
    private val model: Problem,
    private val request: OpenTheoryRequest?,
    private val theoryParams: TheoryParams,
    private val localSearchArms: Int = DEFAULT_LS_ARMS,
    private val seed: Long = 0L,
) {
    // The theory arm's last witness, which may hold integers past the 64-bit range a Sample cannot carry.
    private var theoryWitness: OpenTheoryAssignment? = null
    private var theorySample: Sample? = null

    /** Run the portfolio until an arm settles the model or [cancellation] fires. */
    fun solve(cancellation: Cancellation): OpenTheoryResult {
        val firstLocalArm = if (request != null) 1 else 0
        val localSearch = localSearchWorkers(firstLocalArm)
        if (localSearch.isEmpty()) {
            // With the theory as the only arm there is nothing to schedule, and slicing it would only rerun it
            // from scratch each segment.
            return request?.let { decide(it, theoryParams) }
                ?: OpenTheoryResult.Unknown(TerminationReason.Unsupported, SolveStats.EMPTY)
        }
        val workers = buildList {
            request?.let { add(theoryWorker(it, armId = 0)) }
            addAll(localSearch)
        }
        val portfolio = Portfolio.thompson(workers, lanes = 1, seed = seed, witnessCheck = witnessCheck())
        val result = portfolio.use { it.solve(cancellation) }
        return when (result) {
            is SolveResult.Sat -> OpenTheoryResult.Sat(witnessOf(result.assignment), result.stats)
            is SolveResult.Unsat -> OpenTheoryResult.Unsat(result.stats)
            is SolveResult.Unknown -> OpenTheoryResult.Unknown(result.reason, result.stats)
        }
    }

    // The theory arm's own witness when it settled the run, else the checked local-search sample.
    private fun witnessOf(sample: Sample): OpenTheoryAssignment {
        val theory = theoryWitness
        return if (sample === theorySample && theory != null) theory else OpenTheoryAssignment.Sampled(sample)
    }

    // The theory exactly as the default open route decides it.
    private fun decide(request: OpenTheoryRequest, params: TheoryParams): OpenTheoryResult =
        (OpenTheoryPipeline.execute(request, params) as OpenTheoryExecution.Satisfy).result

    private fun theoryWorker(request: OpenTheoryRequest, armId: Int): PortfolioWorker =
        PortfolioWorker.ofSolve("theory/${request.route.name.lowercase()}", armId) { slice, _ ->
            when (val r = decide(request, theoryParams.copy(cancellation = theoryParams.cancellation or slice))) {
                is OpenTheoryResult.Sat -> {
                    theoryWitness = r.assignment
                    val sample = r.assignment.toSampleOrPlaceholder(model)
                    theorySample = sample
                    SolveResult.Sat(sample, r.stats)
                }

                is OpenTheoryResult.Unsat -> SolveResult.Unsat(stats = r.stats)

                is OpenTheoryResult.Unknown -> SolveResult.Unknown(r.reason, r.stats)
            }
        }

    private fun localSearchWorkers(firstArm: Int): List<PortfolioWorker> {
        // On a model of continuous columns alone with no Boolean to choose, the completion of any candidate is the
        // whole LP: local search would only hand the theory the problem it already solves.
        if (model.numIntVars == 0 && model.numBoolVars == 0) return emptyList()
        val searchModel = LocalSearchModel.open(model)
        val completion = if (model.numRealVars > 0) LeafRealCompletion(model, objective = null) else null
        if (!localSearchSupports(searchModel, completes = completion != null)) return emptyList()
        return LocalSearchCatalog.diverse(Kind.CSP, localSearchArms).mapIndexed { i, recipe ->
            val engine = LocalSearchEngine(
                searchModel,
                strategy = recipe.strategy,
                optimizeStrategy = recipe.optimizeStrategy,
                seedImplicitOnRestart = recipe.seedImplicitOnRestart,
                completion = completion,
            )
            PortfolioWorker.ofSolve("ls/${recipe.label}", firstArm + i, countsInstructions = true) { slice, budget ->
                engine.solve(
                    LocalSearchParams(randomSeed = seed + i, maxInstructions = budget, cancellation = slice),
                    warm = null,
                )
            }
        }
    }

    // The theory arm's witness is exact by construction; a local-search witness is re-derived from the source model.
    private fun witnessCheck(): WitnessCheck = WitnessCheck { sample, _ ->
        if (sample === theorySample) null else refuteOpenWitness(model, sample)
    }

    private companion object {
        const val DEFAULT_LS_ARMS: Int = 3
    }
}
