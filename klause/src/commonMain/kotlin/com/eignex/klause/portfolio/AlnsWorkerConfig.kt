package com.eignex.klause.portfolio

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.localsearch.AcceptanceCriterion
import com.eignex.klause.localsearch.CostShaping
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.localsearch.schedule.Geometric
import com.eignex.klause.meta.alns.Alns
import com.eignex.klause.meta.alns.BacktrackRepair
import com.eignex.klause.solver.BakedProblem
import com.eignex.klause.solver.objective.IncrementalObjective
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SearchEvent
import kotlin.math.abs
import kotlin.math.ln

/**
 * A portfolio arm running hybrid ALNS with CP repair: an outer destroy/repair loop over the
 * incumbent, each freed neighbourhood repaired by a bounded backtrack LCG+LP solve under the pinned
 * complement ([BacktrackRepair]) — full propagation, clause learning, and LP bounding on the fragment,
 * unlike a pure-LS repair. An LS-class engine: it optimises anytime and returns
 * [com.eignex.klause.solver.result.MinimizeResult.BestFound], never claiming completeness, so it fits
 * the local-search track. COP-oriented (it optimises an incumbent); on a CSP the underlying [Alns]
 * degrades to its inner LS via `solve`.
 *
 * [profile] fixes this arm's regime — its destroy-size band and acceptance temperature; a diverse ALNS
 * engine cycles [AlnsProfile.Curated] via [diverse]. The default regime is used for the standalone arm
 * (e.g. the last slot of a mixed portfolio).
 */
internal class AlnsWorkerConfig(val profile: AlnsProfile = AlnsProfile.Default) : WorkerConfig {
    override val label: String get() = "alns-${profile.label}"

    override fun materialize(
        problem: BakedProblem,
        index: Int,
        armId: Int,
        seed: Long,
        lsLambda: Double,
        objective: LinearObjective?,
        lsObjective: IncrementalObjective?,
        definitionalSweep: DefinitionalSweep?,
        onEvent: ((worker: String, event: SearchEvent) -> Unit)?,
        pools: SharedPools?,
    ): PortfolioWorker {
        val workerLabel = "alns/${profile.label}"
        // Cross-repair clause sharing: one pool persists globally-valid learned clauses across
        // fragments so later repairs re-descend under earlier repairs' learning. Gated for soundness —
        // the repair learns under assumptions/an incumbent, so its permanent (objective-bound, blocking)
        // clauses and LP Farkas nogoods hold only under its pins and must not be shared.
        val repairClauses = SharedClausePool()
        // Bidirectional cross-engine flow: publish accepted incumbents into the shared pool and, at
        // the top of each iteration, destroy from the pool's global best — so ALNS both feeds and follows
        // the incumbents backtrack and LS arms find.
        val solutions = pools?.solutions
        val alns = Alns(
            inner = LocalSearchSolver(problem),
            repairOperators = BacktrackRepair.Defaults,
            backtrack = BacktrackSolver(problem),
            backtrackParams = BacktrackParams(
                randomSeed = seed + index,
                clauseExchange = PoolClauseExchange(repairClauses, skipPermanent = true, shareGlobalNogoods = false),
            ),
            minDestroyFraction = profile.minDestroyFraction,
            maxDestroyFraction = profile.maxDestroyFraction,
            improvedSolutionSink = solutions?.let { it::publish },
            pooledSolutionSupplier = solutions?.let { it::best },
            acceptanceFor = acceptanceFactory(),
        )
        val params = LocalSearchParams(
            randomSeed = seed + index,
            costShaping = CostShaping.Linear(lambda = lsLambda),
            lsObjective = lsObjective,
            onEvent = onEvent?.let { sink -> { e -> sink(workerLabel, e) } },
        )
        return PortfolioWorker.of(
            workerLabel,
            armId,
            alns.session(),
            params,
            objective = objective,
            withWarmStart = { p, sample -> p.copy(initialAssignment = sample) },
        )
    }

    /**
     * This regime's acceptance-policy factory: strict hill-climbing when the profile disables worsening,
     * else simulated annealing whose start temperature is scaled to the initial objective — a move
     * `saInitialWorsening` worse than the first incumbent accepts with ~50% probability, then cools.
     */
    private fun acceptanceFactory(): (Double) -> AcceptanceCriterion = if (profile.saInitialWorsening <= 0.0) {
        { AcceptanceCriterion.Improving }
    } else {
        { initialObjective ->
            val start = (profile.saInitialWorsening * abs(initialObjective) / ln(2.0)).coerceAtLeast(1.0)
            val schedule = Geometric(initialTemperature = start, coolingRate = profile.saCooling)
            AcceptanceCriterion.SimulatedAnnealing(schedule)
        }
    }

    companion object {
        /** [count] diverse ALNS arms cycling the curated regimes ([AlnsProfile.Curated]) — the ALNS analog
         *  of [LocalSearchWorkerConfig.diverse]. Every slot is a fresh instance even when regimes repeat. */
        fun diverse(count: Int): List<AlnsWorkerConfig> =
            List(count) { AlnsWorkerConfig(AlnsProfile.Curated[it % AlnsProfile.Curated.size]) }
    }
}
