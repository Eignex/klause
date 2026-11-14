package com.eignex.klause.solver.pipeline

import com.eignex.klause.portfolio.PortfolioIncumbents
import com.eignex.klause.portfolio.PortfolioScenario
import com.eignex.klause.portfolio.PortfolioWorker
import com.eignex.klause.portfolio.WorkerEligibility
import com.eignex.klause.portfolio.WorkerProofAuthority
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceEvidence
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SearchEvent
import com.eignex.klause.util.Cancellation

/** Caller choices for the numerical traversal and joint repair arms of a finite linear-source portfolio. */
internal class FiniteCompanionControls(
    val params: TheoryParams? = null,
    val incumbentCompletionCheck: ((Sample, BigFraction, Cancellation) -> Boolean)? = null,
    val funded: Boolean = false,
    val waitForImprovement: Boolean = false,
    val adaptiveRepair: Boolean = false,
    val onJointRepair: ((String, Long, JointRepairObservation) -> Unit)? = null,
)

/**
 * Numerical traversal and joint repair seated beside the finite workers: admission, construction,
 * arm order, preparation telemetry and scheduler shares. The finite portfolio only places them.
 */
internal class FiniteCompanionArms private constructor(
    private val controls: FiniteCompanionControls,
    private val admission: FiniteCompanionAdmission?,
    private val preparation: FiniteNumericalCompanions.Preparation?,
    val companions: FiniteNumericalCompanions?,
) {
    /** Canonical incumbents the finite workers accept from the companions. */
    val incumbents: PortfolioIncumbents<BigFraction>? get() = companions?.incumbents

    /** Seed positions the leading numerical arm shifts the finite workers by. */
    val finiteSeedOffset: Int get() = if (companions == null) 0 else 1

    val preparationStages: Map<String, Long> get() = preparation?.let {
        mapOf("finiteCompanionPreparationAttempts" to 1L,
            "finiteCompanionPreparationNs" to it.elapsedNanos,
            "finiteCompanionPreparationReady" to if (companions != null) 1L else 0L,
            "finiteCompanionPreparationUnavailable" to if (companions == null) 1L else 0L)
    }.orEmpty()

    val livePreparationStages: (() -> Map<String, Long>)? get() = admission?.let { it::stages }

    val objectiveBound: (() -> SourceEvidence?)? get() = companions?.let { { it.objectiveBound } }

    /** Put the numerical arm first and joint repair after the finite workers. */
    fun arrange(plan: PreparedSourcePlan, finite: List<PortfolioArmFactory>): List<PortfolioArmFactory> {
        val additional = companions?.factories(plan, finite.size).orEmpty()
        return additional.take(1) + finite + additional.drop(1)
    }

    fun bindWorkers(workers: List<PortfolioWorker>) {
        if (companions != null && controls.params?.sourceIncumbentCompletion == true) {
            for (worker in workers) if (worker.controlsObjectiveCutoff) worker.canonicalIncumbentIsCutoff = true
        }
    }

    /** Joint repair keeps 30% of scheduled search time before the first incumbent. */
    fun reserve(workers: List<PortfolioWorker>, finite: DoubleArray): DoubleArray = if (companions == null) finite
    else (if (finite.isEmpty()) DoubleArray(workers.size) else finite).also {
        it[workers.indexOfFirst { worker -> worker.label == JOINT_REPAIR }] = 0.3
    }

    /** Probe weights giving the numerical arm three initial turns, or null without companions. */
    fun probeWeights(workers: List<PortfolioWorker>): IntArray? = if (companions == null) null
    else IntArray(workers.size) { arm -> if (workers[arm].label == NUMERICAL_TRAVERSAL) 3 else 1 }

    companion object {
        const val NUMERICAL_TRAVERSAL = "numerical/finite-companion"
        const val JOINT_REPAIR = "ls/finite-joint-companion"

        @Suppress("LongParameterList")
        fun assemble(
            problem: BakedProblem,
            scenario: PortfolioScenario,
            objective: LinearObjective?,
            controls: FiniteCompanionControls,
            cancellation: Cancellation,
            onEvent: ((worker: String, event: SearchEvent) -> Unit)?,
        ): FiniteCompanionArms {
            val params = controls.params
            val eligibility = params?.let { finiteNumericalCompanionEligibility(problem, scenario, objective, it) }
            if (controls.adaptiveRepair && params?.sourceIncumbentCompletion != true) {
                pipelineConfigError(
                    "companion-repair=adaptive requires source-incumbent-completion for numerical companions",
                )
            }
            val funded = controls.funded
            if (controls.waitForImprovement && !funded) {
                pipelineConfigError("progress-funded companion admission requires funded construction")
            }
            if (funded && (!scenario.companionAfterIncumbent || cancellation.deadline() == null)) {
                pipelineConfigError(
                    "funded companion admission requires companion-after-incumbent and a solve deadline",
                )
            }
            val admission = if (funded && eligibility?.admitted == true) {
                FiniteNumericalCompanions.admission(problem, requireNotNull(objective),
                    cancellation or requireNotNull(params).cancellation or params.timeout,
                    eligibility.requirements.construction)
            } else null
            val comparison: ((SearchEvent.CompanionComparison) -> Unit)? =
                if (scenario.reportPortfolioProgress && onEvent != null) { event -> onEvent(JOINT_REPAIR, event) }
                else null
            val observation: ((Long, JointRepairObservation) -> Unit)? = controls.onJointRepair?.let { report ->
                { seed, observation -> report(JOINT_REPAIR, seed, observation) }
            }
            val adaptive = controls.adaptiveRepair
            val preparation = if (!funded && eligibility?.admitted == true) {
                FiniteNumericalCompanions.prepare(problem, requireNotNull(objective), requireNotNull(params),
                    cancellation, eligibility.requirements.construction, scenario.companionAfterIncumbent,
                    controls.incumbentCompletionCheck, adaptiveRepair = adaptive, onComparison = comparison,
                    onObservation = observation)
            } else null
            val companions = if (admission?.initiallyFunded == true) {
                FiniteNumericalCompanions.deferred(problem, requireNotNull(objective), requireNotNull(params),
                    cancellation, requireNotNull(eligibility).requirements.construction, admission,
                    controls.incumbentCompletionCheck, afterImprovement = controls.waitForImprovement,
                    adaptiveRepair = adaptive, onComparison = comparison, onObservation = observation)
            } else preparation?.companions
            eligibility?.let { reportAdmission(it, admission, companions, onEvent) }
            return FiniteCompanionArms(controls, admission, preparation, companions)
        }

        private fun reportAdmission(
            eligibility: WorkerEligibility,
            admission: FiniteCompanionAdmission?,
            companions: FiniteNumericalCompanions?,
            onEvent: ((worker: String, event: SearchEvent) -> Unit)?,
        ) {
            val declines = if (admission?.initiallyFunded == false) listOf("companion-lifetime-unfunded")
            else if (eligibility.admitted && companions == null) listOf("complete-affine-capture-unavailable")
            else eligibility.declines
            val frontier = eligibility.requirements
            val repair = frontier.copy(authority = WorkerProofAuthority.WITNESS_ONLY,
                construction = frontier.construction.copy(sliceMillis = 250))
            for ((label, requirements) in listOf(NUMERICAL_TRAVERSAL to frontier, JOINT_REPAIR to repair)) {
                onEvent?.invoke("portfolio", SearchEvent.WorkerAdmission(label, declines.isEmpty(),
                    requirements.toString(), declines, selected = declines.isEmpty()))
            }
        }
    }
}
