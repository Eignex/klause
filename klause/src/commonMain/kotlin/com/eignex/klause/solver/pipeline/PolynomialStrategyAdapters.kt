package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.localsearch.CandidateCompletion
import com.eignex.klause.localsearch.Completion
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.meta.alns.Alns
import com.eignex.klause.meta.alns.BacktrackRepair
import com.eignex.klause.portfolio.Portfolio
import com.eignex.klause.portfolio.PortfolioEvidence
import com.eignex.klause.portfolio.PortfolioWorker
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.bake
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.incumbent.CandidateVerifier
import com.eignex.klause.solver.incumbent.EvidenceCertificate
import com.eignex.klause.solver.incumbent.EvidenceKind
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.theory.qfnra.PolynomialCondition
import com.eignex.klause.theory.qfnra.PolynomialSearchComponent
import com.eignex.klause.theory.qfnra.PolynomialTheoryInvocation
import com.eignex.klause.theory.qfnra.QfNraResult
import com.eignex.klause.theory.qfnra.QfNraStats
import com.eignex.klause.util.Cancellation

/** Finite strategy controls for composed polynomial satisfaction. */
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
    private val model: SharedPolynomialModel,
    private val invocation: PolynomialTheoryInvocation,
    private val params: PolynomialStrategyParams,
    private val cancellation: Cancellation,
) {
    private val finite: BakedProblem = model.finite.bake(cancellation)
    private val identity = ModelIdentity.of(model.source)

    private fun certified(sample: Sample): Sample = sample.also {
        it.witnessCertificate = EvidenceCertificate.verified(identity, EvidenceKind.Witness)
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
            }
        },
    )

    private fun localParams(): LocalSearchParams = params.localSearch.withAssumptions(params.assumptions).copy(
        cancellation = params.localSearch.cancellation or cancellation,
    )

    private var refutedCondition: PolynomialCondition? = null

    private val completion = CandidateCompletion { candidate, stop ->
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
        FiniteEngine.LOCAL_SEARCH -> heuristic(localSearch().solve(localParams()))
        FiniteEngine.ALNS -> alns(cancellation)
        FiniteEngine.MIXED -> mixed()
    }

    private fun alns(stop: Cancellation, instructions: Long? = null): SolveResult {
        val inner = localSearch()
        return inner.session().use { session ->
            val alns = Alns(
                inner, session = session,
                backtrack = BacktrackSolver(finite), backtrackParams = backtrackParams(),
                repairOperators = BacktrackRepair.Defaults, maxIterations = params.alnsIterations,
            )
            val objective = LinearObjective(intCoefficients = LongArray(finite.numIntVars) { 1L })
            val local = localParams().copy(cancellation = cancellation or stop,
                maxInstructions = instructions ?: params.localSearch.maxInstructions)
            val result = alns.minimize(objective, local)
            result.assignment?.let { SolveResult.Sat(it, result.stats) }
                ?: SolveResult.Unknown(TerminationReason.BudgetExhausted, result.stats)
        }
    }

    private fun mixed(): SolveResult {
        val bt = PortfolioWorker.of("bt+exact-nra", 0, BacktrackSolver(finite).session(), backtrackParams())
            .bindEvidence(identity)
        val ls = PortfolioWorker.of("ls+exact-nra", 1, localSearch().session(), localParams(),
            withInstructionBudget = { p, units -> p.copy(maxInstructions = units) })
            .bindEvidence(identity, provesResults = false)
        val alns = PortfolioWorker.ofSolve("alns+exact-nra", 2, countsInstructions = true,
            solve = { stop, instructions -> alns(stop, instructions) })
            .bindEvidence(identity, provesResults = false)
        val portfolio = Portfolio.thompson(listOf(bt, ls, alns))
        portfolio.evidenceVerification = PortfolioEvidence(identity, CandidateVerifier { candidate ->
            val certificate = candidate.assignment.witnessCertificate
            if (certificate?.kind == EvidenceKind.Witness && certificate.model.sameModel(identity)) {
                Verification.Accepted(candidate)
            } else {
                Verification.Indeterminate("polynomial source witness has no matching exact certificate")
            }
        })
        return portfolio.use { it.solve(cancellation) }
    }

    private fun heuristic(result: SolveResult): SolveResult = when (result) {
        is SolveResult.Unsat -> SolveResult.Unknown(TerminationReason.BudgetExhausted, result.stats)
        else -> result
    }
}
