package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.composedFixpoint
import com.eignex.klause.localsearch.CandidateCompletion
import com.eignex.klause.localsearch.Completion
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.meta.alns.Alns
import com.eignex.klause.meta.alns.BacktrackRepair
import com.eignex.klause.portfolio.PortfolioWorker
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.bake
import com.eignex.klause.propagation.conditionedRoot
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.incumbent.Candidate
import com.eignex.klause.solver.incumbent.CompletedEnginePublication
import com.eignex.klause.solver.incumbent.EngineInvocation
import com.eignex.klause.solver.incumbent.EvidenceCertificate
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.theory.qfnra.PolynomialCondition
import com.eignex.klause.theory.qfnra.PolynomialSearchComponent
import com.eignex.klause.theory.qfnra.PolynomialTheoryInvocation
import com.eignex.klause.theory.qfnra.QfNraReason
import com.eignex.klause.theory.qfnra.QfNraResult
import com.eignex.klause.theory.qfnra.QfNraStats
import com.eignex.klause.util.Cancellation

internal class PolynomialWorkerAdapter(
    private val model: SharedPolynomialModel,
    private val invocation: PolynomialTheoryInvocation,
    private val params: PolynomialStrategyParams,
    private val binding: EngineInvocation,
    cancellation: Cancellation,
) {
    private val cancellation = cancellation or Cancellation {
        invocation.workExhausted || invocation.lastUnknown?.reason == QfNraReason.ResourceLimit
    }
    private val finite: BakedProblem = binding.preparedPlan.finiteFactorProjections?.let { selected ->
        model.finite.bake(selected.forEngineView(model.finite), this.cancellation)
    } ?: model.finite.bake(this.cancellation)
    private val enforcingFactors = model.finiteEnforcingFactors
    private val fixed = if (params.assumptions.isEmpty) finite.fixedDiscreteSample() else {
        finite.conditionedRoot(params.assumptions, this.cancellation).fixedDiscreteSample()
    }

    private fun neighborhood(adaptive: Boolean, objective: LinearObjective? = null): PortfolioWorker? {
        val point = fixed ?: return null
        val adapter = PolynomialExactNeighborhood(model, invocation, finite, point, params.assumptions,
            objective, ::certified)
        return exactNeighborhoodWorker(if (adaptive) "alns+exact-nra-neighborhood" else "ls+exact-nra-neighborhood",
            if (adaptive) 2 else 1, objective, cancellation or params.localSearch.cancellation,
            exhausted = { cancellation() }, open = {
                adapter.search(adaptive, params.localSearch.randomSeed ?: 0,
                    params.localSearch.maxFlips, params.localSearch.maxInstructions ?: Long.MAX_VALUE,
                    if (adaptive) params.alnsIterations.toLong() else null, params.localSearch.nodeBudget)
            }).bindEvidence(ModelIdentity.of(model.source, objective), provesResults = false)
    }

    private fun certified(sample: Sample): Sample = sample.also {
        val value = binding.executionObjective?.evaluateExact(it)?.toDouble()
        it.witnessCertificate = EvidenceCertificate.published(CompletedEnginePublication.Witness(binding, it, value))
    }

    private fun backtrackParams(): BacktrackParams = params.backtrack.withAssumptions(params.assumptions).copy(
        cancellation = params.backtrack.cancellation or cancellation,
        componentFactory = {
            params.backtrack.componentFactory?.invoke().orEmpty() + PolynomialSearchComponent(
                model.service, invocation, model.integerColumns,
            ) { assignment, context ->
                certified(model.capture(
                    assignment,
                    BooleanArray(model.source.numBoolVars) { requireNotNull(context.boolValue(it)) },
                    LongArray(model.source.numIntVars) { id ->
                        val value = requireNotNull(context.intLowerBound(id))
                        check(value == context.intUpperBound(id))
                        value
                    },
                ))
            }.also { it.useFiniteEnforcement(model.finite, enforcingFactors) }
        },
    )

    private fun localParams(): LocalSearchParams = params.localSearch.withAssumptions(params.assumptions).copy(
        cancellation = params.localSearch.cancellation or cancellation,
    )

    private var refutedCondition: PolynomialCondition? = null

    private val completion = CandidateCompletion { candidate, stop ->
        when (composedFixpoint(finite, Candidate(candidate, Unit), cancellation or stop, params.assumptions,
            enforcingPeers = enforcingFactors)) {
            is Verification.Rejected -> return@CandidateCompletion Completion.Refuted(work = 1)
            is Verification.Indeterminate -> return@CandidateCompletion Completion.Undecided(work = 1)
            is Verification.Accepted -> Unit
        }
        val pins = model.integerColumns.map { (id, column) -> column to BigFraction.ofLong(candidate.intValue(id)) }
            .toMap()
        val condition = PolynomialCondition(
            (0 until model.source.numBoolVars).associateWith(candidate::boolValue), pins, pins,
        )
        val query = if (!stop() && condition == refutedCondition) QfNraResult.Unsatisfiable(QfNraStats(0, 0, 0))
        else invocation.check(condition, stop)
        when (query) {
            is QfNraResult.Satisfiable -> Completion.Witness(
                certified(model.capture(query.assignment, candidate.bools, candidate.ints)), work = 1,
            )
            is QfNraResult.Unsatisfiable -> {
                refutedCondition = condition
                Completion.Refuted(work = 1)
            }
            is QfNraResult.Unknown -> Completion.Undecided(work = 1)
        }
    }

    private fun localSearch(): LocalSearchSolver = LocalSearchSolver(finite, completion = completion)

    fun solve(engine: FiniteEngine): SolveResult = when (engine) {
        FiniteEngine.BACKTRACK, FiniteEngine.FIXED -> BacktrackSolver(finite).solve(backtrackParams())
        FiniteEngine.LOCAL_SEARCH -> neighborhood(false)?.solve(cancellation, params.localSearch.maxInstructions)
            ?: heuristic(localSearch().solve(localParams()))
        FiniteEngine.ALNS -> neighborhood(true)?.solve(cancellation, params.localSearch.maxInstructions)
            ?: alns(cancellation)
        FiniteEngine.MIXED -> error("mixed execution requires the shared invocation")
    }

    fun minimize(objective: LinearObjective, engine: FiniteEngine): MinimizeResult {
        require(objective.realCoefficientValues.none { it != 0.0 }) {
            "polynomial optimization requires a finite objective"
        }
        return when (engine) {
            FiniteEngine.BACKTRACK, FiniteEngine.FIXED -> BacktrackSolver(finite).minimize(objective, backtrackParams())
            FiniteEngine.LOCAL_SEARCH -> neighborhood(false, objective)?.improvements(
                { Double.POSITIVE_INFINITY }, cancellation, null, params.localSearch.maxInstructions,
            )?.last() ?: heuristic(localSearch().minimize(objective, localParams()))
            FiniteEngine.ALNS -> neighborhood(true, objective)?.improvements(
                { Double.POSITIVE_INFINITY }, cancellation, null, params.localSearch.maxInstructions,
            )?.last() ?: heuristic(alnsMinimize(objective, cancellation))
            FiniteEngine.MIXED -> error("mixed execution requires the shared invocation")
        }
    }

    private fun alns(stop: Cancellation, instructions: Long? = null): SolveResult {
        val objective = LinearObjective(intCoefficients = LongArray(finite.numIntVars) { 1L })
        val result = alnsMinimize(objective, stop, instructions)
        return result.assignment?.let { SolveResult.Sat(it, result.stats) }
            ?: SolveResult.Unknown(TerminationReason.BudgetExhausted, result.stats)
    }

    private fun alnsMinimize(
        objective: LinearObjective,
        stop: Cancellation,
        instructions: Long? = null,
    ): MinimizeResult {
        val inner = localSearch()
        return inner.session().use { session ->
            val alns = Alns(
                inner, session = session,
                backtrack = BacktrackSolver(finite), backtrackParams = backtrackParams(),
                repairOperators = BacktrackRepair.Defaults, maxIterations = params.alnsIterations,
            )
            val local = localParams().copy(cancellation = cancellation or stop,
                maxInstructions = instructions?.let { minOf(it, params.localSearch.maxInstructions ?: it) }
                    ?: params.localSearch.maxInstructions)
            alns.minimize(objective, local)
        }
    }

    fun worker(arm: Int, objective: LinearObjective?, slot: PortfolioWorkerSlot): PortfolioWorker = when (arm) {
        0 -> PortfolioWorker.of("bt+exact-nra", 0, BacktrackSolver(finite).session(), backtrackParams(), objective)
            .bindEvidence(ModelIdentity.of(model.source, objective))
        1 -> neighborhood(false, objective) ?: PortfolioWorker.of(
            "ls+exact-nra", 1, localSearch().session(), localParams(), objective,
            withInstructionBudget = { p, units -> p.copy(maxInstructions = minOf(units, p.maxInstructions ?: units)) })
            .bindEvidence(ModelIdentity.of(model.source, objective), provesResults = false)
        2 -> neighborhood(true, objective) ?: retainedAlns(objective, slot)
        else -> error("unknown polynomial arm")
    }

    private fun retainedAlns(objective: LinearObjective?, slot: PortfolioWorkerSlot): PortfolioWorker {
        val inner = localSearch()
        val session = slot.own(inner.session())
        val driver = Alns(inner, session = session, backtrack = BacktrackSolver(finite),
            backtrackParams = backtrackParams(), repairOperators = BacktrackRepair.Defaults,
            maxIterations = params.alnsIterations, rng = kotlin.random.Random(params.localSearch.randomSeed ?: 0))
        if (objective != null) return PortfolioWorker.ofMinimize("alns+exact-nra", 2, countsInstructions = true,
            resumable = { driver.resumable(objective, localParams()) }) { _, _, stop, instructions ->
            sequenceOf(heuristic(alnsMinimize(objective, stop, instructions)))
        }.bindEvidence(ModelIdentity.of(model.source, objective), provesResults = false)
        val probeObjective = LinearObjective(intCoefficients = LongArray(finite.numIntVars) { 1L })
        return PortfolioWorker.ofSolve("alns+exact-nra", 2, countsInstructions = true,
            resumable = { PolynomialAlnsSolve(driver.resumable(probeObjective, localParams())) },
        ) { stop, instructions ->
            alns(stop, instructions)
        }.bindEvidence(ModelIdentity.of(model.source, objective), provesResults = false)
    }

    private fun heuristic(result: SolveResult): SolveResult = when (result) {
        is SolveResult.Unsat -> SolveResult.Unknown(TerminationReason.BudgetExhausted, result.stats)
        else -> result
    }

    private fun heuristic(result: MinimizeResult): MinimizeResult = when (result) {
        is MinimizeResult.Optimal -> MinimizeResult.BestFound(
            result.sample, result.objective, TerminationReason.BudgetExhausted, result.stats,
        )
        is MinimizeResult.Infeasible -> MinimizeResult.Unknown(TerminationReason.BudgetExhausted, result.stats)
        else -> result
    }
}
