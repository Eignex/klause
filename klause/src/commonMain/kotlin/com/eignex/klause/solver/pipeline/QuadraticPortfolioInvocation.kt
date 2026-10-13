package com.eignex.klause.solver.pipeline

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.AlgebraicReal
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.arithmetic.polynomial.PolynomialLimits
import com.eignex.klause.arithmetic.polynomial.PolynomialResourceLimit
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.QuadraticObjectiveSpec
import com.eignex.klause.portfolio.ArmFamily
import com.eignex.klause.portfolio.PortfolioEvidence
import com.eignex.klause.portfolio.PortfolioIncumbents
import com.eignex.klause.portfolio.PortfolioTerminalCheck
import com.eignex.klause.portfolio.PortfolioTerminalPublication
import com.eignex.klause.portfolio.PortfolioWorker
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.quadraticEngineInvocation
import com.eignex.klause.solver.evidence.checkWitness
import com.eignex.klause.solver.evidence.SourceEvidenceCheck
import com.eignex.klause.solver.incumbent.Candidate
import com.eignex.klause.solver.incumbent.CandidateVerifier
import com.eignex.klause.solver.incumbent.EvidenceKind
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.objective.evaluateExact
import com.eignex.klause.solver.result.QuadraticCertificate
import com.eignex.klause.solver.result.QuadraticClaim
import com.eignex.klause.solver.result.QuadraticIncumbent
import com.eignex.klause.solver.result.QuadraticObjectiveBound
import com.eignex.klause.solver.result.QuadraticOptimizationResult
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import com.eignex.kumulant.core.Concurrency
import com.eignex.kumulant.stream.lock

internal class QuadraticPortfolioInvocation(
    private val plan: PreparedSourcePlan,
    private val objective: QuadraticObjectiveSpec,
    private val sense: ObjectiveSense,
    private val params: PolynomialTheoryParams,
    private val native: QuadraticNativeScope,
    private val engine: FiniteEngine,
    private val cores: Int,
    private val assumptions: Assumptions,
) {
    private data class Value(val source: AlgebraicReal, val minimized: AlgebraicReal)
    private val identity = ModelIdentity.of(plan.originalSource, objective)
    private val expected = plan.quadraticEngineInvocation(objective, sense, assumptions)
    private val external = params.timeout or params.cancellation
    private val verification = object : Cancellation {
        override fun isCancelled(): Boolean = external()
        override fun deadline() = external.deadline()
        override fun workMeter(): WorkMeter? = native.polynomial.workMeter()
    }
    private val limits = PolynomialLimits(params.maximumDenseCoefficients, params.maximumMatrixCells)
    private val evaluation = AlgebraicArithmetic(PolynomialArithmetic(verification, limits))
    private val terminalArithmetic = AlgebraicArithmetic(PolynomialArithmetic(verification, limits))
    private val terminalLock = Concurrency.Strict.lock()
    private val incumbents = PortfolioIncumbents.mappedAlgebraic(
        valueOf = { candidate ->
            val value = objective.evaluateExact(candidate.sample, evaluation)
            Value(value, if (sense == ObjectiveSense.MINIMIZE) value else evaluation.negate(value))
        }, exactValue = { it.minimized }, arithmetic = evaluation, onResource = native::decline,
    )
    private var terminal: QuadraticOptimizationResult? = null
    private var sharedBound: QuadraticObjectiveBound? = null

    fun execute(
        create: (Int, PortfolioWorkerSlot, QuadraticNativeScope, () -> QuadraticIncumbent?) -> PortfolioWorker,
    ): QuadraticOptimizationResult {
        val admission = plan.capabilities.admission(engine, ExecutionOperation.OPTIMIZE)
        if (!admission.runnable || expected == null) return QuadraticOptimizationResult.Unknown(
            null, TerminationReason.Unsupported, sense, SolveStats.EMPTY)
        val factories = admission.arms.map { strategy ->
            val arm = when (strategy) {
                ExecutionStrategy.COMPLETE -> 0
                ExecutionStrategy.LOCAL_SEARCH -> 1
                ExecutionStrategy.ALNS -> 2
                ExecutionStrategy.MIXED -> error("admission supplies participating arms")
            }
            val family = when (arm) { 0 -> ArmFamily.Backtrack; 1 -> ArmFamily.LocalSearch; else -> ArmFamily.Lns }
            PortfolioArmFactory(plan, arm, family) { slot ->
                val scope = native.worker(slot.index, params.maximumWork?.let(slot::nativeAllowance))
                create(arm, slot, scope) { incumbents.exchange.current()?.let {
                    QuadraticIncumbent(it.assignment, it.objective.source)
                } }
            }
        }
        val invocation = PortfolioInvocation(plan, factories,
            PortfolioInvocationControls(parallelism = if (engine == FiniteEngine.FIXED) 1 else cores,
                seed = params.strategy.localSearch.randomSeed ?: 1L,
                cancellation = native.polynomial,
                nodeBudget = params.strategy.backtrack.nodeBudget ?: params.strategy.localSearch.nodeBudget,
                reseedStaleThreshold = 0), PortfolioEvidence(identity, CandidateVerifier { candidate ->
                val sample = candidate.assignment
                val certificate = sample.witnessCertificate
                if (certificate?.kind == EvidenceKind.Witness && certificate.model.sameModel(identity) &&
                    sample.numBoolVars == plan.sourceBoolCount && sample.numIntVars == plan.sourceIntCount &&
                    sample.numRealVars == plan.sourceRealCount
                ) when (val checked = requireNotNull(expected).condition.checkWitness(sample, verification)) {
                    SourceEvidenceCheck.Checked -> Verification.Accepted(candidate)
                    is SourceEvidenceCheck.Rejected -> Verification.Rejected(checked.reason)
                    is SourceEvidenceCheck.Unavailable -> Verification.Indeterminate(checked.reason)
                }
                else Verification.Indeterminate("quadratic witness has no matching original-source check")
            })) { workers ->
            val complete = workers.indices.filter { workers[it].family == ArmFamily.Backtrack }
            PortfolioInvocationPolicy(shares = DoubleArray(workers.size) {
                if (it in complete) 0.5 / complete.size else 0.0
            })
        }
        val scalar = invocation.use { it.minimize(native.polynomial, null, incumbents,
            PortfolioTerminalCheck { worker, search ->
                val completed = (search as? QuadraticPortfolioSearch)?.completion ?: return@PortfolioTerminalCheck null
                val checked = terminalLock.withLock { check(worker, completed) }
                    ?: return@PortfolioTerminalCheck null
                PortfolioTerminalPublication(checked) { if (terminal == null) terminal = completed }
            }) }
        terminal?.let { return it.withPortfolioStats(scalar.stats) }
        val best = incumbents.exchange.current()?.let { QuadraticIncumbent(it.assignment, it.objective.source) }
        val reason = if (params.timeout()) TerminationReason.Timeout else if (params.cancellation()) {
            TerminationReason.Cancelled
        } else TerminationReason.BudgetExhausted
        return if (best == null) QuadraticOptimizationResult.Unknown(sharedBound, reason, sense, scalar.stats)
            else QuadraticOptimizationResult.BestFound(best, sharedBound, reason, sense, scalar.stats)
    }

    private fun check(
        worker: PortfolioWorker, result: QuadraticOptimizationResult,
    ): Verification<Unit, Unit>? = try {
        when (result) {
            is QuadraticOptimizationResult.Optimal -> common(worker, result.sense, result.incumbent)
                ?: certificate(result.certificate, QuadraticClaim.Optimal)
                ?: certificate(result.bound.certificate, QuadraticClaim.Bound)
                ?: if (result.bound.strict ||
                    terminalArithmetic.compare(result.incumbent.value, result.bound.value) != 0
                ) {
                    Verification.Rejected("quadratic optimum does not attain its bound")
                } else bound(result.bound.value, false)
            is QuadraticOptimizationResult.UnattainedLimit -> common(worker, result.sense, result.incumbent)
                ?: certificate(result.certificate, QuadraticClaim.UnattainedLimit)
                ?: certificate(result.bound.certificate, QuadraticClaim.Bound)
                ?: if (!result.bound.strict || !beyond(result.incumbent.value, result.bound.value)) {
                    Verification.Rejected("quadratic unattained limit has an invalid strict witness")
                } else bound(result.bound.value, true)
            is QuadraticOptimizationResult.Unbounded -> common(worker, result.sense, result.incumbent)
                ?: certificate(result.certificate, QuadraticClaim.Unbounded)
                ?: Verification.Accepted(Candidate(Unit, Unit))
            is QuadraticOptimizationResult.Infeasible -> {
                common(worker, result.sense, null)
                    ?: certificate(result.certificate, QuadraticClaim.Infeasible)
                    ?: if (incumbents.exchange.current() != null) {
                        Verification.Rejected("source refutation contradicts a witness")
                    } else Verification.Accepted(Candidate(Unit, Unit))
            }
            is QuadraticOptimizationResult.BestFound -> partialBound(worker, result.bound)
            is QuadraticOptimizationResult.Unknown -> partialBound(worker, result.bound)
        }
    } catch (_: PolynomialInterrupted) {
        Verification.Indeterminate("quadratic terminal checking interrupted")
    } catch (stop: PolynomialResourceLimit) {
        native.decline(stop.resource)
        Verification.Indeterminate("quadratic terminal checking exceeded representation limits")
    }

    private fun partialBound(
        worker: PortfolioWorker, candidate: QuadraticObjectiveBound?,
    ): Verification<Unit, Unit>? {
        if (candidate == null || !worker.permitsSourceProofs) return null
        certificate(candidate.certificate, QuadraticClaim.Bound)?.let { return it }
        val checked = bound(candidate.value, candidate.strict)
        if (checked !is Verification.Accepted) return checked
        val prior = sharedBound
        val improves = prior == null || terminalArithmetic.compare(candidate.value, prior.value).let {
            if (sense == ObjectiveSense.MINIMIZE) it > 0 else it < 0
        }
        if (improves) sharedBound = candidate
        return null
    }

    private fun common(
        worker: PortfolioWorker, direction: ObjectiveSense, point: QuadraticIncumbent?,
    ): Verification<Unit, Unit>? {
        if (!worker.permitsSourceProofs || worker.evidenceModel?.matches(identity) != true || direction != sense) {
            return Verification.Rejected("quadratic terminal has no authority for this source objective")
        }
        if (point == null) return null
        val sample = point.sample
        val witness = sample.witnessCertificate
        if (witness?.kind != EvidenceKind.Witness || !witness.model.sameModel(identity) ||
            sample.numBoolVars != plan.sourceBoolCount || sample.numIntVars != plan.sourceIntCount ||
            sample.numRealVars != plan.sourceRealCount
        ) return Verification.Indeterminate("quadratic terminal has no checked original-source witness")
        when (val checked = requireNotNull(expected).condition.checkWitness(sample, terminalArithmetic)) {
            SourceEvidenceCheck.Checked -> Unit
            is SourceEvidenceCheck.Rejected -> return Verification.Rejected(checked.reason)
            is SourceEvidenceCheck.Unavailable -> return Verification.Indeterminate(checked.reason)
        }
        if (terminalArithmetic.compare(objective.evaluateExact(sample, terminalArithmetic), point.value) != 0) {
            return Verification.Rejected("quadratic terminal objective differs from its source witness")
        }
        return null
    }

    private fun certificate(certificate: QuadraticCertificate, claim: QuadraticClaim): Verification<Unit, Unit>? {
        if (certificate.claim != claim || !certificate.model.matches(identity) ||
            !certificate.matchesInvocation(requireNotNull(expected))
        ) {
            return Verification.Rejected("quadratic certificate belongs to another source, condition or objective")
        }
        certificate.evidence?.let { captured ->
            if (captured.engineScope == null || captured.engineScope != captured.scope) {
                return Verification.Indeterminate("quadratic mapped terminal has no original-source admission")
            }
        }
        return null
    }

    private fun beyond(value: AlgebraicReal, bound: AlgebraicReal): Boolean =
        terminalArithmetic.compare(value, bound).let { if (sense == ObjectiveSense.MINIMIZE) it > 0 else it < 0 }

    private fun bound(value: AlgebraicReal, strict: Boolean): Verification<Unit, Unit> {
        val cutoff = if (sense == ObjectiveSense.MINIMIZE) value else terminalArithmetic.negate(value)
        val best = incumbents.exchange.current()?.objective?.minimized
        if (best != null) {
            val compared = terminalArithmetic.compare(best, cutoff)
            if (compared < 0 || (strict && compared == 0)) {
                return Verification.Rejected("quadratic bound contradicts the accepted source incumbent")
            }
        }
        return Verification.Accepted(Candidate(Unit, Unit))
    }
}

internal fun QuadraticOptimizationResult.withPortfolioStats(stats: SolveStats): QuadraticOptimizationResult =
    when (this) {
    is QuadraticOptimizationResult.Optimal -> copy(stats = stats)
    is QuadraticOptimizationResult.UnattainedLimit -> copy(stats = stats)
    is QuadraticOptimizationResult.Unbounded -> copy(stats = stats)
    is QuadraticOptimizationResult.Infeasible -> copy(stats = stats)
    is QuadraticOptimizationResult.BestFound -> copy(stats = stats)
    is QuadraticOptimizationResult.Unknown -> copy(stats = stats)
}
