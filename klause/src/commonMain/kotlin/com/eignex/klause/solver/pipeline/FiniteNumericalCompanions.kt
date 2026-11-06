package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.lp.bounding.LpTechnique
import com.eignex.klause.portfolio.ArmFamily
import com.eignex.klause.portfolio.EngineMix
import com.eignex.klause.portfolio.Kind
import com.eignex.klause.portfolio.PortfolioIncumbents
import com.eignex.klause.portfolio.PortfolioScenario
import com.eignex.klause.portfolio.PortfolioWorker
import com.eignex.klause.portfolio.PortfolioLifecycleKind
import com.eignex.klause.portfolio.WorkerConstructionCost
import com.eignex.klause.portfolio.WorkerEligibility
import com.eignex.klause.portfolio.WorkerFragment
import com.eignex.klause.portfolio.WorkerObjectiveView
import com.eignex.klause.portfolio.WorkerProofAuthority
import com.eignex.klause.portfolio.WorkerRequirements
import com.eignex.klause.portfolio.SourceWorkerBinding
import com.eignex.klause.portfolio.binaryIntegers
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.difference.DifferenceSystem
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.ResumableSearch
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.solver.incumbent.EngineInvocation
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.LinearOptimizationResult
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

internal val PreparedSourceInvocation.requestsFiniteNumericalCompanions: Boolean get() =
    request.linearTraversal == LinearTraversal.FINITE_COMPANIONS ||
        (request.linearTraversal == LinearTraversal.AUTOMATIC && linearSource != null)

internal fun finiteNumericalCompanionEligibility(
    problem: BakedProblem,
    scenario: PortfolioScenario,
    objective: LinearObjective?,
    params: TheoryParams,
): WorkerEligibility {
    val cost = WorkerConstructionCost(problem.numIntVars.toLong() + problem.numRealVars, problem.factors.size,
        lpWorkLimit = params.sourceLpWorkLimit / 2, allocationLimit = params.sourceLpAllocationLimit / 2,
        activeMillis = scenario.companionActiveMillis, sliceMillis = 1_000, sliceWork = 5_000,
        maxColumns = 4_096, maxFactors = 16_384)
    val requirements = WorkerRequirements(
        if (scenario.companionIntegers) WorkerFragment.AFFINE_MIXED else WorkerFragment.AFFINE_BINARY_MIXED,
        WorkerObjectiveView.CANONICAL, WorkerProofAuthority.CHECKED_TERMINALS, cost)
    val declines = buildList {
        if (scenario.cores != 1) add("single-lane-required")
        if (scenario.kind != Kind.COP || objective == null) add("canonical-objective-required")
        if (scenario.engine != EngineMix.MIXED) add("mixed-pool-required")
        if (scenario.explicitArms || scenario.arms != PortfolioScenario.DEFAULT_ARMS || scenario.btPool != null ||
            scenario.lsPool != null || scenario.btEdit != null || scenario.annotationArm != null
        ) add("explicit-finite-recipe")
        if (problem.numIntVars == 0 || problem.numRealVars == 0 || problem.numBoolVars != 0) {
            add("integer-continuous-fragment-required")
        }
        if (requirements.fragment == WorkerFragment.AFFINE_BINARY_MIXED && !problem.binaryIntegers) {
            add("binary-integers-required")
        }
        if (cost.columns <= requireNotNull(cost.maxColumns) && (0 until problem.numIntVars).any {
            !problem.intBounds.hasLower(it) || !problem.intBounds.hasUpper(it)
        }) {
            add("finite-integer-bounds-required")
        }
        if (cost.columns > requireNotNull(cost.maxColumns) || cost.factors > requireNotNull(cost.maxFactors)) {
            add("construction-size-limit")
        }
        if (!scenario.lpCeiling.resolved(LpTechnique.BOUNDING)) add("lp-bounding-disabled")
        if (scenario.diagnosticWorkLimit != null) add("numerical-work-schedule-unsupported")
    }
    return WorkerEligibility(requirements, declines)
}

internal class FiniteNumericalCompanions private constructor(
    private val model: BakedProblem,
    private val objective: LinearObjective,
    private val context: SourceProofContext?,
    private val params: TheoryParams,
    private val cancellation: Cancellation,
    private val cost: WorkerConstructionCost,
    private val afterIncumbent: Boolean,
    private val incumbentCompletionCheck: ((Sample, BigFraction, Cancellation) -> Boolean)?,
    private val admission: FiniteCompanionAdmission? = null,
    private val afterImprovement: Boolean = false,
) {
    class Preparation(val companions: FiniteNumericalCompanions?, val elapsedNanos: Long)

    val incumbents = PortfolioIncumbents.exact(objective)
    private val completionSharing = if (params.sourceIncumbentCompletion && incumbentCompletionCheck != null)
        LinearIncumbentSharing() else null

    fun factories(plan: PreparedSourcePlan, firstArm: Int): List<PortfolioArmFactory> {
        val identity = ModelIdentity.of(model, objective)
        val binding = EngineInvocation(plan, identity, objective)
        fun factory(arm: Int, family: ArmFamily, create: (PortfolioWorkerSlot) -> PortfolioWorker) =
            PortfolioArmFactory(plan, arm, family,
                binding = { SourceWorkerBinding(binding, sharingInvocation = binding,
                    provesResults = it.permitsSourceProofs) }, create = create)
        fun local(slot: PortfolioWorkerSlot) = params.copy(
            randomSeed = slot.seed, nodeBudget = slot.nodeBudget,
            cancellation = cancellation or params.cancellation or slot.cancellation,
            sourceLpWorkLimit = requireNotNull(cost.lpWorkLimit),
            sourceLpAllocationLimit = requireNotNull(cost.allocationLimit),
        )
        return listOf(
            factory(firstArm, ArmFamily.Backtrack) { slot ->
                lateinit var worker: PortfolioWorker
                worker = PortfolioWorker.ofMinimize("numerical/finite-companion", firstArm, resumable = {
                    val limits = local(slot)
                    val search = if (admission == null) numericalSearch(limits, worker, requireNotNull(context))
                    else DeferredCompanionSearch({ admission.declineReason }) { global, millis ->
                        admission.contextForStart(global, millis)?.let { numericalSearch(limits, worker, it) }
                    }
                    BoundedCompanionSearch(search, "finiteNumericalCompanion",
                        activeLimit = requireNotNull(cost.activeMillis).milliseconds,
                        sliceLimit = requireNotNull(cost.sliceMillis).milliseconds)
                }) { _, _, _, _ -> error("numerical companion runs only by slice") }.bindEvidence(identity)
                worker.also {
                    it.startAfterIncumbent = afterIncumbent
                    it.startAfterAcceptedImprovement = afterImprovement
                }
            },
            factory(firstArm + 1, ArmFamily.LocalSearch) { slot ->
                lateinit var companion: PortfolioWorker
                val limits = local(slot)
                val worker = context?.let { repairWorker(slot, firstArm + 1, limits, it) { companion } }
                companion = PortfolioWorker.ofMinimize("ls/finite-joint-companion", firstArm + 1,
                    countsInstructions = true,
                    resumable = {
                        val search = if (worker != null) requireNotNull(worker.newResumableSearch(incumbents::bound))
                        else DeferredCompanionSearch({ requireNotNull(admission).declineReason }) { global, millis ->
                            requireNotNull(admission).contextForStart(global, millis)?.let {
                                requireNotNull(repairWorker(slot, firstArm + 1, limits, it) { companion }
                                    .newResumableSearch(incumbents::bound))
                            }
                        }
                        BoundedCompanionSearch(search,
                            "finiteJointCompanion", activeLimit = requireNotNull(cost.activeMillis).milliseconds)
                    }) { _, _, _, _ -> error("joint companion runs only by slice") }
                    .bindEvidence(identity, provesResults = false)
                companion.also {
                    it.startAfterIncumbent = afterIncumbent
                    it.startAfterAcceptedImprovement = afterImprovement
                }
            },
        )
    }

    private fun repairWorker(slot: PortfolioWorkerSlot, arm: Int, limits: TheoryParams,
        context: SourceProofContext, companion: () -> PortfolioWorker): PortfolioWorker {
        var readVersion = 0L
        var importedVersion = 0L
        val repair = slot.own(JointLinearRepair(model, context, objective, limits,
            boundOrdered = true, external = {
                sharedIncumbent(companion()) { readVersion = it }
            }, onIncumbentImport = {
                importedVersion = readVersion
                companion().observeLifecycle(PortfolioLifecycleKind.INCUMBENT_IMPORTED, importedVersion)
            }, onIncumbentUse = {
                companion().observeLifecycle(PortfolioLifecycleKind.INCUMBENT_CONSUMED, importedVersion)
            }, publicationLifetime = limits.cancellation or limits.timeout, observeExternalIncumbents = true,
            sharedCompletions = completionSharing,
            incumbentCompletionCheck = incumbentCompletionCheck))
        return exactNeighborhoodWorker("ls/finite-joint-companion", arm, objective,
            limits.cancellation, limits.timeout, { repair.exhausted },
            pendingIncumbentTrial = { repair.incumbentTrialPending },
            open = { repair.search(adaptive = false, seed = slot.seed) })
    }

    private fun sharedIncumbent(worker: PortfolioWorker, onVersion: (Long) -> Unit) =
        incumbents.exchange.current()?.also {
            onVersion(it.version)
            worker.observeLifecycle(PortfolioLifecycleKind.INCUMBENT_READ, it.version)
        }?.assignment

    private fun numericalSearch(limits: TheoryParams, worker: PortfolioWorker,
        context: SourceProofContext): ResumableSearch =
        object : ResumableSearch {
            private var readVersion = 0L
            private var importedVersion = 0L
            private val search = LinearAuthorityInvocation(context, limits, boundOrdered = true,
                external = { sharedIncumbent(worker) { readVersion = it } }, onIncumbentImport = {
                    importedVersion = readVersion
                    worker.observeLifecycle(PortfolioLifecycleKind.INCUMBENT_IMPORTED, importedVersion)
                }, onIncumbentUse = {
                    worker.observeLifecycle(PortfolioLifecycleKind.INCUMBENT_CONSUMED, importedVersion)
                }, observeExternalIncumbents = true, sharedCompletions = completionSharing,
                incumbentCompletionCheck = incumbentCompletionCheck)
            private var result: MinimizeResult? = null
            override val isDone: Boolean get() = result != null
            override val stats get() = search.stats
            override val work: Long get() = search.work

            override fun runSlice(global: Cancellation, sliceMillis: Long, sliceNodes: Long,
                onIncumbent: (MinimizeResult.WithSample) -> Unit): MinimizeResult? {
                result?.let { return it }
                val finished = search.runSlice(global, sliceMillis, sliceNodes) { sample, value ->
                    onIncumbent(MinimizeResult.BestFound(sample, value.toDouble(),
                        TerminationReason.BudgetExhausted, stats))
                } ?: return null
                val original = LinearOptimizationResult(context.scope, finished.terminal, finished.bound,
                    finished.incumbent, finished.value, finished.reason, finished.stats, context.semantics)
                result = finiteNumericalCompletion(original, context, global or limits.cancellation or limits.timeout)
                return result
            }

            override fun close() = search.close()
        }

    companion object {
        fun admission(model: BakedProblem, objective: LinearObjective, cancellation: Cancellation,
            cost: WorkerConstructionCost): FiniteCompanionAdmission = FiniteCompanionAdmission(
            cancellation, requireNotNull(cost.activeMillis), requireNotNull(cost.sliceMillis),
            capture = { captureContext(model, objective, it) })

        fun deferred(model: BakedProblem, objective: LinearObjective, params: TheoryParams,
            cancellation: Cancellation, cost: WorkerConstructionCost, admission: FiniteCompanionAdmission,
            incumbentCompletionCheck: ((Sample, BigFraction, Cancellation) -> Boolean)?,
            afterImprovement: Boolean = false): FiniteNumericalCompanions =
            FiniteNumericalCompanions(model, objective, null, params, cancellation, cost, true,
                incumbentCompletionCheck, admission, afterImprovement)

        fun prepare(model: BakedProblem, objective: LinearObjective, params: TheoryParams,
            cancellation: Cancellation, cost: WorkerConstructionCost,
            afterIncumbent: Boolean = false,
            incumbentCompletionCheck: ((Sample, BigFraction, Cancellation) -> Boolean)? = null,
            clock: TimeSource = TimeSource.Monotonic,
        ): Preparation {
            val started = clock.markNow()
            val context = captureContext(model, objective, cancellation)
            val companions = context?.let {
                FiniteNumericalCompanions(model, objective, it, params, cancellation, cost, afterIncumbent,
                    incumbentCompletionCheck)
            }
            return Preparation(companions, started.elapsedNow().inWholeNanoseconds)
        }

        private fun captureContext(model: BakedProblem, objective: LinearObjective,
            cancellation: Cancellation): SourceProofContext? {
            // The propagated difference graph duplicates affine rows and bounds in these coordinates.
            val affine = model.withFactors(model.factors.filterNot { it is DifferenceSystem }.toTypedArray())
            return affine.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE, cancellation,
                numericalIndicators = true)
        }
    }
}

internal fun finiteNumericalCompletion(result: LinearOptimizationResult, context: SourceProofContext,
    cancellation: Cancellation): MinimizeResult {
    // A verified relaxation proof uses the finite working coordinates and objective; finite admission checks the point.
    if (result.verifyTerminal(context, cancellation) is Verification.Accepted) {
        when (val claim = result.terminal?.claim) {
            is SourceClaim.Optimal -> return MinimizeResult.Optimal(claim.sample,
                requireNotNull((claim.value as? SourceValue.Rational)?.value)
                    .toDouble(), result.stats)
            SourceClaim.Refutation -> return MinimizeResult.Infeasible(stats = result.stats)
            else -> Unit
        }
    }
    val point = result.incumbent
    val value: BigFraction? = result.value
    val reason = if (result.reason == TerminationReason.SearchExhausted) {
        TerminationReason.Unsupported
    } else result.reason
    return if (point == null || value == null) MinimizeResult.Unknown(reason, result.stats)
    else MinimizeResult.BestFound(point, value.toDouble(), reason, result.stats)
}
