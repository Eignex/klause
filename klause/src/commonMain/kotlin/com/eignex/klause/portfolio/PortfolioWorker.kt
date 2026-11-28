package com.eignex.klause.portfolio

import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.solver.InstructionSlicedSearch
import com.eignex.klause.solver.ReseedGuard
import com.eignex.klause.solver.ResumableSearch
import com.eignex.klause.solver.ResumableSolve
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.Session
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.SolverParams
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.solver.incumbent.CompletedEngineResult
import com.eignex.klause.solver.incumbent.EngineInvocation
import com.eignex.klause.solver.incumbent.EvidenceCertificate
import com.eignex.klause.solver.incumbent.EvidenceKind
import com.eignex.klause.solver.incumbent.IncumbentExchange
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.objective.OptimalityGap
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.SharingChannel
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.ExecutionControl

/**
 * One configured engine instance in a [Portfolio]: a [Session] with the base params it runs under, erased behind
 * closures so one portfolio can hold workers of different engines. Construct via [of].
 */
class PortfolioWorker private constructor(
    /** Id for progress and telemetry, such as `ls/cbls/fixed`. */
    val label: String,
    /** Index of this worker's arm in the composed pool; replicas of one arm share it. */
    val armId: Int,
    private val solveFn: (ExecutionControl, Long?) -> SolveResult,
    private val improvementsFn: (() -> Double, Sample?, ExecutionControl, Long?) -> Sequence<MinimizeResult>,
    private val samplesFn: (ExecutionControl) -> Sequence<Sample>,
    private val resumableFn: ((() -> Double, ExecutionControl?, Sample?) -> ResumableSearch?)?,
    private val resumableSolveFn: ((ExecutionControl?) -> ResumableSolve?)?,
    private val withInstructions: Boolean,
    private val closeFn: () -> Unit,
) : AutoCloseable {

    /** Solve once, honouring [cancel] (set when a sibling wins the race). */
    fun solve(cancel: ExecutionControl, maxInstructions: Long? = null): SolveResult = solveFn(cancel, maxInstructions)

    internal var sharedPools: SharedPools? = null

    // Each contribution earns this worker that contribution's signal.
    internal var sharing: Set<Contribution> = emptySet()

    internal var sharingMeter: SharingMeter? = null

    // In the pool's objective units; null closes bounds exactly.
    internal var optimalityGap: OptimalityGap? = null

    internal var publicationExchange: IncumbentExchange<Sample, Double>? = null

    internal fun <T> withPublicationBatch(cancellation: ExecutionControl, action: () -> T): T {
        val exchange = publicationExchange ?: return action()
        val meter = checkNotNull(sharingMeter)
        return exchange.withBufferedOffers(cancellation, meter::incumbentEvent,
            { block -> meter.timed(SharingChannel.Incumbents, block) }, action)
    }

    internal val signals: Set<Signal>
        get() = (if (withInstructions) COUNTED_SIGNALS else SEARCH_SIGNALS) + sharing.map { it.signal }

    /** Whether this worker accepts the counted instruction budget used to schedule LS segments. */
    val acceptsInstructionBudget: Boolean get() = withInstructions

    internal var family: ArmFamily = if (withInstructions) ArmFamily.LocalSearch else ArmFamily.Backtrack

    internal var restartingSearch: Boolean = false

    // Null outside a composed roster.
    internal var role: String? = null

    internal var improvementOnly: Boolean = false

    internal var firstWitnessOnly: Boolean = false

    internal var auxiliaryProof: Boolean = false

    internal var earlyCoreTrial: Boolean = false

    internal var retainInitialProofCoverage: Boolean = false

    internal var startAfterIncumbent: Boolean = false
    internal var startAfterAcceptedImprovement: Boolean = false
    internal var booleanProofRecipe: BooleanProofRecipe? = null
    internal var constructionNanos: Long = 0
    internal var createdNanos: Long = 0
    internal var onLifecycle: ((PortfolioLifecycleKind, Long) -> Unit)? = null

    internal fun observeLifecycle(kind: PortfolioLifecycleKind, version: Long = 0) {
        onLifecycle?.invoke(kind, version)
    }

    internal var controlsObjectiveCutoff: Boolean = false
    internal var canonicalIncumbentIsCutoff: Boolean = false

    private var configuredModel: ModelIdentity? = null
    private var provesSourceResults = true
    internal var sourceBinding: SourceWorkerBinding? = null
        private set
    internal val evidenceModel: ModelIdentity? get() = sourceBinding?.model ?: configuredModel
    internal val permitsSourceProofs: Boolean get() = sourceBinding?.provesResults ?: provesSourceResults
    internal val evidenceScope: SourceScope? get() = sourceBinding?.scope
    internal val evidenceInvocation: EngineInvocation? get() = sourceBinding?.invocation
    internal val sharingInvocation: EngineInvocation? get() = sourceBinding?.sharingInvocation
    internal var executionAssumptions: Assumptions? = null
        private set

    internal fun bindSource(binding: SourceWorkerBinding): PortfolioWorker = also {
        require(evidenceModel?.matches(binding.model) != false) { "binding differs from the worker model" }
        require(permitsSourceProofs || !binding.provesResults) { "binding cannot promote witness authority" }
        val existing = sourceBinding
        require(existing == null || existing.matches(binding)) { "worker cannot change its source binding" }
        if (existing == null) {
            binding.sharingInvocation?.let { invocation ->
                sharedPools?.bounds?.bindInvocation(invocation)
                sharedPools?.varBounds?.bindInvocation(invocation)
            }
            sourceBinding = binding
        }
    }

    internal fun certificateFor(kind: EvidenceKind, completion: CompletedEngineResult): EvidenceCertificate? {
        val model = evidenceModel ?: return null
        if (!permitsSourceProofs || evidenceScope != completion.scope) return null
        val runtime = evidenceInvocation
        if (runtime != null && completion.invocation?.let { runtime.matches(it) } != true) return null
        if ((evidenceInvocation == null) != (completion.invocation == null)) return null
        return EvidenceCertificate.completed(model, kind, completion)
    }

    internal fun bindEvidence(model: ModelIdentity, provesResults: Boolean = true): PortfolioWorker = also {
        require(evidenceModel?.matches(model) != false) { "worker cannot change its model or objective" }
        require(sourceBinding == null || permitsSourceProofs == (permitsSourceProofs && provesResults)) {
            "worker cannot change its source authority"
        }
        configuredModel = model
        provesSourceResults = provesSourceResults && provesResults
    }

    /**
     * Opens a pause/resume handle over this worker's optimisation, or `null` when the engine cannot pause.
     * [readBound] exposes the portfolio's shared best objective for bound pruning.
     */
    fun newResumableSearch(readBound: () -> Double): ResumableSearch? = resumableFn?.invoke(readBound, null, null)

    internal fun newResumableSearch(
        readBound: () -> Double,
        cancellation: ExecutionControl,
        warmStart: Sample? = null,
    ): ResumableSearch? = resumableFn?.invoke(readBound, cancellation, warmStart)

    /** Opens a pause/resume handle over this worker's satisfaction search, or `null` when the engine cannot pause. */
    fun newResumableSolve(): ResumableSolve? = resumableSolveFn?.invoke(null)

    internal fun newResumableSolve(cancellation: ExecutionControl): ResumableSolve? =
        resumableSolveFn?.invoke(cancellation)

    /**
     * Streams improving incumbents. [readBound] exposes the portfolio's shared best objective to engines that
     * prune on it. [warmStart] is the portfolio's incumbent, used by engines with a warm-start seam. Every worker
     * minimises the same canonical objective, so [MinimizeResult.objectiveValue] is comparable across the pool.
     */
    fun improvements(
        readBound: () -> Double,
        cancel: ExecutionControl,
        warmStart: Sample? = null,
        maxInstructions: Long? = null,
    ): Sequence<MinimizeResult> = improvementsFn(readBound, warmStart, cancel, maxInstructions)

    /** Stream diverse samples, honouring [cancel] (set when the collector stops). */
    fun samples(cancel: ExecutionControl): Sequence<Sample> = samplesFn(cancel)

    override fun close() {
        closeFn()
    }

    internal fun objectiveCoordinateProposals(
        model: ModelIdentity,
        proposal: (Sample) -> MinimizeResult.BestFound?,
    ): PortfolioWorker {
        fun checked(result: MinimizeResult): MinimizeResult {
            val reason = when (result) {
                is MinimizeResult.BestFound -> result.reason
                is MinimizeResult.Unknown -> result.reason
                else -> TerminationReason.SearchExhausted
            }
            return result.assignment?.let(proposal)?.copy(reason = reason, stats = result.stats)
                ?: MinimizeResult.Unknown(reason, result.stats)
        }

        return PortfolioWorker(
            label = "$label/objective-coordinates",
            armId = armId,
            solveFn = { _, _ -> error("objective coordinate worker only minimizes") },
            // Private coordinates and objective offsets cannot consume canonical warm starts or cutoffs.
            improvementsFn = { _, _, cancel, limit ->
                improvements({ Double.POSITIVE_INFINITY }, cancel, maxInstructions = limit).map(::checked)
            },
            samplesFn = { emptySequence() },
            resumableFn = { _, cancel, _ ->
                val search = resumableFn?.invoke({ Double.POSITIVE_INFINITY }, cancel, null)
                search?.let { child ->
                    object : ResumableSearch by child, InstructionSlicedSearch, ReseedGuard {
                        override val chargedInstructions: Long
                            get() = (child as? InstructionSlicedSearch)?.chargedInstructions
                                ?: child.stats.ls.moves.sum.toLong()
                        override val auxiliaryWork: Long
                            get() = (child as? InstructionSlicedSearch)?.auxiliaryWork ?: 0L
                        override val canReseed: Boolean get() = (child as? ReseedGuard)?.canReseed ?: true
                        override val canContinuePreparation: Boolean
                            get() = (child as? ReseedGuard)?.canContinuePreparation ?: false
                        override val incumbentTrialPending: Boolean
                            get() = (child as? ReseedGuard)?.incumbentTrialPending == true

                        override fun runSlice(
                            global: ExecutionControl,
                            sliceMillis: Long,
                            onIncumbent: (MinimizeResult.WithSample) -> Unit,
                        ): MinimizeResult? = runSlice(global, sliceMillis, -1L, onIncumbent)

                        override fun runSlice(
                            global: ExecutionControl,
                            sliceMillis: Long,
                            sliceNodes: Long,
                            onIncumbent: (MinimizeResult.WithSample) -> Unit,
                        ): MinimizeResult? = child.runSlice(global, sliceMillis, sliceNodes) { point ->
                            proposal(point.sample)?.copy(stats = point.stats)?.let(onIncumbent)
                        }?.let(::checked)

                        override fun runInstructionSlice(
                            global: ExecutionControl,
                            sliceMillis: Long,
                            sliceInstructions: Long,
                            onIncumbent: (MinimizeResult.WithSample) -> Unit,
                        ): MinimizeResult? {
                            val accept: (MinimizeResult.WithSample) -> Unit = { point ->
                                proposal(point.sample)?.copy(stats = point.stats)?.let(onIncumbent)
                            }
                            val terminal = if (child is InstructionSlicedSearch) {
                                child.runInstructionSlice(global, sliceMillis, sliceInstructions, accept)
                            } else child.runSlice(global, sliceMillis, sliceInstructions, accept)
                            return terminal?.let(::checked)
                        }

                        override fun runScheduledSlice(
                            global: ExecutionControl,
                            sliceMillis: Long,
                            sliceInstructions: Long,
                            sliceWork: Long,
                            instructionsPerWork: Double,
                            onIncumbent: (MinimizeResult.WithSample) -> Unit,
                        ): MinimizeResult? {
                            val accept: (MinimizeResult.WithSample) -> Unit = { point ->
                                proposal(point.sample)?.copy(stats = point.stats)?.let(onIncumbent)
                            }
                            val terminal = if (child is InstructionSlicedSearch) {
                                child.runScheduledSlice(
                                    global, sliceMillis, sliceInstructions, sliceWork, instructionsPerWork, accept,
                                )
                            } else child.runSlice(global, sliceMillis, sliceWork, accept)
                            return terminal?.let(::checked)
                        }
                    }
                }
            },
            resumableSolveFn = null,
            withInstructions = withInstructions,
            closeFn = ::close,
        ).bindEvidence(model, provesResults = false).also {
            it.family = family
            it.restartingSearch = restartingSearch
            it.firstWitnessOnly = true
        }
    }

    /** Factory for type-erased workers over a concrete typed session. */
    companion object {
        /**
         * Wraps a typed [session] and base [params] as a type-erased worker. [objective] may be null for a
         * satisfaction-only worker, on which [improvements] fails fast. [withBound] injects the shared objective
         * bound and [withWarmStart] a portfolio incumbent into the params; pass null for an engine without that
         * seam. [withInstructionBudget] installs a counted per-segment allowance; engines without a deterministic
         * segment counter leave it null.
         */
        fun <P : SolverParams> of(
            label: String,
            armId: Int,
            session: Session<P>,
            params: P,
            objective: LinearObjective? = null,
            withWarmStart: ((P, Sample) -> P)? = null,
            withInstructionBudget: ((P, Long) -> P)? = null,
            withBound: ((P, () -> Double) -> P)? = null,
        ): PortfolioWorker {
            // withCancellation declares a SolverParams return on the interface but every
            // backend overrides it covariantly to return its own P; the cast is sound.
            @Suppress("UNCHECKED_CAST")
            fun withCancel(c: ExecutionControl): P = params.withCancellation(c) as P

            fun withInstructions(p: P, maxInstructions: Long?): P {
                val budget = withInstructionBudget
                return if (maxInstructions != null && budget != null) budget(p, maxInstructions) else p
            }
            val resumableFn: ((() -> Double, ExecutionControl?, Sample?) -> ResumableSearch?)? =
                if (objective != null) {
                    { readBound, cancellation, warmStart ->
                        val base = cancellation?.let(::withCancel) ?: params
                        var p = withBound?.invoke(base, readBound) ?: base
                        if (warmStart != null && withWarmStart != null) p = withWarmStart(p, warmStart)
                        session.resumable(objective, p)
                    }
                } else {
                    null
                }
            return PortfolioWorker(
                label = label,
                armId = armId,
                solveFn = { c, maxInstructions ->
                    session.solve(withInstructions(withCancel(c), maxInstructions))
                },
                improvementsFn = { readBound, warmStart, c, maxInstructions ->
                    val obj = requireNotNull(objective) {
                        "PortfolioWorker '$label' was built without an objective; cannot stream improvements"
                    }
                    var p = withInstructions(withCancel(c), maxInstructions)
                    p = withBound?.invoke(p, readBound) ?: p
                    if (warmStart != null && withWarmStart != null) p = withWarmStart(p, warmStart)
                    session.improvements(obj, p)
                },
                samplesFn = { c -> session.samples(withCancel(c)) },
                resumableFn = resumableFn,
                resumableSolveFn = { cancellation ->
                    session.resumableSolve(cancellation?.let(::withCancel) ?: params)
                },
                withInstructions = withInstructionBudget != null,
                closeFn = { session.close() },
            ).also { it.executionAssumptions = params.assumptions }
        }

        // A worker over an engine with no Session. With resumable, a Portfolio resumes one handle each segment
        // instead of calling solve.
        internal fun ofSolve(
            label: String,
            armId: Int,
            countsInstructions: Boolean = false,
            resumable: (() -> ResumableSolve)? = null,
            solve: (ExecutionControl, Long?) -> SolveResult,
        ): PortfolioWorker = PortfolioWorker(
            label = label,
            armId = armId,
            solveFn = solve,
            improvementsFn = { _, _, _, _ -> error("PortfolioWorker '$label' cannot stream improvements") },
            samplesFn = { emptySequence() },
            resumableFn = null,
            resumableSolveFn = resumable?.let { open -> { _ -> open() } },
            withInstructions = countsInstructions,
            closeFn = {},
        )

        // An optimizing worker over an engine with no Session. With resumable, a Portfolio resumes one handle each
        // segment instead of calling improvements.
        internal fun ofMinimize(
            label: String,
            armId: Int,
            countsInstructions: Boolean = false,
            resumable: ((readBound: () -> Double) -> ResumableSearch)? = null,
            improvements: (() -> Double, Sample?, ExecutionControl, Long?) -> Sequence<MinimizeResult>,
        ): PortfolioWorker = PortfolioWorker(
            label = label,
            armId = armId,
            solveFn = { _, _ -> error("PortfolioWorker '$label' only minimizes") },
            improvementsFn = improvements,
            samplesFn = { emptySequence() },
            resumableFn = resumable?.let { open -> { readBound, _, _ -> open(readBound) } },
            resumableSolveFn = null,
            withInstructions = countsInstructions,
            closeFn = {},
        )
    }
}
