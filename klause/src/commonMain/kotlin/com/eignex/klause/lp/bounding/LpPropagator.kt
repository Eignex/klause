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
import com.eignex.klause.lp.engine.LpClaim
import com.eignex.klause.lp.engine.LpExactCitedSide
import com.eignex.klause.lp.engine.LpExactState
import com.eignex.klause.lp.engine.LpExactSupport
import com.eignex.klause.lp.engine.LpFloatAllowance
import com.eignex.klause.lp.engine.LpLayoutRemap
import com.eignex.klause.lp.engine.LpPricingOptions
import com.eignex.klause.lp.engine.LpPrimalGuidance
import com.eignex.klause.lp.engine.LpScopedMetrics
import com.eignex.klause.lp.engine.LpScopedRow
import com.eignex.klause.lp.engine.LpScopedSolver
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.lp.engine.LpSolveMetrics
import com.eignex.klause.lp.engine.LpSolver
import com.eignex.klause.lp.engine.strongerThan
import com.eignex.klause.lp.relaxation.LpCutEdit
import com.eignex.klause.lp.relaxation.LpSourceCompaction
import com.eignex.klause.lp.relaxation.LpSourceEdit
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ExactContinuationLimits
import com.eignex.klause.solver.search.ComponentCheck
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchAtomPremise
import com.eignex.klause.solver.search.SearchBranchKind
import com.eignex.klause.solver.search.SearchBranchProposal
import com.eignex.klause.solver.search.SearchBrancher
import com.eignex.klause.solver.search.SearchComponent
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchExplanation
import com.eignex.klause.solver.search.explainAtoms
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.MutableIntObjectMap

internal data class LpEffortProfile(
    val iterations: Int = 0,
    val work: Long = 0L,
    val trackDegeneracy: Boolean = false,
    val maxRows: Int = Int.MAX_VALUE,
    val refactorUpdates: Int = DEFAULT_REFACTOR_UPDATE_LIMIT,
    val pricing: LpPricingOptions = LpPricingOptions(),
    val continuation: ExactContinuationLimits = ExactContinuationLimits(),
    val fullContinuation: Boolean = true,
    val componentSplit: Boolean = false,
)

internal interface LpSearchPolicy {
    val eagerAssertionScopes: Boolean get() = true
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

internal data class LpPremisedBound(
    val column: Int,
    val upper: Boolean,
    val side: ExactLpSide,
    val premise: SearchAtomPremise,
)

internal class LpPropagator(
    private val policy: LpSearchPolicy,
    private val effort: () -> LpEffortProfile = { LpEffortProfile() },
    private val solveContext: LpSolveContext = LpSolveContext.Production,
    private val cancellation: Cancellation = Cancellation.Never,
    private val certificationObserver: LpCertificationObserver? = null,
    // Each bound edit costs the scoped solver a pass over the model's columns; the engine charges it as LP work.
    private val onEdit: (Long) -> Unit = {},
    private val queryWorkAllowance: () -> Long? = { null },
    private val onPreparation: ((LpSolveMetrics) -> Unit)? = null,
    private val continuationAllowance: () -> Long? = { null },
) : SearchComponent,
    SearchBrancher,
    SearchBranchProposal,
    AutoCloseable {
    override val branchKind: SearchBranchKind get() = SearchBranchKind.Arithmetic

    private var owner: LpScopedSolver? = null
    private var proofContext: SearchContext? = null
    private var modelKey: Any? = null

    private var closed = false
    private var invalidated = false
    private var nextWitness = 0L
    private var preparedWork = 0L
    private var preparedRefactors = 0L
    private var solved = false
    private val witnesses = HashMap<Long, SearchAtomPremise>()
    var sourcePremises: LpSourcePremises? = null
        private set
    var lastMetrics = LpSolveMetrics()
        private set
    val state: LpExactState? get() = owner?.state
    val metrics: LpScopedMetrics? get() = owner?.metrics
    val floatTermination get() = owner?.lastFloatTermination
    val floatPaused: Boolean get() = owner?.floatPaused == true

    fun boundPremise(witness: Long): SearchAtomPremise = witnesses[witness] ?: SearchAtomPremise.Unavailable

    fun activeBoundPremise(column: Int, upper: Boolean): SearchAtomPremise? {
        val active = state?.activeSide(column, upper) ?: return null
        val premise = if (active.witness < 0L) SearchAtomPremise.All(emptyList()) else boundPremise(active.witness)
        return active.side.premises?.let { SearchAtomPremise.All(listOf(premise, it.asPremise())) } ?: premise
    }

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
            leaves += if (active.witness < 0L) {
                SearchAtomPremise.All(emptyList())
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

    fun install(key: Any, model: ExactLpModel, token: Cancellation = cancellation): Boolean {
        if (closed || token()) return false
        if (modelKey === key && owner != null) return true
        val context = proofContext
        reset()
        proofContext = context
        val initial = LpExactState(model)
        owner = newOwner(initial)
        modelKey = key
        sourcePremises = LpSourcePremises(key)
        return true
    }

    fun rootBoundPremises(): Map<Pair<Int, Boolean>, SearchAtomPremise>? {
        val current = state ?: return null
        if (current.depth != 0) return null
        val result = HashMap<Pair<Int, Boolean>, SearchAtomPremise>()
        for (column in 0 until current.model.numVars) {
            for (upper in listOf(false, true)) {
                val active = current.activeSide(column, upper) ?: continue
                result[column to upper] = if (active.witness < 0L) {
                    SearchAtomPremise.All(emptyList())
                } else {
                    boundPremise(active.witness)
                }
            }
        }
        return result.toMap()
    }

    private fun newOwner(initial: LpExactState): LpScopedSolver {
        val profile = effort()
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
            componentSplit = profile.componentSplit,
        )
    }

    fun atLevel(depth: Int, token: Cancellation = cancellation): Boolean {
        val current = owner ?: return false
        if (depth < current.state.depth) {
            onEdit(current.state.model.numVars.toLong())
            if (!current.pop(depth, token)) return invalidate()
            retainWitnesses()
        }
        while (current.state.depth < depth) {
            onEdit(current.state.model.numVars.toLong())
            if (!current.push(token)) return invalidate()
        }
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
        if (previous != null && !side.strongerThan(previous, upper)) return true
        val witness = nextWitness++
        onEdit(current.state.model.numVars.toLong())
        if (witness == Long.MAX_VALUE || !current.assertBound(column, upper, side, witness)) {
            return invalidate()
        }
        witnesses[witness] = premise
        lastMetrics = LpSolveMetrics()
        return true
    }

    fun assertBounds(bounds: List<LpPremisedBound>): LpBoundBatchResult = withOwner { current ->
        current.state.conflict?.let { return@withOwner LpBoundBatchResult.Conflict(0, it) }
        if (bounds.isEmpty()) return@withOwner LpBoundBatchResult.Applied(0)
        val lower = MutableIntObjectMap<ExactLpSide>()
        val upper = MutableIntObjectMap<ExactLpSide>()
        val assertions = ArrayList<LpBoundAssertion>()
        val premises = ArrayList<SearchAtomPremise>()
        for (bound in bounds) {
            if (cancellation()) return@withOwner LpBoundBatchResult.Declined(assertions.size)
            if (bound.column !in 0 until current.state.model.numVars) {
                return@withOwner LpBoundBatchResult.Declined(assertions.size + 1)
            }
            val sides = if (bound.upper) upper else lower
            val previous = sides[bound.column] ?: current.state.activeSide(bound.column, bound.upper)?.side
            if (previous != null && !bound.side.strongerThan(previous, bound.upper)) continue
            if (nextWitness > Long.MAX_VALUE - assertions.size - 1L) {
                return@withOwner LpBoundBatchResult.Declined(assertions.size + 1)
            }
            assertions.add(LpBoundAssertion(
                bound.column, bound.upper, bound.side, nextWitness + assertions.size, current.state.depth,
            ))
            premises.add(bound.premise)
            sides.put(bound.column, bound.side)
            if (!ExactLpBounds(
                    lower[bound.column] ?: current.state.activeSide(bound.column, false)?.side,
                    upper[bound.column] ?: current.state.activeSide(bound.column, true)?.side,
                ).consistent
            ) break
        }
        if (assertions.isEmpty()) return@withOwner LpBoundBatchResult.Applied(0)
        // Retain the caller's per-bound allowance debit even when the numerical snapshot is shared.
        onEdit(current.state.model.numVars.toLong() * assertions.size)
        val result = current.assertBounds(assertions)
        if (result !is LpBoundBatchResult.Declined) {
            for (index in 0 until result.count) witnesses[assertions[index].witness] = premises[index]
            nextWitness += result.count
            if (result.count > 0) lastMetrics = LpSolveMetrics()
        }
        result
    } ?: LpBoundBatchResult.Declined(0)

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
                if (previous != null && !side.strongerThan(previous, upperSide)) continue
                if (nextWitness > Long.MAX_VALUE - assertions.size - 1L) {
                    return LpBoundBatchResult.Declined(assertions.size + 1)
                }
                assertions.add(
                    LpBoundAssertion(column, upperSide, side, nextWitness + assertions.size, current.state.depth),
                )
                if (upperSide) activeUpper = side else activeLower = side
                if (!ExactLpBounds(activeLower, activeUpper).consistent) break@columns
            }
        }
        if (assertions.isEmpty()) return LpBoundBatchResult.Applied(0)
        onEdit(current.state.model.numVars.toLong())
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

    fun compact(
        before: LpExactState,
        remap: LpLayoutRemap,
        cuts: LpCutEdit,
        sources: LpSourceCompaction? = null,
    ): Boolean = withOwner { current ->
        if (current.state !== before || cuts.sourceState !== before || !cuts.isCurrent() ||
            cuts.retired.isNotEmpty() || cuts.rows.isNotEmpty() ||
            (sources != null && (sources.sourceState !== before || sources.remap !== remap || !sources.isCurrent())) ||
            cancellation()
        ) {
            return@withOwner false
        }
        onEdit(before.model.numVars.toLong())
        if (!current.compact(remap)) return@withOwner false
        retainWitnesses()
        lastMetrics = LpSolveMetrics()
        sources?.commit()
        cuts.commit()
        true
    } == true

    fun editCuts(edit: LpCutEdit): Boolean = withOwner { current ->
        val before = current.state
        if (edit.sourceState !== before || !edit.isCurrent() || cancellation()) return@withOwner false
        if (edit.retired.isNotEmpty() || edit.rows.isNotEmpty()) {
            onEdit(before.model.numVars.toLong() + edit.rows.size)
            if (!current.replaceRows(edit.retired, emptyList(), edit.rows, before.depth > 0)) return@withOwner false
            retainWitnesses()
            lastMetrics = LpSolveMetrics()
        }
        edit.commit()
        true
    } == true

    fun editSources(
        edit: LpSourceEdit,
        premise: (Int, Boolean) -> SearchAtomPremise = { _, _ -> SearchAtomPremise.Unavailable },
    ): Boolean = withOwner { current ->
        try {
            val before = current.state
            if (edit.sourceState !== before || !edit.isCurrent() ||
                edit.bounds.size != before.model.n + edit.columns.size || cancellation()
            ) {
                return@withOwner false
            }
            val assertions = edit.boundAssertions(nextWitness, cancellation) ?: return@withOwner false
            if (edit.retired.isEmpty() && edit.columns.isEmpty() && edit.rows.isEmpty() && edit.objective == null &&
                assertions.isEmpty()
            ) {
                edit.commit()
                return@withOwner true
            }
            val premises = assertions.map { premise(it.column, it.upper) }
            onEdit((before.model.numVars + edit.columns.size + edit.rows.size).toLong())
            if (!current.replaceRows(
                    edit.retired, edit.columns, edit.rows, before.depth > 0,
                    permanentRows = edit.permanentRows, objective = edit.objective, assertions = assertions,
                )
            ) {
                return@withOwner false
            }
            val count = current.state.assertions.size - before.assertions.size
            for (index in 0 until count) witnesses[assertions[index].witness] = premises[index]
            nextWitness += count
            lastMetrics = LpSolveMetrics()
            edit.commit()
            true
        } finally {
            onPreparation?.invoke(takePreparationMetrics(current))
        }
    } == true

    fun prepare(token: Cancellation, workAllowance: Long): Boolean = withOwner { current ->
        try {
            current.prepare(token, workAllowance)
        } finally {
            lastMetrics = takePreparationMetrics(current)
            onPreparation?.invoke(lastMetrics)
        }
    } == true

    fun partialPrimalGuidance(token: Cancellation): LpPrimalGuidance? =
        withOwner { it.partialPrimalGuidance(token) }

    fun solveFloat(
        warm: Basis? = null,
        token: Cancellation = cancellation,
        continuationWorkAllowance: (() -> Long?)? = null,
        queryAllowance: (() -> Long?)? = null,
    ): Pair<LpSolver, FloatLpResult?>? =
        solveOwned(token.takeIf { onPreparation != null }) { current ->
            val standard = queryWorkAllowance()
            val requested = queryAllowance?.invoke()
            val offered = when {
                requested == null -> standard
                standard == null || standard == 0L -> requested
                else -> minOf(standard, requested)
            }
            val spent = (requested != null && requested <= 0L) || (standard != null && standard < 0L)
            val queryToken = if (spent) token or Cancellation { true } else token
            val continuation = (continuationWorkAllowance ?: continuationAllowance)()
            if (continuation != null && current.state.depth == 0) {
                val profile = effort()
                return@solveOwned current.advanceFloat(
                    (offered ?: profile.work).coerceAtLeast(1L),
                    LpFloatAllowance(continuation.coerceAtLeast(1L), profile.iterations), queryToken,
                ).also { solved = true }
            }
            val allowance = if (solved || offered != null) {
                effort().let { LpFloatAllowance((offered ?: it.work).coerceAtLeast(0L), it.iterations) }
            } else null
            // An explicit query allowance uses the scoped owner's retained basis and factors.
            current.solveFloat(if (allowance != null) null else warm, queryToken, allowance).also { solved = true }
        }

    fun solve(
        token: Cancellation = cancellation,
        sparsePointRecovery: Boolean = false,
        claim: LpClaim = LpClaim.ATTAINED_OPTIMUM,
    ): CertifiedLpResult? =
        solveOwned {
            val profile = effort()
            it.solve(
                token = if (token === cancellation) cancellation else cancellation or token,
                continuationLimits = profile.continuation,
                fullContinuation = profile.fullContinuation,
                observer = certificationObserver,
                sparsePointRecovery = sparsePointRecovery,
                claim = claim,
            ).also { solved = true }
        }

    private inline fun <T> solveOwned(
        prepareToken: Cancellation? = null,
        action: (LpScopedSolver) -> T,
    ): T? = withOwner { current ->
        var queryStarted = false
        try {
            if (prepareToken != null) {
                val ready = try {
                    current.prepare(prepareToken)
                } finally {
                    onPreparation?.invoke(takePreparationMetrics(current))
                }
                if (!ready) return@withOwner null
            }
            queryStarted = true
            action(current)
        } finally {
            lastMetrics = (if (queryStarted) current.lastMetrics else LpSolveMetrics()) +
                takePreparationMetrics(current)
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
        if (withOwner { it.resetRoot(cancellation) } != true) return invalidate()
        witnesses.clear()
        nextWitness = 0L
        sourcePremises = LpSourcePremises(requireNotNull(modelKey))
        return true
    }

    private fun takePreparationMetrics(current: LpScopedSolver): LpSolveMetrics {
        val metrics = current.metrics
        val preparation = LpSolveMetrics(
            workOps = metrics.preparationWork - preparedWork,
            initialRefactorizations = (metrics.preparationRefactorizations - preparedRefactors).toInt(),
        )
        preparedWork = metrics.preparationWork
        preparedRefactors = metrics.preparationRefactorizations
        return preparation
    }

    override fun initialize(context: SearchContext): ComponentResult {
        if (proofContext != null && proofContext !== context) return ComponentResult.Indeterminate
        policy.initialize(context)
        proofContext = context
        return ComponentResult.Consistent
    }

    override fun assert(decision: SearchDecision, context: SearchContext): ComponentResult =
        assertWithin(decision, context, Cancellation.Never)

    internal fun assertWithin(decision: SearchDecision, context: SearchContext, token: Cancellation): ComponentResult {
        if (proofContext != null && proofContext !== context) return ComponentResult.Indeterminate
        proofContext = context
        if (owner != null && policy.eagerAssertionScopes && !atLevel(context.decisionLevel, token)) {
            return ComponentResult.Indeterminate
        }
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
        if (candidate != null && candidate.value.den != BIG_ONE) {
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
        retainWitnesses()
        policy.retract(decisionLevel)
    }

    private fun retainWitnesses() {
        val active = state?.assertions?.map { it.witness }?.toSet().orEmpty()
        witnesses.keys.retainAll(active)
    }

    override fun onRestart(context: SearchContext) = policy.restart(context)

    fun releaseSolver() {
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
        proofContext = null
        sourcePremises = null
        witnesses.clear()
        solved = false
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
