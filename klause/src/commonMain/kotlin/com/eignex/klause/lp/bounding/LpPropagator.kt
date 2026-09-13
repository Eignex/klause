package com.eignex.klause.lp.bounding

import com.eignex.klause.lp.engine.Basis
import com.eignex.klause.lp.engine.CertifiedLpResult
import com.eignex.klause.lp.engine.DEFAULT_REFACTOR_UPDATE_LIMIT
import com.eignex.klause.lp.engine.ExactLpBounds
import com.eignex.klause.lp.engine.ExactLpModel
import com.eignex.klause.lp.engine.ExactLpPremises
import com.eignex.klause.lp.engine.ExactLpSide
import com.eignex.klause.lp.engine.FloatLpResult
import com.eignex.klause.lp.engine.LpBoundAssertion
import com.eignex.klause.lp.engine.LpBoundBatchResult
import com.eignex.klause.lp.engine.LpCertificationObserver
import com.eignex.klause.lp.engine.LpExactCitedSide
import com.eignex.klause.lp.engine.LpExactState
import com.eignex.klause.lp.engine.LpExactSupport
import com.eignex.klause.lp.engine.LpFloatAllowance
import com.eignex.klause.lp.engine.LpPricingOptions
import com.eignex.klause.lp.engine.LpRootAdmission
import com.eignex.klause.lp.engine.LpScopedMetrics
import com.eignex.klause.lp.engine.LpScopedRow
import com.eignex.klause.lp.engine.LpScopedRows
import com.eignex.klause.lp.engine.LpScopedSolver
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.lp.engine.LpSolveMetrics
import com.eignex.klause.lp.engine.LpSolver
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ExactContinuationLimits
import com.eignex.klause.solver.search.ComponentCheck
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchAtomPremise
import com.eignex.klause.solver.search.SearchBrancher
import com.eignex.klause.solver.search.SearchComponent
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchExplanation
import com.eignex.klause.solver.search.explainAtoms
import com.eignex.klause.util.Cancellation
import com.ionspin.kotlin.bignum.integer.BigInteger

internal data class LpEffortProfile(
    val iterations: Int = 0,
    val work: Long = 0L,
    val trackDegeneracy: Boolean = false,
    val maxRows: Int = Int.MAX_VALUE,
    val refactorUpdates: Int = DEFAULT_REFACTOR_UPDATE_LIMIT,
    val pricing: LpPricingOptions = LpPricingOptions(),
    val continuation: ExactContinuationLimits = ExactContinuationLimits(),
    val fullContinuation: Boolean = true,
)

internal interface LpSearchPolicy {
    fun initialize(context: SearchContext) = Unit
    fun assert(decision: SearchDecision, context: SearchContext): ComponentResult = ComponentResult.Consistent
    fun propagate(context: SearchContext): ComponentResult = ComponentResult.Consistent
    fun check(context: SearchContext): ComponentCheck = ComponentCheck.Indeterminate
    fun nextBranch(context: SearchContext): List<SearchDecision>? = null
    fun fractionalBranch(context: SearchContext): LpFractionalBranch? = null
    fun retract(decisionLevel: Int) = Unit
    fun restart(context: SearchContext) = Unit
}

internal data class LpFractionalBranch(
    val variable: Int,
    val value: BigFraction,
    val boolean: Boolean = false,
    val registered: Boolean = true,
)

internal class LpPropagator(
    private val policy: LpSearchPolicy,
    private val effort: () -> LpEffortProfile = { LpEffortProfile() },
    private val solveContext: LpSolveContext = LpSolveContext.Production,
    private val cancellation: Cancellation = Cancellation.Never,
    private val certificationObserver: LpCertificationObserver? = null,
) : SearchComponent,
    SearchBrancher,
    AutoCloseable {
    private var owner: LpScopedSolver? = null
    private var epochWarm: Basis? = null
    private var proofContext: SearchContext? = null
    private var modelKey: Any? = null
    private var rootState: LpExactState? = null

    // Local cut models may reinstall after an epoch; their initial bounds still need source premises.
    private var sourceRootBounds = true
    private var baseSidePremises: Map<Pair<Int, Boolean>, SearchAtomPremise> = emptyMap()
    private var closed = false
    private var invalidated = false
    private var nextWitness = 0L
    private var preparedWork = 0L
    private var preparedRefactors = 0L
    private var solved = false
    private var pendingRootAdmission = false
    private val witnesses = HashMap<Long, SearchAtomPremise>()
    var sourcePremises: LpSourcePremises? = null
        private set
    var lastMetrics = LpSolveMetrics()
        private set
    var lastEpochMetrics = LpSolveMetrics()
        private set
    val state: LpExactState? get() = owner?.state
    val metrics: LpScopedMetrics? get() = owner?.metrics

    fun boundPremise(witness: Long): SearchAtomPremise = witnesses[witness] ?: SearchAtomPremise.Unavailable

    fun explainConflict(support: LpExactSupport?, context: SearchContext): SearchExplanation? {
        val current = state ?: return null
        if (support == null || support.state !== current || proofContext !== context || context.cancelled()) return null
        val leaves = ArrayList<SearchAtomPremise>()
        for ((row, metadata) in support.rows) {
            if (row !in 0 until current.model.m || current.model.row(row) != metadata ||
                !current.rows.row(row).active
            ) {
                return null
            }
            if (!metadata.global) leaves += metadata.premises.asPremise()
        }
        for (cited in support.sides) {
            if (cited.column !in 0 until current.model.numVars) return null
            val active = current.activeSide(cited.column, cited.upper) ?: return null
            if (active.side != cited.side || active.witness != cited.witness) return null
            val declared = rootState?.takeIf { cited.column < it.model.numVars }
                ?.activeSide(cited.column, cited.upper)
            leaves += if (declared == active) {
                if (sourceRootBounds) {
                    SearchAtomPremise.All(emptyList())
                } else {
                    baseSidePremises[cited.column to cited.upper] ?: SearchAtomPremise.Unavailable
                }
            } else {
                boundPremise(active.witness)
            }
            cited.side.premises?.let { leaves += it.asPremise() }
        }
        return context.explainAtoms(SearchAtomPremise.All(leaves))
    }

    private fun ExactLpPremises?.asPremise(): SearchAtomPremise {
        if (this == null || boundEntries().isNotEmpty() || literalEntries().isEmpty()) {
            return SearchAtomPremise.Unavailable
        }
        return SearchAtomPremise.All(
            literalEntries().map {
                SearchAtomPremise.Asserted(SearchDecision.Bool(it))
            },
        )
    }

    fun install(key: Any, model: ExactLpModel, rootAdmission: LpRootAdmission? = null): Boolean {
        val admitted = if (rootAdmission != null) rootAdmission.claim(key, model) ?: return false else null
        if (closed || cancellation()) return false
        if (modelKey === key && owner != null) return rootAdmission == null
        reset()
        val initial = admitted ?: LpExactState(model)
        owner = newOwner(initial, rootAdmission)
        pendingRootAdmission = rootAdmission != null
        rootState = initial
        modelKey = key
        sourcePremises = LpSourcePremises(key)
        return true
    }

    fun replaceEpoch(
        key: Any,
        model: ExactLpModel,
        warm: Basis?,
        token: Cancellation,
        validatePublication: () -> Boolean,
        premises: Map<Pair<Int, Boolean>, SearchAtomPremise> = emptyMap(),
        rows: LpScopedRows? = null,
        preserveSourcePremises: Boolean = false,
        publish: () -> Unit,
    ): Boolean {
        lastEpochMetrics = LpSolveMetrics()
        if (closed || pendingRootAdmission || token() || (owner?.state?.depth ?: 0) != 0 ||
            (preserveSourcePremises && modelKey !== key)
        ) {
            return false
        }
        val donor = owner
        val donorState = donor?.state
        val receipt = donor?.exportEpochReceipt().takeIf { preserveSourcePremises }
        if (preserveSourcePremises && (
                receipt == null || donorState?.model?.sameAuthority(model) != true ||
                    rows?.sameAuthority(donorState.rows) != true
                )
        ) {
            return false
        }
        val savedPremises = sourcePremises.takeIf { preserveSourcePremises }
        val initial = LpExactState(model, rows = rows ?: LpScopedRows.initial(model.m))
        val candidate = newOwner(initial)
        var preparing = true
        val preparationToken = Cancellation { cancellation() || (preparing && token()) }
        var published = false
        return AutoCloseable { if (!published) candidate.close() }.use {
            try {
                if (receipt != null && !candidate.importEpochReceipt(receipt)) return@use false
                val attempt = candidate.solveFloat(warm, preparationToken) ?: return@use false
                val basis = attempt.second?.basis ?: attempt.first.infeasibleBasis ?: return@use false
                if (basis.basicVars.size != model.m || basis.status.size != model.numVars ||
                    basis.basicVars.distinct().size != model.m ||
                    token() || !validatePublication() || token() || owner !== donor || donor?.state !== donorState ||
                    (preserveSourcePremises && donor?.exportEpochReceipt() != receipt)
                ) {
                    return@use false
                }
                val previous = owner
                owner = candidate
                epochWarm = Basis(basis.basicVars.copyOf(), basis.status.copyOf(), false)
                rootState = initial
                sourceRootBounds = false
                baseSidePremises = premises.toMap()
                modelKey = key
                sourcePremises = savedPremises ?: LpSourcePremises(key)
                witnesses.clear()
                nextWitness = 0L
                solved = true
                pendingRootAdmission = false
                invalidated = false
                preparedWork = candidate.metrics.preparationWork
                preparedRefactors = candidate.metrics.preparationRefactorizations
                lastMetrics = LpSolveMetrics()
                published = true
                preparing = false
                previous.use { publish() }
                true
            } finally {
                lastEpochMetrics = candidate.lastMetrics + LpSolveMetrics(
                    workOps = candidate.metrics.preparationWork,
                    initialRefactorizations = candidate.metrics.preparationRefactorizations.toInt(),
                )
            }
        }
    }

    fun refreshSourceEpoch(token: Cancellation, validatePublication: () -> Boolean): Boolean {
        val current = state ?: return false
        val key = modelKey ?: return false
        val premises = epochBasePremises() ?: return false
        return replaceEpoch(
            key,
            current.model,
            epochWarm?.takeIf {
                it.basicVars.size == current.model.m && it.status.size == current.model.numVars
            },
            token,
            { state === current && validatePublication() },
            premises,
            current.rows,
            preserveSourcePremises = true,
        ) { }
    }

    fun epochBasePremises(): Map<Pair<Int, Boolean>, SearchAtomPremise>? {
        val current = state ?: return null
        if (current.depth != 0) return null
        val result = HashMap<Pair<Int, Boolean>, SearchAtomPremise>()
        for (column in 0 until current.model.numVars) {
            for (upper in listOf(false, true)) {
                val active = current.activeSide(column, upper) ?: continue
                val declared = rootState?.takeIf { column < it.model.numVars }?.activeSide(column, upper)
                result[column to upper] = if (declared == active) {
                    if (sourceRootBounds) {
                        SearchAtomPremise.All(emptyList())
                    } else {
                        baseSidePremises[column to upper] ?: SearchAtomPremise.Unavailable
                    }
                } else {
                    boundPremise(active.witness)
                }
            }
        }
        return result.toMap()
    }

    private fun newOwner(initial: LpExactState, rootAdmission: LpRootAdmission? = null): LpScopedSolver {
        val ordinary = effort()
        // Initial admission prepares logicals and solves in two separately metered invocations.
        val profile = if (rootAdmission == null) {
            ordinary
        } else {
            ordinary.copy(
                work = rootAdmission.workLimit?.div(2L) ?: 0L,
                iterations = rootAdmission.iterationLimit ?: 0,
            )
        }
        return LpScopedSolver(
            initial,
            cancellation,
            solveContext,
            refactorUpdateLimit = profile.refactorUpdates,
            iterationLimit = profile.iterations,
            workLimit = profile.work,
            trackDegeneracy = profile.trackDegeneracy,
            maxRetainedRows = profile.maxRows,
            pricing = profile.pricing,
        )
    }

    fun atLevel(depth: Int, token: Cancellation = cancellation): Boolean {
        val current = owner ?: return false
        if (depth < current.state.depth && !current.pop(depth, token)) return invalidate()
        while (current.state.depth < depth) if (!current.push(token)) return invalidate()
        return true
    }

    fun assertBound(
        column: Int,
        upper: Boolean,
        side: ExactLpSide,
        premise: SearchAtomPremise = SearchAtomPremise.Unavailable,
    ): Boolean {
        val current = owner ?: return false
        if (column !in 0 until current.state.model.numVars) return invalidate()
        val previous = current.state.activeSide(column, upper)?.side
        if (previous == side) return true
        val witness = nextWitness++
        if (witness == Long.MAX_VALUE || !current.assertBound(column, upper, side, witness)) {
            return invalidate()
        }
        witnesses[witness] = premise
        lastMetrics = LpSolveMetrics()
        return true
    }

    fun assertBounds(lower: List<ExactLpSide>, upper: List<ExactLpSide>): LpBoundBatchResult {
        val current = owner ?: return LpBoundBatchResult.Declined(0)
        current.state.conflict?.let { return LpBoundBatchResult.Conflict(0, it) }
        if (lower.size != upper.size || lower.size > current.state.model.numVars || cancellation()) {
            return LpBoundBatchResult.Declined(0)
        }
        val assertions = ArrayList<LpBoundAssertion>()
        columns@ for (column in lower.indices) {
            var activeLower = current.state.activeSide(column, false)?.side
            var activeUpper = current.state.activeSide(column, true)?.side
            for (upperSide in listOf(false, true)) {
                if (cancellation()) return LpBoundBatchResult.Declined(assertions.size)
                val side = if (upperSide) upper[column] else lower[column]
                val previous = if (upperSide) activeUpper else activeLower
                if (previous == side) continue
                if (nextWitness > Long.MAX_VALUE - assertions.size - 1L) {
                    return LpBoundBatchResult.Declined(assertions.size + 1)
                }
                assertions.add(
                    LpBoundAssertion(column, upperSide, side, nextWitness + assertions.size, current.state.depth),
                )
                val comparison = previous?.let { side.number.value.compareTo(it.number.value) }
                if (comparison == null || (if (upperSide) comparison < 0 else comparison > 0) ||
                    (comparison == 0 && side.strict && !previous.strict)
                ) {
                    if (upperSide) activeUpper = side else activeLower = side
                }
                if (!ExactLpBounds(activeLower, activeUpper).consistent) break@columns
            }
        }
        val result = current.assertBounds(assertions)
        if (result !is LpBoundBatchResult.Declined) {
            for (index in 0 until result.count) {
                witnesses[assertions[index].witness] = SearchAtomPremise.Unavailable
            }
            nextWitness += result.count
            if (result.count > 0) lastMetrics = LpSolveMetrics()
        }
        return result
    }

    fun append(row: LpScopedRow, scoped: Boolean): Boolean = owner?.append(row, scoped) == true
    fun deactivate(row: Long): Boolean = owner?.deactivate(row) == true

    fun solveFloat(warm: Basis? = null, token: Cancellation = cancellation): Pair<LpSolver, FloatLpResult?>? {
        val admitted = pendingRootAdmission
        val result = solveOwned { current ->
            val allowance = if (solved && !admitted) effort().let { LpFloatAllowance(it.work, it.iterations) } else null
            current.solveFloat(if (solved) null else warm, token, allowance).also { solved = true }
        }
        if (admitted) {
            pendingRootAdmission = false
            if (result == null) {
                val recorded = lastMetrics
                try {
                    invalidate()
                } finally {
                    lastMetrics = recorded
                }
            }
        }
        return result
    }

    fun solve(): CertifiedLpResult? = solveOwned {
        val profile = effort()
        it.solve(
            continuationLimits = profile.continuation,
            fullContinuation = profile.fullContinuation,
            observer = certificationObserver,
        ).also { result ->
            result?.float?.basis?.let { basis ->
                epochWarm = Basis(basis.basicVars.copyOf(), basis.status.copyOf(), false)
            }
        }
    }

    private inline fun <T> solveOwned(action: (LpScopedSolver) -> T): T? = withOwner { current ->
        try {
            action(current)
        } finally {
            recordWork(current)
        }
    }

    @Suppress("TooGenericExceptionCaught") // A failed owner is invalid; preserve its primary and cleanup failures.
    private inline fun <T> withOwner(action: (LpScopedSolver) -> T): T? {
        val current = owner ?: return null
        return try {
            action(current)
        } catch (primary: Throwable) {
            val metrics = lastMetrics
            try {
                invalidate()
            } catch (cleanup: Throwable) {
                primary.addSuppressed(cleanup)
            } finally {
                lastMetrics = metrics
            }
            throw primary
        }
    }

    fun resetRoot(): Boolean {
        lastMetrics = LpSolveMetrics()
        val initial = rootState ?: return false
        if (withOwner { it.resetRoot(initial, cancellation) } != true) return invalidate()
        witnesses.clear()
        nextWitness = 0L
        sourcePremises = LpSourcePremises(requireNotNull(modelKey))
        return true
    }

    private fun recordWork(current: LpScopedSolver) {
        val metrics = current.metrics
        lastMetrics = current.lastMetrics + LpSolveMetrics(
            workOps = metrics.preparationWork - preparedWork,
            initialRefactorizations = (metrics.preparationRefactorizations - preparedRefactors).toInt(),
        )
        preparedWork = metrics.preparationWork
        preparedRefactors = metrics.preparationRefactorizations
    }

    override fun initialize(context: SearchContext): ComponentResult {
        if (proofContext != null && proofContext !== context) return ComponentResult.Indeterminate
        policy.initialize(context)
        proofContext = context
        return ComponentResult.Consistent
    }

    override fun assert(decision: SearchDecision, context: SearchContext): ComponentResult {
        if (proofContext != null && proofContext !== context) return ComponentResult.Indeterminate
        proofContext = context
        if (owner != null && !atLevel(context.decisionLevel, Cancellation.Never)) return ComponentResult.Indeterminate
        sourcePremises?.record(decision, context)
        return policy.assert(decision, context)
    }

    override fun propagate(context: SearchContext): ComponentResult {
        if (closed || context.cancelled()) return ComponentResult.Indeterminate
        val result = policy.propagate(context)
        if (result !is ComponentResult.Consistent) return result
        val current = state ?: return result
        val conflict = current.conflict ?: return result
        val row = conflict.column - current.model.n
        val support = LpExactSupport(
            current,
            if (row >= 0) listOf(row to current.model.row(row)) else emptyList(),
            listOf(conflict.lower, conflict.upper).map {
                LpExactCitedSide(it.column, it.upper, it.side, it.witness)
            },
        )
        return ComponentResult.Conflict(explainConflict(support, context))
    }

    override fun check(context: SearchContext): ComponentCheck =
        if (closed || invalidated || context.cancelled()) ComponentCheck.Indeterminate else policy.check(context)

    override fun nextBranch(context: SearchContext): List<SearchDecision>? {
        if (closed || context.cancelled()) return null
        val candidate = policy.fractionalBranch(context)
        if (candidate != null && candidate.value.den != BigInteger.ONE) {
            if (candidate.boolean && candidate.value > BigFraction.ZERO && candidate.value < BigFraction.ONE) {
                return listOf(
                    SearchDecision.Bool((candidate.variable shl 1) or 1),
                    SearchDecision.Bool(candidate.variable shl 1),
                )
            }
            if (!candidate.boolean) {
                lpIntegerBranch(candidate.variable, candidate.value, context, candidate.registered)?.let { return it }
            }
        }
        return policy.nextBranch(context)
    }

    override fun retract(decisionLevel: Int) {
        val current = owner
        if (current != null && current.state.depth > decisionLevel &&
            !current.pop(decisionLevel, Cancellation.Never)
        ) {
            invalidate()
        }
        lastMetrics = LpSolveMetrics()
        val active = state?.assertions?.map { it.witness }?.toSet().orEmpty()
        witnesses.keys.retainAll(active)
        policy.retract(decisionLevel)
    }

    override fun onRestart(context: SearchContext) = policy.restart(context)

    fun releaseSolver() {
        if (pendingRootAdmission) {
            invalidate()
            return
        }
        val current = owner ?: return
        val retained = current.state
        owner = null
        current.close()
        solved = false
        preparedWork = 0L
        preparedRefactors = 0L
        owner = newOwner(retained)
    }

    fun reset() {
        val previous = owner
        owner = null
        invalidated = false
        modelKey = null
        rootState = null
        baseSidePremises = emptyMap()
        epochWarm = null
        proofContext = null
        sourcePremises = null
        witnesses.clear()
        solved = false
        pendingRootAdmission = false
        nextWitness = 0L
        preparedWork = 0L
        preparedRefactors = 0L
        lastMetrics = LpSolveMetrics()
        previous?.close()
    }

    private fun invalidate(): Boolean {
        try {
            reset()
        } finally {
            invalidated = true
        }
        return false
    }

    override fun close() {
        if (closed) return
        closed = true
        reset()
    }
}
