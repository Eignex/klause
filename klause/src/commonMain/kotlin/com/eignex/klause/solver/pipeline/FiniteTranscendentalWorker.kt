package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.localsearch.CandidateCompletion
import com.eignex.klause.localsearch.Completion
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.meta.alns.Alns
import com.eignex.klause.meta.alns.BacktrackRepair
import com.eignex.klause.portfolio.PortfolioWorker
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.incumbent.Candidate
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.solver.validation.FinitePointCheck
import com.eignex.klause.theory.qfnra.PolynomialCondition
import com.eignex.klause.theory.transcendental.TranscendentalCheckResult
import com.eignex.klause.theory.transcendental.TranscendentalCondition
import com.eignex.klause.theory.transcendental.TranscendentalSearchComponent
import com.eignex.klause.theory.transcendental.TranscendentalTheoryService
import com.eignex.klause.theory.transcendental.TranscendentalWitness
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BigFraction

internal class FiniteTranscendentalWorker(
    private val model: SharedTranscendentalModel,
    private val service: TranscendentalTheoryService,
    private val params: TranscendentalTheoryParams,
    private val root: TranscendentalCondition,
    private val cancellation: ExecutionControl,
    private val capture: (TranscendentalWitness) -> Sample,
) {
    private val strategy = params.strategy
    private val finite = model.finite.bake(model.finiteProjections, cancellation)
    private val pointCheck = FinitePointCheck(finite)
    private val backtrack = strategy.backtrack.withAssumptions(strategy.assumptions).copy(
        nodeBudget = params.nodeBudget ?: strategy.backtrack.nodeBudget,
        cancellation = strategy.backtrack.cancellation.withAccounting(cancellation) or cancellation,
        componentFactory = {
            listOf(TranscendentalSearchComponent(model.problem, service, root, params.maximumDepth,
                finiteIntegers = model.problem.integerCoordinates.withIndex().associate { it.index to it.value },
                capture = capture))
        },
    )
    private val local = strategy.localSearch.withAssumptions(strategy.assumptions).copy(
        nodeBudget = params.nodeBudget ?: strategy.localSearch.nodeBudget,
        cancellation = strategy.localSearch.cancellation.withAccounting(cancellation) or cancellation,
    )
    private val completion = CandidateCompletion { candidate, stop ->
        val checking = cancellation or stop
        when (pointCheck.check(Candidate(candidate, Unit), checking, local.assumptions)) {
            is Verification.Rejected -> return@CandidateCompletion Completion.Refuted(work = 1)
            is Verification.Indeterminate -> return@CandidateCompletion Completion.Undecided(work = 1)
            is Verification.Accepted -> Unit
        }
        val integers = model.problem.integerCoordinates.withIndex().associate { (id, coordinate) ->
            coordinate to BigFraction.ofLong(candidate.intValue(id))
        }
        val scoped = TranscendentalCondition(PolynomialCondition(
            (0 until model.source.numBoolVars).associateWith(candidate::boolValue),
            root.rational.lower + integers, root.rational.upper + integers,
        ), root.lowerStrict, root.upperStrict)
        when (val result = service.check(scoped, checking)) {
            is TranscendentalCheckResult.Witness -> Completion.Witness(capture(result.value), work = 1)
            TranscendentalCheckResult.Refuted -> Completion.Refuted(work = 1)
            TranscendentalCheckResult.Unknown -> Completion.Undecided(work = 1)
        }
    }
    private fun localSearch() = LocalSearchSolver(finite, completion = completion)
    private val objective = LinearObjective(intCoefficients = LongArray(finite.numIntVars) { 1L })

    fun solve(engine: FiniteEngine): SolveResult = when (engine) {
        FiniteEngine.BACKTRACK, FiniteEngine.FIXED -> BacktrackSolver(finite).solve(backtrack)
        FiniteEngine.LOCAL_SEARCH -> heuristic(localSearch().solve(local))
        FiniteEngine.ALNS -> alns(cancellation)
        FiniteEngine.MIXED -> error("mixed execution requires the shared invocation")
    }

    fun worker(arm: Int, slot: PortfolioWorkerSlot, identity: ModelIdentity): PortfolioWorker = when (arm) {
        0 -> PortfolioWorker.of("bt+finite-functions", 0, BacktrackSolver(finite).session(), backtrack, null)
            .bindEvidence(identity)
        1 -> PortfolioWorker.of("ls+finite-functions", 1, localSearch().session(), local, null,
            withInstructionBudget = { p, units -> p.copy(maxInstructions = minOf(units, p.maxInstructions ?: units)) })
            .bindEvidence(identity, provesResults = false)
        2 -> {
            val inner = localSearch()
            val session = slot.own(inner.session())
            val driver = Alns(inner, session = session, backtrack = BacktrackSolver(finite),
                backtrackParams = backtrack, repairOperators = BacktrackRepair.Defaults,
                maxIterations = strategy.alnsIterations,
                rng = kotlin.random.Random(local.randomSeed ?: 0))
            PortfolioWorker.ofSolve("alns+finite-functions", 2, countsInstructions = true,
                resumable = { PolynomialAlnsSolve(driver.resumable(objective, local)) },
            ) { stop, instructions -> alns(stop, instructions) }.bindEvidence(identity, provesResults = false)
        }
        else -> error("unknown function arm")
    }

    private fun alns(stop: ExecutionControl, instructions: Long? = null): SolveResult {
        val inner = localSearch()
        val result = inner.session().use { session ->
            Alns(inner, session = session, backtrack = BacktrackSolver(finite), backtrackParams = backtrack,
                repairOperators = BacktrackRepair.Defaults, maxIterations = strategy.alnsIterations)
                .minimize(objective, local.copy(cancellation = cancellation or stop,
                    maxInstructions = instructions?.let { minOf(it, local.maxInstructions ?: it) }
                        ?: local.maxInstructions))
        }
        return result.assignment?.let { SolveResult.Sat(it, result.stats) }
            ?: SolveResult.Unknown((result as? MinimizeResult.Unknown)?.reason
                ?: TerminationReason.BudgetExhausted, result.stats)
    }

    private fun heuristic(result: SolveResult): SolveResult = when (result) {
        is SolveResult.Unsat -> SolveResult.Unknown(TerminationReason.BudgetExhausted, result.stats)
        else -> result
    }
}
