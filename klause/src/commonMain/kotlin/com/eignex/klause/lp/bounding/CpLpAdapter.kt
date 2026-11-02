package com.eignex.klause.lp.bounding

import com.eignex.klause.lp.engine.Cut
import com.eignex.klause.lp.engine.CutSourceKind
import com.eignex.klause.lp.engine.ExactLpNumber
import com.eignex.klause.lp.engine.ExactLpSide
import com.eignex.klause.lp.engine.LpBoundBatchResult
import com.eignex.klause.lp.engine.LpLayoutRemap
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.strongerThan
import com.eignex.klause.lp.relaxation.CpToLpRelaxation
import com.eignex.klause.lp.relaxation.CutSourceMap
import com.eignex.klause.lp.relaxation.LpRelaxation
import com.eignex.klause.lp.relaxation.LpRetainedCuts
import com.eignex.klause.lp.relaxation.LpRetainedSources
import com.eignex.klause.lp.relaxation.LpSourceEdit
import com.eignex.klause.lp.relaxation.RootDomains
import com.eignex.klause.lp.relaxation.SessionDomains
import com.eignex.klause.lp.relaxation.authoritativeWithTemplates
import com.eignex.klause.lp.relaxation.columnBounds
import com.eignex.klause.lp.relaxation.withCpBounds
import com.eignex.klause.lp.relaxation.withModel
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.result.LpPhaseStats
import com.eignex.klause.solver.search.ComponentCheck
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchAtomPremise
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.util.Cancellation

internal class CpLpAdapter(private val engine: LpEngine) : LpSearchPolicy {
    // CP decisions reach the LP in a bound batch; its scopes are synchronized when that batch is assembled.
    override val eagerAssertionScopes: Boolean get() = false
    private var native: PropagationSession? = null
    private var sharedNative: PropagationSession? = null
    private var shared: SearchContext? = null
    private var feasibility = false
    private var persistentState = false
    private var sourceRoot = false
    private var sourceRelaxer: CpToLpRelaxation? = null
    private var sources: LpRetainedSources? = null
    private fun newCuts() = LpRetainedCuts { family, outcome ->
        engine.sink.lp.cutLifecycle.event(family, "ROW_LIFECYCLE", outcome)
    }
    private var cuts = newCuts()
    var branching: ((SearchContext) -> List<SearchDecision>?)? = null
    var fractional: ((SearchContext) -> LpFractionalBranch?)? = null
    var currentModel: LpModel? = null
        private set

    fun attach(session: PropagationSession, feasibility: Boolean) {
        sharedNative = session
        this.feasibility = feasibility
    }

    override fun initialize(context: SearchContext) {
        if (native === sharedNative && (shared == null || shared === context)) resetRoot() else reset()
        shared = context
    }

    override fun propagate(context: SearchContext): ComponentResult {
        val session = sharedNative ?: return ComponentResult.Consistent
        if (!feasibility) return ComponentResult.Consistent
        val prune = engine.pruneNode(session, Double.POSITIVE_INFINITY, -1, true)
        return if (prune) ComponentResult.Conflict() else ComponentResult.Consistent
    }

    override fun check(context: SearchContext): ComponentCheck = ComponentCheck.Feasible

    override fun nextBranch(context: SearchContext): List<SearchDecision>? = branching?.invoke(context)
    override fun fractionalBranch(context: SearchContext): LpFractionalBranch? = fractional?.invoke(context)

    override fun retract(decisionLevel: Int) {
        currentModel = null
        sources?.let { it.retract(minOf(it.depth, decisionLevel)) }
        cuts.retract(minOf(cuts.depth, decisionLevel))
        if (!persistentState) engine.propagator.reset()
    }

    // The source-root probe asserts no search restriction; live adoption checks every changed bound.
    fun retainRootProbe(probe: PropagationSession?): Boolean {
        val target = sharedNative ?: return false
        val sameSource = if (probe == null) sourceRoot && native == null
            else native === probe && probe.problem === engine.problem && probe.decisionLevel == 0
        if (!engine.retainedRootEnabled || !sameSource || !persistentState ||
            target.problem !== engine.problem || target.decisionLevel != 0 || shared?.decisionLevel != 0 ||
            engine.propagator.state?.depth != 0
        ) return false
        native = target
        sourceRoot = false
        return true
    }

    fun localModel() {
        persistentState = false
        sourceRoot = false
        currentModel = null
        sourceRelaxer = null
        sources = null
        cuts = newCuts()
    }

    fun resetRoot() {
        currentModel = null
        if (!persistentState || !engine.propagator.resetRoot()) {
            reset()
        } else {
            sources?.retract(0)
            cuts.retract(0)
        }
    }

    fun reset() {
        native = null
        persistentState = false
        sourceRoot = false
        sourceRelaxer = null
        sources = null
        cuts = newCuts()
        currentModel = null
        engine.propagator.reset()
    }

    fun isSourceRoot(base: LpRelaxation): Boolean {
        val state = engine.propagator.state ?: return false
        return sourceRoot && native == null && currentModel === base.model && state.depth == 0 &&
            base.model.exactState === state
    }

    fun sourceRootRelaxation(base: LpRelaxation, cancellation: Cancellation): LpRelaxation? {
        reset()
        val exact = base.authoritativeWithTemplates(cancellation, onWork = engine::noteNodeOverhead) {
            engine.sink.lp.observeTemplateUse(exactBounds = it)
        } ?: return null
        if (!engine.propagator.install(base, exact, cancellation) || cancellation()) return null
        val model = requireNotNull(engine.propagator.state).ownerWorkingModel() ?: return null
        val rebound = base.withModel(model)
        currentModel = model
        persistentState = true
        sourceRoot = true
        return rebound
    }

    fun sourceRootRelaxation(relaxer: CpToLpRelaxation, cancellation: Cancellation): LpRelaxation? {
        reset()
        sourceRelaxer = relaxer
        val retained = LpRetainedSources(engine.problem, relaxer).also { sources = it }
        val core = engine.propagator
        if (!core.install(retained, LpRetainedSources.emptyModel())) return null
        val domains = RootDomains(engine.problem)
        val edit = retained.prepare(requireNotNull(core.state), domains, cancellation) {
            engine.noteNodeOverhead(it * LpNodeOverhead.BUILD)
        } ?: return null
        if (cancellation() || !core.editSources(edit) { _, _ -> SearchAtomPremise.Unavailable } || cancellation()) {
            return null
        }
        val rebound = retained.relaxation(requireNotNull(core.state), domains)
        currentModel = rebound.model
        persistentState = true
        sourceRoot = true
        return rebound
    }

    fun sourceRootCuts(base: LpRelaxation, selected: List<Cut>, cancellation: Cancellation): LpRelaxation? {
        if (!sourceRoot || native != null || currentModel !== base.model) return null
        return applyCuts(base, base.sourceMap ?: return null, selected, null, cancellation)
    }

    fun relaxation(relaxer: CpToLpRelaxation, session: PropagationSession): LpRelaxation? {
        currentModel = null
        val core = engine.propagator
        if (native !== session || sourceRelaxer !== relaxer || core.state == null) {
            reset()
            native = session
            sourceRelaxer = relaxer
            sources = LpRetainedSources(session.problem, relaxer)
            if (!core.install(requireNotNull(sources), LpRetainedSources.emptyModel())) return null
        }
        val retained = requireNotNull(sources)
        val domains = SessionDomains(session)
        val depth = if (session === sharedNative) {
            shared?.decisionLevel ?: session.decisionLevel
        } else {
            session.decisionLevel
        }
        if (!core.atLevel(depth)) return null
        if (retained.depth > depth) retained.retract(depth)
        var edit = retained.prepare(requireNotNull(core.state), domains, engine.params.cancellation) {
            engine.noteNodeOverhead(it * LpNodeOverhead.BUILD)
        } ?: return null
        weakenedBoundDepth(edit)?.let { assertionDepth ->
            if (assertionDepth < 0) return null
            val target = maxOf(0, assertionDepth - 1)
            if (assertionDepth == 0) {
                if (!core.resetRoot()) return null
            } else if (!core.atLevel(target)) {
                return null
            }
            retained.retract(minOf(retained.depth, target))
            cuts.retract(minOf(cuts.depth, target))
            if (!core.atLevel(depth)) return null
            edit = retained.prepare(requireNotNull(core.state), domains, engine.params.cancellation) {
                engine.noteNodeOverhead(it * LpNodeOverhead.BUILD)
            } ?: return null
        }
        if (!core.editSources(edit) { column, upper -> boundPremise(edit, column, upper, session) }) return null
        val rebound = retained.relaxation(requireNotNull(core.state), domains)
        currentModel = rebound.model
        persistentState = true
        return rebound
    }

    private fun weakenedBoundDepth(edit: LpSourceEdit): Int? {
        val current = engine.propagator.state ?: return null
        var target: Int? = null
        for (column in 0 until current.model.n) {
            for (upper in listOf(false, true)) {
                val active = current.activeSide(column, upper) ?: continue
                val requested = if (upper) edit.bounds[column].upper else edit.bounds[column].lower
                if (requested != null && !active.side.strongerThan(requested, upper)) continue
                if (active.witness < 0L) return -1
                target = minOf(target ?: active.depth, active.depth)
            }
        }
        return target
    }

    private fun boundPremise(
        edit: LpSourceEdit,
        column: Int,
        upper: Boolean,
        session: PropagationSession,
    ): SearchAtomPremise {
        val context = shared?.takeIf { session === sharedNative } ?: return SearchAtomPremise.Unavailable
        val source = edit.source(column) ?: return SearchAtomPremise.Unavailable
        return when (source.kind) {
            CutSourceKind.INTEGER -> {
                val native = session.intDomain(source.id)
                if (upper && context.intUpperBound(source.id)?.let { it <= native.max } == true) {
                    context.intUpperBoundPremise(source.id)
                } else if (!upper && context.intLowerBound(source.id)?.let { it >= native.min } == true) {
                    context.intLowerBoundPremise(source.id)
                } else {
                    SearchAtomPremise.Unavailable
                }
            }
            CutSourceKind.BOOLEAN -> session.boolValue(source.id)?.let { value ->
                if (context.boolValue(source.id) != value) SearchAtomPremise.Unavailable else {
                    SearchAtomPremise.Asserted(SearchDecision.Bool((source.id shl 1) or if (value) 0 else 1))
                }
            } ?: SearchAtomPremise.Unavailable
            else -> SearchAtomPremise.Unavailable
        }
    }

    fun relaxation(base: LpRelaxation, session: PropagationSession): LpRelaxation? {
        currentModel = null
        if (base.model.hasContinuous || base.model.doubleView != null || base.model.exactState != null) return null
        if (native !== session) {
            engine.propagator.reset()
            cuts = newCuts()
            native = session
            sourceRoot = false
        }
        val core = engine.propagator
        if (!persistentState) core.reset()
        if (core.state == null && !core.install(base, base.authoritativeWithTemplates(engine.params.cancellation,
            onWork = engine::noteNodeOverhead) {
                engine.sink.lp.observeTemplateUse(exactBounds = it)
            } ?: return null)
        ) return null
        val depth = if (session ===
            sharedNative
        ) {
            shared?.decisionLevel ?: session.decisionLevel
        } else {
            session.decisionLevel
        }
        if (!core.atLevel(depth)) return null
        val (lower, upper) = base.columnBounds(session)
        val current = requireNotNull(core.state).model
        val weakens = lower.indices.any { column ->
            val bounds = current.column(column).bounds
            val origin = current.column(column).origin.value
            val lo = BigFraction.ofLong(lower[column]) - origin
            val hi = BigFraction.ofLong(upper[column]) - origin
            bounds.lower?.let { lo < it.number.value } == true || bounds.upper?.let { hi > it.number.value } == true
        }
        // Standalone callers may replace a root or sibling without delivering shared retract events.
        if (weakens) {
            if (!core.resetRoot()) {
                cuts = newCuts()
                if (!core.install(base, base.authoritativeWithTemplates(engine.params.cancellation,
                    onWork = engine::noteNodeOverhead) {
                        engine.sink.lp.observeTemplateUse(exactBounds = it)
                    } ?: return null)
                ) return null
            } else {
                cuts.retract(0)
            }
            if (!core.atLevel(depth)) return null
        }
        val initial = requireNotNull(core.state).model
        val result = core.assertBounds(
            lower.indices.map { column ->
                ExactLpSide(ExactLpNumber.of(BigFraction.ofLong(lower[column]) - initial.column(column).origin.value))
            },
            upper.indices.map { column ->
                ExactLpSide(ExactLpNumber.of(BigFraction.ofLong(upper[column]) - initial.column(column).origin.value))
            },
        )
        if (result !is LpBoundBatchResult.Applied) return null
        val state = requireNotNull(core.state)
        val proof = state.ownerWorkingModel() ?: return null
        val sources = base.sourceMap?.withCpBounds(proof, session)
        val rebound = base.withModel(proof, sources)
        currentModel = proof
        persistentState = true
        return rebound
    }

    fun cutRelaxation(
        base: LpRelaxation,
        session: PropagationSession,
        selected: List<Cut>? = null,
    ): LpRelaxation? {
        if (currentModel !== base.model || native !== session) return null
        val map = base.sourceMap?.withCpBounds(base.model, session) ?: return null
        return applyCuts(base, map, selected, session, engine.params.cancellation)
    }

    private fun applyCuts(
        base: LpRelaxation,
        map: CutSourceMap,
        selected: List<Cut>?,
        session: PropagationSession?,
        cancellation: Cancellation,
    ): LpRelaxation? {
        val core = engine.propagator
        val before = core.state ?: return null
        if (cuts.depth > before.depth) cuts.retract(before.depth)
        val edit = cuts.prepare(before, base.withModel(base.model, map), selected, cancellation)
            ?: return null
        engine.noteNodeOverhead(edit.emittedExtent * LpNodeOverhead.BUILD)
        val beforeMetrics = core.metrics
        val applied = try {
            core.editCuts(edit)
        } finally {
            core.metrics?.let { after ->
                if (beforeMetrics != null) engine.sink.lp.cutLifecycle.shared("OWNER_EDIT", LpPhaseStats(calls = 1L,
                    work = after.preparationWork - beforeMetrics.preparationWork,
                    outcomes = mapOf("CREATED" to after.createdOwners - beforeMetrics.createdOwners,
                        "CLOSED" to after.closedOwners - beforeMetrics.closedOwners,
                        "DECLINED" to after.editDeclines - beforeMetrics.editDeclines,
                        "RETIRED_ROWS" to edit.retired.size.toLong(), "ADDED_ROWS" to edit.rows.size.toLong())))
            }
        }
        if (!applied || cancellation()) return null
        val state = requireNotNull(core.state)
        val model = if (state === before) base.model else state.ownerWorkingModel() ?: return null
        val parents = cuts.parentRows(state)
        val rebound = base.withModel(model, map.withParentRows(parents))
        val compacted = if (session == null) rebound else compactRelaxation(rebound, session)
        currentModel = compacted.model
        return compacted
    }

    private fun compactRelaxation(base: LpRelaxation, session: PropagationSession): LpRelaxation {
        val core = engine.propagator
        val before = requireNotNull(core.state)
        val source = sources
        val edit = source?.prepareCompaction(before, engine.params.cancellation, cuts.storageUnits)
        val remap = edit?.remap ?: if (source == null && before.rows.retiredCount > 0 &&
            before.rows.storageWeight(before.model.layoutStorage)
                .warrantsCompaction(cuts.storageUnits + before.trailStorageUnits)
        ) {
            LpLayoutRemap(before.model.n, before.rows)
        } else {
            return base
        }
        val cutEdit = cuts.prepareCompaction(before, remap, engine.params.cancellation) ?: return base
        engine.noteNodeOverhead((edit?.extent ?: (before.model.layoutStorage.total +
            cuts.storageUnits + before.trailStorageUnits)) * LpNodeOverhead.BUILD)
        if (!core.compact(before, remap, cutEdit, edit)) return base
        val state = requireNotNull(core.state)
        val model = requireNotNull(state.ownerWorkingModel())
        val rebound = source?.relaxation(state, SessionDomains(session)) ?: base.withModel(model, remap = remap)
        val map = rebound.sourceMap?.withCpBounds(model, session)?.withParentRows(cuts.parentRows(state))
        if (map != null) engine.cutPool.remap(map)
        return rebound.withModel(model, map)
    }
}
