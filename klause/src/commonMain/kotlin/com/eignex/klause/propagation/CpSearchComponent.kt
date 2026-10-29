package com.eignex.klause.propagation

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.SourceIntId
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.result.ConflictAnalysisStatsSink
import com.eignex.klause.solver.search.ComponentCheck
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.RegisteredTheoryDecision
import com.eignex.klause.solver.search.SearchAtomPremise
import com.eignex.klause.solver.search.SearchBrancher
import com.eignex.klause.solver.search.SearchConflictResolution
import com.eignex.klause.solver.search.SearchConflictResolver
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchExactIntValue
import com.eignex.klause.solver.search.SearchExplanation
import com.eignex.klause.solver.search.SearchIntegerBound
import com.eignex.klause.solver.search.SearchLearnedConflict
import com.eignex.klause.solver.search.SearchLearnedConflictResult
import com.eignex.klause.solver.search.SearchModel
import com.eignex.klause.solver.search.SearchModelBlocker
import com.eignex.klause.solver.search.SearchModelKey
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.solver.search.explainAtoms
import com.eignex.klause.util.CheckedLongOverflowException
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.bigIntOf

/**
 * The finite-domain participant in a shared search session.
 *
 * [PropagationSession] owns CP domains and conflict analysis. This adapter aligns its decision levels
 * with the shared trail and supplies CP's specialized learned constraints without exposing domains
 * to theory components.
 */
class CpSearchComponent(
    /** Native finite-domain propagation state. */
    val session: PropagationSession,
    /** Source id for each CP-local integer column; `null` keeps the ordinary identity mapping. */
    sourceIntIds: IntArray? = null,
    /** Typed CP split policy; the shared runner owns applying and retracting its returned decisions. */
    private val branching: CpBranching = CpBranching.Middle,
) : SearchBrancher,
    SearchModelBlocker,
    SearchConflictResolver {
    private val sourceIntIds = sourceIntIds?.copyOf()
    private val cpIntBySource = this.sourceIntIds?.let { ids ->
        IntArray((ids.maxOrNull() ?: -1) + 1) { -1 }.also { map ->
            for (local in ids.indices) map[ids[local]] = local
        }
    }
    private var sharedRootLevel = 0
    private var analysisSharedLevel = 0
    private val nativeLevelBySharedLevel = IntArrayList().apply { add(0) }
    private var lastResult: PropagationResult? = null
    internal val lastConflict: PropagationResult.Unsat? get() = lastResult as? PropagationResult.Unsat
    internal var propagationObserver: ((PropagationResult.Implied) -> Unit)? = null
    internal var cascadeConflictObserver: ((PropagationResult.Unsat) -> Unit)? = null
    private var sharedContext: com.eignex.klause.solver.search.SearchContext? = null
    private var sourceChecks = session.projection.selection?.originalChecks

    internal fun useEnforcingPeers(factorIds: Set<Int>) {
        check(sharedContext == null)
        if (sourceChecks == null || factorIds.isEmpty()) return
        val selection = session.projection.selection ?: return
        sourceChecks = SelectedProjectionChecks(selection, factorIds)
    }

    /** Where conflicts this component cannot learn from are counted; null counts nothing. */
    internal var conflictStats: ConflictAnalysisStatsSink? = null

    override val resolvesAfterModelBlock: Boolean
        get() = session.problem.numIntVars == 0 && session.problem.factors.isEmpty()

    override val prefersNativeConflictAnalysis: Boolean
        get() = session.problem.numIntVars == 0 || when (val learned = lastConflict?.learnedClause) {
            is ConflictAnalyzer.AnalysisResult.LearnedPb -> usableMixedPb(learned)
            is ConflictAnalyzer.AnalysisResult.LearnedIntegerRow -> usableIntegerRow(learned)
            else -> false
        }

    private fun usableIntegerRow(learned: ConflictAnalyzer.AnalysisResult.LearnedIntegerRow): Boolean {
        val usable = checkIntegerRow(learned)
        if (!usable) session.explanationState.arithmeticLearningTrace?.record(
            "integer.decline.shared-assertion-or-premise")
        return usable
    }

    private fun checkIntegerRow(learned: ConflictAnalyzer.AnalysisResult.LearnedIntegerRow): Boolean {
        try {
            val context = sharedContext as? SearchSession ?: return false
            var nativeTarget = sharedRootLevel
            for (level in minOf(nativeLevelBySharedLevel.size - 1, analysisSharedLevel - 1) downTo 0) {
                if (nativeLevelBySharedLevel[level] <= learned.backjumpLevel) {
                    nativeTarget = nativeLevelBySharedLevel[level]
                    break
                }
            }
            val state = session.explanationState
            val trail = state.integerLearningTrailAtLevel?.invoke(nativeTarget) ?: return false
            if (!learned.row.assertsAt(state, trail)) return false
            if (learned.row.guard != Lit.NONE) {
                val guard = learned.row.guard
                val variable = Lit.variable(guard)
                val premise = if (variable < session.problem.numBoolVars) {
                    SearchAtomPremise.Asserted(SearchDecision.Bool(guard))
                } else {
                    val atom = state.atomIdOf(variable)
                    if (!Lit.isPositive(guard) || state.atoms.kind[atom] != AtomKind.EQ) return false
                    val source = sourceIntId(state.atoms.intVar[atom])
                    val value = state.atoms.threshold[atom]
                    if (context.intLowerBound(source) != value || context.intUpperBound(source) != value) return false
                    SearchAtomPremise.All(listOf(context.intLowerBoundPremise(source),
                        context.intUpperBoundPremise(source)))
                }
                if (context.explainAtoms(premise, maxNodes = 128) == null) {
                    return false
                }
            }
            for (variable in learned.row.variables) {
                if (context.cancelled()) return false
                val source = sourceIntId(variable)
                if (context.explainAtoms(context.intLowerBoundPremise(source), maxNodes = 128) == null) return false
                if (context.explainAtoms(context.intUpperBoundPremise(source), maxNodes = 128) == null) return false
            }
            return !context.cancelled()
        } catch (_: CheckedLongOverflowException) {
            return false
        } catch (_: FinitePropagationInterrupted) {
            session.explanationState.runCancelled = true
            return false
        }
    }

    private fun usableMixedPb(learned: ConflictAnalyzer.AnalysisResult.LearnedPb): Boolean {
        val usable = checkMixedPb(learned)
        if (!usable) session.explanationState.arithmeticLearningTrace?.record("pb.decline.shared-assertion-or-premise")
        return usable
    }

    private fun checkMixedPb(learned: ConflictAnalyzer.AnalysisResult.LearnedPb): Boolean {
        val context = sharedContext as? SearchSession ?: return false
        var nativeTarget = sharedRootLevel
        for (level in minOf(nativeLevelBySharedLevel.size - 1, analysisSharedLevel - 1) downTo 0) {
            if (nativeLevelBySharedLevel[level] <= learned.backjumpLevel) {
                nativeTarget = nativeLevelBySharedLevel[level]
                break
            }
        }
        val state = session.explanationState
        var available = 0L
        var maxFree = 0L
        for (i in learned.literals.indices) {
            if (context.cancelled()) return false
            val literal = learned.literals[i]
            val variable = Lit.variable(literal)
            val sharedValue = context.boolValue(variable)
            if (sharedValue != null &&
                !context.supportsNativeBooleanAnalysis(variable, this, session.problem.numBoolVars)
            ) {
                return false
            }
            val freed = !state.boolAssignedAt(variable) || state.boolLevel[variable] > nativeTarget
            if (freed || state.boolValueAt(variable) == Lit.isPositive(literal)) available += learned.weights[i]
            if (freed) maxFree = maxOf(maxFree, learned.weights[i])
        }
        val slack = available - learned.degree
        return slack >= 0 && maxFree > slack
    }

    /** Align shared decision level zero with CP's post-seed root. */
    fun rebase() {
        sharedRootLevel = session.decisionLevel
        nativeLevelBySharedLevel.clear()
        nativeLevelBySharedLevel.add(sharedRootLevel)
    }

    override fun initialize(context: com.eignex.klause.solver.search.SearchContext): ComponentResult =
        session.withCancellation(context.executionControls.cancellation) {
            session.explanationState.finiteWork.begin()
            try {
                initializeFacts(context)
            } catch (_: FinitePropagationInterrupted) {
                session.explanationState.runCancelled = true
                ComponentResult.Indeterminate
            }
        }

    private fun initializeFacts(context: com.eignex.klause.solver.search.SearchContext): ComponentResult {
        sharedContext = context
        if (session.fixpointCancelled) return ComponentResult.Indeterminate
        if (session.isUnsatAtRoot) return ComponentResult.Conflict()
        for (variable in 0 until session.problem.numBoolVars) {
            checkPublication()
            val value = session.boolValue(variable) ?: continue
            val publication = context.publish(Lit.make(variable, value))
            if (publication !is ComponentResult.Consistent) return publication
        }
        for (variable in 0 until session.problem.numIntVars) {
            checkPublication()
            val domain = session.intDomain(variable)
            val lowerPremise = session.sharedBoundPremise(variable, false, context, ::sourceIntId, sharedRootLevel)
            checkCompleteFixpoint()
            val lower = context.publish(
                SearchDecision.IntAtLeast(sourceIntId(variable), domain.min),
                lowerPremise,
            )
            if (lower !is ComponentResult.Consistent) return lower
            checkPublication()
            val upperPremise = session.sharedBoundPremise(variable, true, context, ::sourceIntId, sharedRootLevel)
            checkCompleteFixpoint()
            val upper = context.publish(
                SearchDecision.IntAtMost(sourceIntId(variable), domain.max),
                upperPremise,
            )
            if (upper !is ComponentResult.Consistent) return upper
        }
        return if (session.fixpointCancelled) ComponentResult.Indeterminate else ComponentResult.Consistent
    }

    internal fun prepareInitialization(
        context: com.eignex.klause.solver.search.SearchContext,
    ): Iterator<ComponentResult?> = sequence {
        sharedContext = context
        if (session.fixpointCancelled) {
            yield(ComponentResult.Indeterminate)
            return@sequence
        }
        if (session.isUnsatAtRoot) {
            yield(ComponentResult.Conflict())
            return@sequence
        }
        for (variable in 0 until session.problem.numBoolVars) {
            if ((variable + 1) % PROPAGATION_PREPARATION_BATCH_SIZE == 0) yield(null)
            val value = session.boolValue(variable) ?: continue
            val publication = context.publish(Lit.make(variable, value))
            if (publication !is ComponentResult.Consistent) {
                yield(publication)
                return@sequence
            }
        }
        for (variable in 0 until session.problem.numIntVars) {
            if ((variable + 1) % PROPAGATION_PREPARATION_BATCH_SIZE == 0) yield(null)
            val domain = session.intDomain(variable)
            val lower = context.publish(
                SearchDecision.IntAtLeast(sourceIntId(variable), domain.min),
                session.sharedBoundPremise(variable, false, context, ::sourceIntId, sharedRootLevel),
            )
            if (lower !is ComponentResult.Consistent) {
                yield(lower)
                return@sequence
            }
            val upper = context.publish(
                SearchDecision.IntAtMost(sourceIntId(variable), domain.max),
                session.sharedBoundPremise(variable, true, context, ::sourceIntId, sharedRootLevel),
            )
            if (upper !is ComponentResult.Consistent) {
                yield(upper)
                return@sequence
            }
        }
        yield(if (session.fixpointCancelled) ComponentResult.Indeterminate else ComponentResult.Consistent)
    }.iterator()

    override fun assert(
        decision: SearchDecision,
        context: com.eignex.klause.solver.search.SearchContext,
    ): ComponentResult = session.withCancellation(context.executionControls.cancellation) {
        assertDecision(decision, context)
    }

    private fun assertDecision(
        decision: SearchDecision,
        context: com.eignex.klause.solver.search.SearchContext,
    ): ComponentResult {
        sharedContext = context
        lastResult = null
        analysisSharedLevel = context.decisionLevel
        if (session.fixpointCancelled) return ComponentResult.Indeterminate
        if (decision is SearchDecision.Bool && Lit.variable(decision.literal) >= session.problem.numBoolVars) {
            return if (context.atomLiteral(decision) != null) {
                ComponentResult.Consistent
            } else {
                ComponentResult.Indeterminate
            }
        }
        val result = when (decision) {
            is SearchDecision.Bool -> when (
                val result = session.pinBool(
                    Lit.variable(decision.literal),
                    Lit.isPositive(decision.literal),
                )
            ) {
                is PropagationResult.Implied -> {
                    lastResult = result
                    publish(result, context, Lit.variable(decision.literal))
                }

                is PropagationResult.Unsat -> {
                    lastResult = result
                    conflict(result)
                }
            }

            is SearchDecision.IntAtMost -> cpIntId(decision.variable)?.let {
                if (session.intDomain(it).max <= decision.upper) {
                    ComponentResult.Consistent
                } else {
                    result(session.pinIntAtMost(it, decision.upper), context)
                }
            } ?: ComponentResult.Consistent

            is SearchDecision.IntAtLeast -> cpIntId(decision.variable)?.let {
                if (session.intDomain(it).min >= decision.lower) {
                    ComponentResult.Consistent
                } else {
                    result(session.pinIntAtLeast(it, decision.lower), context)
                }
            } ?: ComponentResult.Consistent

            is SearchDecision.IntEqual -> cpIntId(decision.variable)?.let {
                result(session.pinInt(it, decision.value), context)
            } ?: ComponentResult.Consistent

            is SearchDecision.Theory -> {
                val bound = (decision.decision as? RegisteredTheoryDecision)?.payload as? SearchIntegerBound
                if (bound != null && bound.strict && cpIntId(bound.variable) != null) {
                    val premise = if (bound.upper) {
                        context.intLowerBoundPremise(bound.variable)
                    } else {
                        context.intUpperBoundPremise(bound.variable)
                    }
                    ComponentResult.Conflict(
                        context.explainAtoms(
                            SearchAtomPremise.All(listOf(SearchAtomPremise.Asserted(decision), premise)),
                        ),
                    )
                } else {
                    ComponentResult.Consistent
                }
            }
        }
        recordNativeLevel(context.decisionLevel)
        // A cut fixpoint reports no conflict, so this node stands on factors that never fired: hand it
        // back as undecided and let the traversal retract it, rather than descend toward a leaf those
        // factors would have refuted. A refutation the fixpoint did reach needs no such retreat — an
        // under-propagated state only ever under-tightens, so the conflict holds and keeps its
        // explanation. See [check] for the leaf that a fixpoint cut elsewhere reaches.
        return if (result is ComponentResult.Consistent && session.fixpointCancelled) {
            ComponentResult.Indeterminate
        } else {
            result
        }
    }

    private fun result(
        result: PropagationResult,
        context: com.eignex.klause.solver.search.SearchContext,
    ): ComponentResult = when (result) {
        is PropagationResult.Implied -> {
            lastResult = result
            publish(result, context)
        }

        is PropagationResult.Unsat -> {
            lastResult = result
            conflict(result)
        }
    }

    private fun conflict(result: PropagationResult.Unsat): ComponentResult.Conflict = ComponentResult.Conflict(
        (when (val learned = result.learnedClause) {
            is ConflictAnalyzer.AnalysisResult.Learned -> learned
            is ConflictAnalyzer.AnalysisResult.LearnedPb -> learned.fallbackClause
            is ConflictAnalyzer.AnalysisResult.LearnedIntegerRow -> learned.fallbackClause
            else -> null
        })
            ?.literals
            ?.takeIf { literals -> literals.all { Lit.variable(it) < session.problem.numBoolVars } }
            ?.let(::SearchExplanation),
    )

    /** Publish a CP fixpoint's Boolean facts and integer bounds, each with its CP premise, to [context]. */
    fun publish(
        result: PropagationResult.Implied,
        context: com.eignex.klause.solver.search.SearchContext,
        skippedVariable: Int = -1,
    ): ComponentResult = publish(result, context, skippedVariable) { decision, premise ->
        context.publish(decision, premise)
    }

    /** Import facts already applied by the native CP session at its current shared level. */
    fun import(result: PropagationResult.Implied, shared: SearchSession): ComponentResult =
        publish(result, shared, skippedVariable = -1) { decision, premise ->
            shared.publishFrom(this, decision, premise)
        }

    private fun publish(
        result: PropagationResult.Implied,
        context: com.eignex.klause.solver.search.SearchContext,
        skippedVariable: Int,
        publish: (SearchDecision, SearchAtomPremise) -> ComponentResult,
    ): ComponentResult {
        if (session.fixpointCancelled) return ComponentResult.Indeterminate
        session.explanationState.finiteWork.begin()
        return try {
            publishFixpoint(result, context, skippedVariable, publish)
        } catch (_: FinitePropagationInterrupted) {
            session.explanationState.runCancelled = true
            ComponentResult.Indeterminate
        }
    }

    private fun checkPublication() {
        checkCompleteFixpoint()
        session.explanationState.checkFiniteWork()
    }

    private fun checkCompleteFixpoint() {
        if (session.fixpointCancelled) throw FinitePropagationInterrupted()
    }

    private fun publishFixpoint(
        result: PropagationResult.Implied,
        context: com.eignex.klause.solver.search.SearchContext,
        skippedVariable: Int,
        publish: (SearchDecision, SearchAtomPremise) -> ComponentResult,
    ): ComponentResult {
        propagationObserver?.invoke(result)
        result.forEachBool { variable, value ->
            checkPublication()
            if (variable != skippedVariable) {
                val publication = publish(
                    SearchDecision.Bool(Lit.make(variable, value)), SearchAtomPremise.Unavailable,
                )
                if (publication !is ComponentResult.Consistent) return publication
            }
        }
        if (session.problem.numIntVars == 0) return ComponentResult.Consistent
        // Queued assertions are delivered after this callback, keeping the native point fixed.
        val premises = CpPublicationPremises(session, context, ::sourceIntId, sharedRootLevel)
        result.forEachInt { variable, value ->
            checkPublication()
            val premise = SearchAtomPremise.All(
                listOf(premises.bound(variable, false), premises.bound(variable, true)),
            )
            checkCompleteFixpoint()
            val publication = publish(SearchDecision.IntEqual(sourceIntId(variable), value), premise)
            if (publication !is ComponentResult.Consistent) return publication
        }
        result.forEachIntMin { variable, value ->
            checkPublication()
            val premise = premises.bound(variable, false)
            checkCompleteFixpoint()
            val publication = publish(SearchDecision.IntAtLeast(sourceIntId(variable), value), premise)
            if (publication !is ComponentResult.Consistent) return publication
        }
        result.forEachIntMax { variable, value ->
            checkPublication()
            val premise = premises.bound(variable, true)
            checkCompleteFixpoint()
            val publication = publish(SearchDecision.IntAtMost(sourceIntId(variable), value), premise)
            if (publication !is ComponentResult.Consistent) return publication
        }
        return if (session.fixpointCancelled) ComponentResult.Indeterminate else ComponentResult.Consistent
    }

    override val retainsOwnExplanations: Boolean get() = true

    override fun reasonFor(literal: Int): SearchExplanation? {
        val context = sharedContext ?: return null
        val premise = session.sharedBooleanPremise(Lit.variable(literal), context, ::sourceIntId, sharedRootLevel)
            ?: return null
        return context.explainAtoms(premise, SearchDecision.Bool(literal))
    }

    override fun retract(decisionLevel: Int) {
        val target = if (decisionLevel < nativeLevelBySharedLevel.size) {
            nativeLevelBySharedLevel[decisionLevel]
        } else {
            sharedRootLevel
        }
        val rolledBack = target < session.decisionLevel
        session.popToLevel(target)
        // A failed native pin already restored its completed parent before returning its conflict.
        if (rolledBack || lastResult is PropagationResult.Unsat) session.explanationState.runCancelled = false
        nativeLevelBySharedLevel.truncateTo(decisionLevel + 1)
    }

    /** Owned finite assignment contributed by this component. */
    val assignmentKey = SearchModelKey(Sample::class)

    override fun contributeModel(model: SearchModel, context: com.eignex.klause.solver.search.SearchContext) {
        for (variable in 0 until session.problem.numIntVars) {
            val value = session.intDomain(variable).min
            val source = sourceIntId(variable)
            model.contribute(SearchExactIntValue(SourceIntId(source)), bigIntOf(value))
        }
        model.contribute(
            assignmentKey,
            Sample(
                BooleanArray(session.problem.numBoolVars) { variable -> session.boolValue(variable) ?: false },
                LongArray(session.problem.numIntVars) { variable -> session.intDomain(variable).min },
            ),
        )
    }

    override fun nextBranch(context: com.eignex.klause.solver.search.SearchContext): List<SearchDecision>? =
        branching.alternatives(session, ::sourceIntId)

    /**
     * Refuse a leaf standing on a fixpoint the deadline cut short. A cut fixpoint reports no conflict
     * (see [PropagationSession.fixpointCancelled]), so the factors it never fired have not had their
     * say: the traversal would otherwise read the under-propagated state as a model and publish an
     * assignment that violates them.
     */
    override fun check(context: com.eignex.klause.solver.search.SearchContext): ComponentCheck =
        if (session.fixpointCancelled) ComponentCheck.Indeterminate else session.checkSelectedProjection(sourceChecks)

    override fun blockModel(
        model: com.eignex.klause.solver.search.AssembledSearchModel,
        context: com.eignex.klause.solver.search.SearchContext,
    ): ComponentResult {
        val sample = checkNotNull(model.valueOf(assignmentKey))
        val nogood = session.assignmentNogood(
            sample.bools,
            sample.ints,
        )
        if (nogood.isEmpty()) return ComponentResult.Conflict()
        return when (val result = session.addLearnedClause(Clause(nogood), lbd = nogood.size, permanent = true)) {
            is PropagationResult.Implied -> publish(result, context)
            is PropagationResult.Unsat -> ComponentResult.Conflict()
        }
    }

    override fun resolveConflict(context: com.eignex.klause.solver.search.SearchContext): SearchConflictResolution {
        val unsat = lastResult as? PropagationResult.Unsat ?: return SearchConflictResolution.Chronological
        var learned = unsat.learnedClause as? ConflictAnalyzer.AnalysisResult.LearnedConstraint
        if (session.problem.numIntVars > 0 &&
            learned is ConflictAnalyzer.AnalysisResult.LearnedPb && !usableMixedPb(learned)
        ) {
            learned = learned.fallbackClause
        }
        if (learned is ConflictAnalyzer.AnalysisResult.LearnedIntegerRow && !usableIntegerRow(learned)) {
            learned = learned.fallbackClause
        }
        if (learned == null) {
            conflictStats?.observeNotApplicable()
            return SearchConflictResolution.Chronological
        }
        if (!learned.asserting) {
            conflictStats?.observeNonAsserting()
            return SearchConflictResolution.Chronological
        }
        if (learned.isContradiction && learned.backjumpLevel == 0) {
            return SearchConflictResolution.Exhausted
        }
        return SearchConflictResolution.Backjump(CpLearnedConflict(learned))
    }

    /** Shared level corresponding to the latest native level not above [nativeLevel]. */
    fun sharedLevelForNative(nativeLevel: Int): Int {
        // A rejected native pin has already undone its own level; its shared scope still awaits retraction.
        val latest = if (lastResult is PropagationResult.Unsat) {
            minOf(nativeLevelBySharedLevel.size - 1, analysisSharedLevel - 1)
        } else {
            nativeLevelBySharedLevel.size - 1
        }
        for (level in latest downTo 0) {
            if (nativeLevelBySharedLevel[level] <= nativeLevel) return level
        }
        return 0
    }

    private inner class CpLearnedConflict(private val learned: ConflictAnalyzer.AnalysisResult.LearnedConstraint) :
        SearchLearnedConflict {
        private val factorFamilies = session.explanationState.factorAttribution?.analysisFamilies ?: 0
        override val decisionLevel: Int get() = sharedLevelForNative(learned.backjumpLevel)
        override val lbd: Int get() = learned.lbd
        override val guardLiterals: IntArray get() = learned.guardLiterals
        override val decisionLevels: IntArray get() = learned.decisionLevels

        override fun apply(session: SearchSession): SearchLearnedConflictResult {
            this@CpSearchComponent.session.explanationState.arithmeticLearningTrace?.record(
                "analysis.mapped-target", learned.backjumpLevel.toLong(), decisionLevel.toLong(),
                this@CpSearchComponent.session.decisionLevel.toLong())
            val result = this@CpSearchComponent.session.withAttribution(factorFamilies) {
                when (learned) {
                    is ConflictAnalyzer.AnalysisResult.Learned -> this@CpSearchComponent.session.addLearnedClause(
                        Clause(learned.literals),
                        learned.lbd,
                    )

                    is ConflictAnalyzer.AnalysisResult.LearnedPb -> this@CpSearchComponent.session.addLearnedPb(
                        learned.weights,
                        learned.literals,
                        learned.degree,
                        learned.lbd,
                    )

                    is ConflictAnalyzer.AnalysisResult.LearnedIntegerRow ->
                        this@CpSearchComponent.session.addLearnedIntegerRow(learned.row, learned.lbd)
                }
            }
            lastResult = result
            return when (result) {
                is PropagationResult.Implied -> {
                    // The clause stays in this component's own database. Copying it into the shared one
                    // would give the same clause two watch indexes and two reduction policies, and the
                    // shared analyzer reaches this component's reasoning through [reasonFor] instead.
                    when (import(result, session)) {
                        ComponentResult.Consistent -> when (session.propagate()) {
                            ComponentResult.Consistent -> SearchLearnedConflictResult.Resume
                            is ComponentResult.Conflict -> SearchLearnedConflictResult.Chronological
                            ComponentResult.Indeterminate -> SearchLearnedConflictResult.Indeterminate
                        }

                        is ComponentResult.Conflict -> SearchLearnedConflictResult.Chronological

                        ComponentResult.Indeterminate -> SearchLearnedConflictResult.Indeterminate
                    }
                }

                is PropagationResult.Unsat -> {
                    cascadeConflictObserver?.invoke(result)
                    val next = result.learnedClause as? ConflictAnalyzer.AnalysisResult.LearnedConstraint
                    when {
                        next == null -> SearchLearnedConflictResult.Chronological
                        next.backjumpLevel == 0 && next.isContradiction -> SearchLearnedConflictResult.Exhausted
                        else -> SearchLearnedConflictResult.Backjump(CpLearnedConflict(next))
                    }
                }
            }
        }
    }

    private fun cpIntId(sourceIntId: Int): Int? {
        val map = cpIntBySource ?: return sourceIntId
        return map.getOrNull(sourceIntId)?.takeIf { it >= 0 }
    }

    private fun sourceIntId(cpIntId: Int): Int = sourceIntIds?.get(cpIntId) ?: cpIntId

    private fun recordNativeLevel(sharedLevel: Int) {
        while (nativeLevelBySharedLevel.size <= sharedLevel) nativeLevelBySharedLevel.add(session.decisionLevel)
        nativeLevelBySharedLevel[sharedLevel] = session.decisionLevel
    }
}

/** Supplies finite-domain splits without owning a search trail. */
fun interface CpBranching {
    /** Exhaustive alternatives for the current CP state, or null once all CP columns are fixed. */
    fun alternatives(session: PropagationSession, sourceIntId: (Int) -> Int): List<SearchDecision>?

    /** First unfixed column, split at its inclusive midpoint. */
    data object Middle : CpBranching {
        override fun alternatives(session: PropagationSession, sourceIntId: (Int) -> Int): List<SearchDecision>? {
            for (variable in 0 until session.problem.numIntVars) {
                val domain = session.intDomain(variable)
                if (domain.min == domain.max) continue
                // Unsigned halving preserves the inclusive midpoint even for the full signed Long range.
                val middle = domain.min + ((domain.max - domain.min) ushr 1)
                return listOf(
                    SearchDecision.IntAtMost(sourceIntId(variable), middle),
                    SearchDecision.IntAtLeast(sourceIntId(variable), middle + 1),
                )
            }
            return null
        }
    }

    /** Leave residual selection to another shared [SearchBrancher]. */
    data object None : CpBranching {
        override fun alternatives(session: PropagationSession, sourceIntId: (Int) -> Int): List<SearchDecision>? = null
    }
}
