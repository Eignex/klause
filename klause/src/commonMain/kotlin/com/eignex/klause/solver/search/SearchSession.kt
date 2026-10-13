package com.eignex.klause.solver.search

import com.eignex.klause.solver.result.OpenTheoryClauseStats
import com.eignex.klause.util.Cancellation

/**
 * Shared trailed coordination for finite-domain and theory components.
 *
 * This class deliberately has no dependency on [com.eignex.klause.propagation.PropagationSession].
 * The finite CP path participates through a component adapter; theories keep their own state and never
 * observe finite domains. Components that retain residual state provide their splits through
 * [SearchBrancher], so the session also owns generic finite and hybrid tree traversal.
 */
class SearchSession(
    private val components: List<SearchComponent>,
    private val maxChecks: Long = Long.MAX_VALUE,
    cancellation: Cancellation = Cancellation.Never,
    learnedDb: SearchLearnedDbParams = SearchLearnedDbParams(),
    private val branchers: List<SearchBrancher> = emptyList(),
    private val atoms: SearchAtomRegistry? = null,
    executionControls: SearchExecutionControls = SearchExecutionControlAdapter(cancellation),
    proposals: List<SearchBranchProposal>,
) : SearchContext,
    ClauseWatchHost {
    /** Open a session with cancellation-only controls and the existing native traversal defaults. */
    constructor(
        components: List<SearchComponent>,
        maxChecks: Long = Long.MAX_VALUE,
        cancellation: Cancellation = Cancellation.Never,
        learnedDb: SearchLearnedDbParams = SearchLearnedDbParams(),
        branchers: List<SearchBrancher> = emptyList(),
        atoms: SearchAtomRegistry? = null,
    ) : this(
        components,
        maxChecks,
        cancellation,
        learnedDb,
        branchers,
        atoms,
        SearchExecutionControlAdapter(cancellation),
    )

    /** Open a session with explicit execution controls and the existing proposal order. */
    constructor(
        components: List<SearchComponent>,
        maxChecks: Long = Long.MAX_VALUE,
        cancellation: Cancellation = Cancellation.Never,
        learnedDb: SearchLearnedDbParams = SearchLearnedDbParams(),
        branchers: List<SearchBrancher> = emptyList(),
        atoms: SearchAtomRegistry? = null,
        executionControls: SearchExecutionControls,
    ) : this(components, maxChecks, cancellation, learnedDb, branchers, atoms, executionControls, emptyList())

    private val proposals = proposals.toList()

    init {
        atoms?.attach()
    }

    private val singleComponent = components.singleOrNull()
    private val trail = SearchTrail()
    private val learning = SearchLearning(learnedDb)
    private var selfPublicationsPending = false
    private var activeComponent: SearchComponent? = null
    private var conflictResolver: SearchConflictResolver? = null
    private var conflictOwner: SearchComponent? = null
    private var lastPushCreatedLevel = false
    private var unassignListener: SearchUnassignListener? = null
    private var checks = 0L
    private var checksExhausted = false
    private var decisionsExhausted = false
    override var executionControls: SearchExecutionControls = executionControls
        private set

    /** Current shared decision level. */
    override val decisionLevel: Int get() = trail.decisionLevel

    override fun boolValue(variable: Int): Boolean? = trail.boolValue(variable)

    override fun registerAtom(positive: SearchTheoryDecision, negative: SearchTheoryDecision): SearchTheoryAtom? =
        if (cancelled()) null else atoms?.register(positive, negative)

    override fun atomLiteral(decision: SearchDecision): Int? = if (atoms == null) {
        (decision as? SearchDecision.Bool)?.literal
    } else {
        atoms.literal(decision)
    }

    private fun acceptsLiteral(literal: Int): Boolean = atoms?.accepts(literal) ?: true

    private fun namedExplanation(explanation: SearchExplanation?): SearchExplanation? =
        if (atoms == null) explanation else explanation?.takeIf { it.literals.all(::acceptsLiteral) }

    override fun intLowerBound(variable: Int): Long? = trail.lower(variable)

    override fun intUpperBound(variable: Int): Long? = trail.upper(variable)

    override fun intLowerBoundPremise(variable: Int): SearchAtomPremise = trail.lowerPremise(variable)

    override fun intUpperBoundPremise(variable: Int): SearchAtomPremise = trail.upperPremise(variable)

    /** Clause-form reason for the current assignment of [variable], or null when it is a decision. */
    fun reasonFor(variable: Int): SearchExplanation? = trail.reasonFor(variable)

    override fun imply(literal: Int, explanation: SearchExplanation): ComponentResult =
        assignImplied(literal, explanation)

    private fun implyFrom(source: SearchComponent?, literal: Int, explanation: SearchExplanation): ComponentResult {
        val previous = activeComponent
        activeComponent = source
        val result = assignImplied(literal, explanation)
        activeComponent = previous
        return result
    }

    override fun publish(literal: Int): ComponentResult = publish(SearchDecision.Bool(literal))

    override fun publish(decision: SearchDecision): ComponentResult = when (decision) {
        is SearchDecision.Bool -> assignImplied(decision.literal, null)

        is SearchDecision.IntAtMost, is SearchDecision.IntAtLeast, is SearchDecision.IntEqual -> publishIntFact(
            decision,
        )

        is SearchDecision.Theory -> {
            if (decision.decision is RegisteredTheoryDecision) {
                val literal = atomLiteral(decision)
                if (literal == null) {
                    ComponentResult.Indeterminate
                } else {
                    assignImplied(
                        literal,
                        null,
                        publishedTheory = true,
                    )
                }
            } else {
                trail.pendingAssertions.addLast(PendingSearchAssertion(decision, activeComponent))
                ComponentResult.Consistent
            }
        }
    }

    override fun publish(decision: SearchDecision, premise: SearchAtomPremise): ComponentResult = when (decision) {
        is SearchDecision.IntAtMost, is SearchDecision.IntAtLeast, is SearchDecision.IntEqual ->
            publishIntFact(decision, premise)

        else -> publish(decision)
    }

    /**
     * Import a consequence that [source] has already applied to its native state.
     *
     * Native components use this outside their normal callback (for example after asserting a learned
     * clause at an existing level). The source is excluded from the pending delivery, preventing a
     * second native pin from creating an extra private decision level.
     */
    internal fun publishFrom(
        source: SearchComponent,
        decision: SearchDecision,
        premise: SearchAtomPremise = SearchAtomPremise.Unavailable,
    ): ComponentResult {
        val previous = activeComponent
        activeComponent = source
        val result = publish(decision, premise)
        activeComponent = previous
        return result
    }

    private fun assignImplied(
        literal: Int,
        explanation: SearchExplanation?,
        publishedTheory: Boolean = false,
    ): ComponentResult {
        if (!acceptsLiteral(literal)) return ComponentResult.Indeterminate
        val reason = namedExplanation(explanation)
        val variable = literal ushr 1
        val value = if (literal and 1 == 0) TRUE else FALSE
        return when (trail.rawBoolValue(variable)) {
            value -> ComponentResult.Consistent

            UNASSIGNED -> {
                assignBool(variable, value, if (publishedTheory) activeComponent else null)
                if (reason != null) {
                    trail.retainReason(variable, reason)
                } else {
                    activeComponent?.let { trail.retainPublisher(variable, it) }
                }
                if (!publishedTheory && singleComponent != null && singleComponent === activeComponent) {
                    // The sole component already applied its consequence. Keep the fixpoint signal
                    // without buffering a delivery that would only be skipped as a self-publication.
                    selfPublicationsPending = true
                } else {
                    trail.pendingAssertions.addLast(
                        PendingSearchAssertion(
                            SearchDecision.Bool(literal),
                            if (publishedTheory) null else activeComponent,
                        ),
                    )
                }
                ComponentResult.Consistent
            }

            else -> ComponentResult.Conflict(reason)
        }
    }

    override fun consumeCheck(): Boolean {
        if (checks++ >= maxChecks) {
            checksExhausted = true
            executionControls.reportStop(SearchStopCause.CheckAllowance)
            return false
        }
        return executionControls.consumeCheck()
    }

    internal fun workExhausted(): Boolean = executionControls.exhausted
    internal fun checkBudgetExhausted(): Boolean = checksExhausted
    internal fun decisionBudgetExhausted(): Boolean = decisionsExhausted

    internal fun markDecisionBudgetExhausted() {
        decisionsExhausted = true
        executionControls.reportStop(SearchStopCause.DecisionAllowance)
    }

    private var controlsAttached = false
    private var initializationStarted = false

    internal fun attachExecutionControls(controls: SearchExecutionControls) {
        check(!controlsAttached) { "execution controls are attached once per search session" }
        check(!initializationStarted && checks == 0L && decisionLevel == 0) {
            "execution controls must be bound before search"
        }
        executionControls = controls
        controlsAttached = true
    }

    override fun cancelled(): Boolean = executionControls.cancellation()

    private var cancellationPollRequested = false

    override fun requestCancellationPoll() {
        cancellationPollRequested = true
    }

    internal fun consumeCancellationPollRequest(): Boolean = cancellationPollRequested.also {
        cancellationPollRequested = false
    }

    /** Initialize all components at the shared root. */
    fun initialize(): ComponentResult {
        initializationStarted = true
        val result = runComponents { it.initialize(this) }
        return if (result is ComponentResult.Consistent) propagate() else result
    }

    internal fun prepareInitialization(
        initialize: (SearchComponent, SearchContext) -> Iterator<ComponentResult?>,
    ): Iterator<ComponentResult?> = sequence {
        initializationStarted = true
        for (component in components) {
            val pending = initialize(component, this@SearchSession)
            var result: ComponentResult? = null
            while (result == null) {
                activeComponent = component
                try {
                    result = pending.next()
                } finally {
                    activeComponent = null
                }
                if (result == null) yield(null)
            }
            if (result !is ComponentResult.Consistent) {
                yield(recordConflict(component, result))
                return@sequence
            }
        }
        yield(propagate())
    }.iterator()

    /** Assert [decision], then run a shared propagation round. */
    fun push(decision: SearchDecision): ComponentResult {
        lastPushCreatedLevel = false
        return recordAndDispatch(decision, source = null)
    }

    internal fun lastPushCreatedLevel(): Boolean = lastPushCreatedLevel

    private fun recordAndDispatch(decision: SearchDecision, source: SearchComponent?): ComponentResult {
        conflictResolver = null
        conflictOwner = null
        if (atoms != null) {
            val bound = SearchIntegerBound.of(decision)
            if (bound != null) {
                val atom = registerAtom(bound, bound.complement()) ?: return ComponentResult.Indeterminate
                return recordAndDispatch(SearchDecision.Theory(atom.positive), source)
            }
        }
        if (decision is SearchDecision.Theory && decision.decision is RegisteredTheoryDecision) {
            val literal = atomLiteral(decision) ?: return ComponentResult.Indeterminate
            return recordAndDispatch(SearchDecision.Bool(literal), source)
        }
        if (decision is SearchDecision.Bool) {
            if (!acceptsLiteral(decision.literal)) return ComponentResult.Indeterminate
            val variable = decision.literal ushr 1
            val value = if (decision.literal and 1 == 0) TRUE else FALSE
            when (trail.rawBoolValue(variable)) {
                value -> return ComponentResult.Consistent
                UNASSIGNED -> Unit
                else -> return ComponentResult.Conflict()
            }
        }
        trail.enter(decision)
        lastPushCreatedLevel = true
        when (decision) {
            is SearchDecision.Bool -> assignBool(
                decision.literal ushr 1,
                if (decision.literal and 1 == 0) TRUE else FALSE,
                source,
            )

            is SearchDecision.IntAtMost, is SearchDecision.IntAtLeast, is SearchDecision.IntEqual -> {
                val premise = if (decision is SearchDecision.IntEqual && atoms != null) {
                    val sides = listOf(
                        SearchDecision.IntAtLeast(decision.variable, decision.value),
                        SearchDecision.IntAtMost(decision.variable, decision.value),
                    )
                    val assertions = sides.map { side ->
                        val bound = checkNotNull(SearchIntegerBound.of(side))
                        registerAtom(bound, bound.complement())?.positive ?: return ComponentResult.Indeterminate
                    }
                    for (assertion in assertions) {
                        val variable = assertion.literal ushr 1
                        val value = if (assertion.literal and 1 == 0) TRUE else FALSE
                        val prior = trail.rawBoolValue(variable)
                        if (prior != UNASSIGNED && prior != value) return ComponentResult.Conflict()
                        if (prior == UNASSIGNED) assignBool(variable, value, source)
                    }
                    SearchAtomPremise.All(assertions.map { SearchAtomPremise.Asserted(SearchDecision.Theory(it)) })
                } else {
                    SearchAtomPremise.Unavailable
                }
                when (val result = updateIntFact(decision, premise)) {
                    ComponentResult.Consistent -> Unit
                    else -> return result
                }
            }

            is SearchDecision.Theory -> Unit
        }
        if (singleComponent != null) {
            val component = singleComponent
            if (component !== source) {
                activeComponent = component
                val result = component.assert(decision, this)
                activeComponent = null
                if (result !is ComponentResult.Consistent) return recordConflict(component, result)
            }
        } else {
            for (component in components) {
                if (component === source) continue
                activeComponent = component
                val result = component.assert(decision, this)
                activeComponent = null
                if (result !is ComponentResult.Consistent) return recordConflict(component, result)
            }
        }
        return propagate()
    }

    /**
     * Report every Boolean variable a retraction frees to [listener], or to nobody when it is null.
     *
     * A branching that indexes only the unassigned variables needs the freeing signal to re-offer them;
     * one that rescans the assignment on every pick does not, and pays nothing for this.
     */
    internal fun observeUnassignments(listener: SearchUnassignListener?) {
        unassignListener = listener
    }

    /** Retract all decisions above [decisionLevel] in reverse trail order. */
    fun popTo(decisionLevel: Int) {
        retractTo(decisionLevel)
    }

    private fun retractTo(decisionLevel: Int) {
        if (decisionLevel == this.decisionLevel) return
        trail.retractTo(decisionLevel, unassignListener)
        learning.onRetraction(trail.boolTrail.size)
        singleComponent?.retract(decisionLevel) ?: components.forEach { it.retract(decisionLevel) }
    }

    /** Run all components until each has observed the current shared state once. */
    fun propagate(): ComponentResult {
        while (true) {
            selfPublicationsPending = false
            while (trail.pendingAssertions.isNotEmpty()) {
                val pending = trail.pendingAssertions.removeFirst()
                val registered = (pending.decision as? SearchDecision.Theory)?.decision as? RegisteredTheoryDecision
                val integer = (registered?.payload as? SearchIntegerBound)?.decision()
                val asserted = integer ?: pending.decision
                if (integer != null) {
                    val result = updateIntFact(integer, SearchAtomPremise.Asserted(pending.decision))
                    if (result !is ComponentResult.Consistent) return result
                }
                if (singleComponent != null) {
                    val component = singleComponent
                    if (component !== pending.source) {
                        activeComponent = component
                        val result = component.assert(asserted, this)
                        activeComponent = null
                        if (result !is ComponentResult.Consistent) return recordConflict(component, result)
                    }
                } else {
                    for (component in components) {
                        if (component === pending.source) continue
                        activeComponent = component
                        val result = component.assert(asserted, this)
                        activeComponent = null
                        if (result !is ComponentResult.Consistent) return recordConflict(component, result)
                    }
                }
            }
            val result = runComponents { it.propagate(this) }
            if (result !is ComponentResult.Consistent) return result
            val learned = propagateLearnedClauses()
            if (learned !is ComponentResult.Consistent) return learned
            if (trail.pendingAssertions.isEmpty() && !selfPublicationsPending) return ComponentResult.Consistent
        }
    }

    /** Complete the candidate leaf, preserving unknown rather than treating it as infeasible. */
    fun check(): ComponentCheck {
        conflictResolver = null
        conflictOwner = null
        for (component in components) {
            when (val result = component.check(this)) {
                ComponentCheck.Feasible -> Unit

                is ComponentCheck.Infeasible -> return result

                ComponentCheck.Indeterminate -> {
                    reportRepresentationDecline()
                    return result
                }
            }
        }
        return ComponentCheck.Feasible
    }

    internal fun conflictResolution(): Pair<SearchConflictResolver, SearchConflictResolution>? {
        val resolver = conflictResolver ?: return null
        conflictResolver = null
        conflictOwner = null
        return resolver to resolver.resolveConflict(this)
    }

    /** Whether the latest conflict prefers its component-owned analyzer. */
    internal val hasNativeConflictResolver: Boolean
        get() = conflictResolver?.prefersNativeConflictAnalysis == true

    /** Whether the component that produced the latest conflict keeps that explanation itself. */
    internal val explanationIsOwnedElsewhere: Boolean
        get() = conflictOwner?.retainsOwnExplanations == true

    /**
     * Resolve a falsified clause-form component explanation to a shared first-UIP consequence.
     *
     * Components use this for certificates such as a guarded difference-logic negative cycle. The
     * analyzer resolves latest implied literals at the conflict level through their trailed reasons
     * until one first-UIP literal remains. Retracting to the second-highest level then makes that
     * literal unit.
     */
    internal fun explainedConflict(explanation: SearchExplanation?): SearchConflictResolution? {
        var clause = namedExplanation(explanation)?.literals?.toList() ?: return null
        if (clause.isEmpty()) return SearchConflictResolution.Exhausted
        if (!clause.all(::isFalseLiteral)) return null
        var remainingResolutions = trail.booleanCount
        while (true) {
            val conflictLevel = clause.maxOf { literal -> levelOf(literal) }
            if (conflictLevel == 0) return SearchConflictResolution.Exhausted
            if (clause.count { literal -> levelOf(literal) == conflictLevel } == 1) {
                val levels = clause.map { literal -> levelOf(literal) }
                    .distinct()
                    .sorted()
                    .toIntArray()
                val backjump = levels.lastOrNull { it < conflictLevel } ?: 0
                learning.onConflict(asserting = true)
                return SearchConflictResolution.Backjump(
                    ExplainedLearnedConflict(SearchExplanation(clause.toIntArray()), backjump, levels),
                )
            }
            if (remainingResolutions-- == 0) return retainUnasserting(clause)
            val pivot = latestResolvableLiteral(clause, conflictLevel) ?: return retainUnasserting(clause)
            clause = resolve(clause, pivot.first, pivot.second) ?: return retainUnasserting(clause)
        }
    }

    /**
     * Retain a conflict that resolution could not make asserting, and leave the traversal to backtrack.
     *
     * The clause is a resolvent of valid clauses, so it holds under the root problem whether or not one
     * literal of it remains at the conflict level. Keeping it is what makes every conflict inform the
     * rest of the search; the caller still backtracks chronologically, which cannot loop the way a
     * non-asserting backjump would.
     */
    private fun retainUnasserting(clause: List<Int>): SearchConflictResolution {
        learning.onConflict(asserting = false)
        learn(SearchExplanation(clause.toIntArray()))
        return SearchConflictResolution.Chronological
    }

    private fun isFalseLiteral(literal: Int): Boolean {
        val value = boolValue(literal ushr 1) ?: return false
        return value != (literal and 1 == 0)
    }

    private fun latestResolvableLiteral(clause: List<Int>, level: Int): Pair<Int, SearchExplanation>? {
        val assignedAtLevel = trail.assignedAtLevel(level)
        for (position in assignedAtLevel.size - 1 downTo 0) {
            val variable = assignedAtLevel[position]
            val assigned = trail.rawBoolValue(variable).takeIf { it != UNASSIGNED } ?: continue
            val falsified = (variable shl 1) or assigned
            if (falsified !in clause) continue
            val reason = reasonOf(variable, falsified xor 1) ?: continue
            return falsified to reason
        }
        return null
    }

    /**
     * Reason for the current assignment of [variable], asking its publisher when the session holds
     * none. A component-supplied clause is retained, so a second resolution through the same literal
     * does not rebuild it.
     */
    private fun reasonOf(variable: Int, implied: Int): SearchExplanation? {
        trail.reasonFor(variable)?.let { return it }
        val published = namedExplanation(trail.publisherOf(variable)?.reasonFor(implied)) ?: return null
        if (implied !in published.literals) return null
        trail.retainReason(variable, published)
        return published
    }

    private fun resolve(clause: List<Int>, pivot: Int, reason: SearchExplanation): List<Int>? {
        val implied = pivot xor 1
        if (implied !in reason.literals) return null
        val resolvent = LinkedHashSet<Int>(clause.size + reason.literals.size)
        for (literal in clause) if (literal != pivot && !addFalseLiteral(resolvent, literal)) return null
        for (literal in reason.literals) if (literal != implied && !addFalseLiteral(resolvent, literal)) return null
        if (pivot in resolvent) return null
        return resolvent.toList()
    }

    private fun addFalseLiteral(clause: MutableSet<Int>, literal: Int): Boolean =
        isFalseLiteral(literal) && literal xor 1 !in clause && (literal in clause || clause.add(literal))

    /** Assemble a complete model from the active components at the current shared level. */
    fun model(): AssembledSearchModel {
        val values = LinkedHashMap<Any, Any>()
        val model = object : SearchModel {
            @Suppress("OVERRIDE_DEPRECATION")
            override fun put(key: Any, value: Any) {
                check(key !in values) { "duplicate model contribution for $key" }
                values[key] = if (key is TypedSearchValueKey<*>) key.capture(value) else value
            }
        }
        trail.contributeModel(model)
        singleComponent?.contributeModel(model, this) ?: components.forEach { it.contributeModel(model, this) }
        return AssembledSearchModel(values)
    }

    internal fun branchAlternatives(kind: SearchBranchKind? = null): List<SearchDecision>? {
        singleComponent?.let { component ->
            proposalAlternatives(component, kind)?.let { return it }
        } ?: components.forEach { component ->
            proposalAlternatives(component, kind)?.let { return it }
        }
        for (brancher in branchers) proposalAlternatives(brancher, kind)?.let { return it }
        for (proposal in proposals) {
            if (kind == null || proposal.branchKind == kind) proposal.nextBranch(this)?.let { return it }
        }
        return null
    }

    private fun proposalAlternatives(component: SearchComponent, kind: SearchBranchKind?): List<SearchDecision>? {
        if (component is SearchBrancher) {
            if (kind == null || ((component as? SearchBranchProposal)?.branchKind ?: SearchBranchKind.Finite) == kind) {
                return component.nextBranch(this)
            }
        } else if (component is SearchBranchProposal && (kind == null || component.branchKind == kind)) {
            return component.nextBranch(this)
        }
        return null
    }

    /** Return to root, notify components, and propagate retained learned clauses. */
    fun restart(): ComponentResult = restartAt(0)

    internal fun restartAt(decisionLevel: Int): ComponentResult {
        popTo(decisionLevel)
        learning.onRestart()
        singleComponent?.onRestart(this) ?: components.forEach { it.onRestart(this) }
        return propagate()
    }

    /**
     * Discard root publications after an owner has rebuilt its native root outside this session.
     *
     * Reseeding CP changes the facts at its post-seed root without changing the shared decision level.
     * The CP adapter calls this boundary before publishing the rebuilt root, so no fact from a previous
     * assumption set can leak into the next component run.
     */
    fun resetRootFacts() {
        require(atoms == null) { "registered source atoms require a fresh session when rebuilding the root" }
        require(decisionLevel == 0) { "root facts can only be rebuilt at shared level zero" }
        trail.resetRoot()
        learning.onRootReset()
    }

    /**
     * Compatibility verdict surface for callers that use only Boolean decisions.
     *
     * The traversal itself is [solve], so Boolean, finite, hybrid, and theory-only configurations share
     * one engine and one restart/budget lifecycle.
     */
    fun solveBoolean(numBoolVars: Int): BooleanSearchResult = when (val result = solve(numBoolVars)) {
        is SearchResult.Satisfied -> BooleanSearchResult.Satisfied(result.model)
        SearchResult.Exhausted -> BooleanSearchResult.Exhausted
        SearchResult.Indeterminate -> BooleanSearchResult.Indeterminate
    }

    /**
     * Drive Boolean and component-provided decisions through one shared search loop.
     *
     * Boolean variables are selected first for compatibility with the existing SAT and theory
     * fragments. Once they are assigned, a [SearchBrancher] may split its remaining local state.
     * A component with no branch is expected to decide its residual in [SearchComponent.check].
     *
     * [candidateHints] steer which side of a Boolean split is tried first and nothing else, so the
     * verdict is the one this traversal would have reached without them.
     *
     * [booleanBranching] chooses which Boolean is split. A heuristic one also observes the traversal,
     * so it is passed as [observer] as well.
     */
    fun solve(
        numBoolVars: Int,
        params: SearchSolveParams = SearchSolveParams(),
        candidateHints: SearchCandidateHints = SearchCandidateHints.None,
        booleanBranching: BooleanBranching = BooleanBranching.SourceOrder(numBoolVars),
        observer: SearchRunObserver = SearchRunObserver.None,
    ): SearchResult {
        val run = openRun(
            numBoolVars,
            params,
            booleanBranching = booleanBranching,
            observer = observer,
            candidateHints = candidateHints,
        )
        return when (val event = run.next()) {
            is SearchRunEvent.Satisfied -> SearchResult.Satisfied(event.model)
            SearchRunEvent.Exhausted -> SearchResult.Exhausted
            SearchRunEvent.Paused -> SearchResult.Indeterminate
            is SearchRunEvent.Indeterminate -> SearchResult.Indeterminate
        }
    }

    /** Open a resumable traversal over the shared component set. */
    fun openRun(
        numBoolVars: Int,
        params: SearchSolveParams = SearchSolveParams(),
        booleanBranching: BooleanBranching = BooleanBranching.SourceOrder(numBoolVars),
        decisionBudget: SearchDecisionBudget = SearchDecisionBudget.Unlimited,
        observer: SearchRunObserver = SearchRunObserver.None,
        modelContinuation: SearchModelContinuation = SearchModelContinuation.Chronological,
        modelPolicy: SearchModelPolicy = SearchModelPolicy.SurfaceAll,
        nodePolicy: SearchNodePolicy = SearchNodePolicy.ExpandAll,
        lifecycle: SearchRunLifecycle = SearchRunLifecycle.None,
        candidateHints: SearchCandidateHints = SearchCandidateHints.None,
    ): SearchRun = openRunAt(
        numBoolVars, params, booleanBranching, decisionBudget, observer,
        modelContinuation, modelPolicy, nodePolicy, lifecycle, candidateHints, SearchBranchSelection.Default, 0,
    )

    /** Open a resumable traversal with independent lazy branch selection. */
    fun openRun(
        numBoolVars: Int,
        params: SearchSolveParams = SearchSolveParams(),
        booleanBranching: BooleanBranching = BooleanBranching.SourceOrder(numBoolVars),
        decisionBudget: SearchDecisionBudget = SearchDecisionBudget.Unlimited,
        observer: SearchRunObserver = SearchRunObserver.None,
        modelContinuation: SearchModelContinuation = SearchModelContinuation.Chronological,
        modelPolicy: SearchModelPolicy = SearchModelPolicy.SurfaceAll,
        nodePolicy: SearchNodePolicy = SearchNodePolicy.ExpandAll,
        lifecycle: SearchRunLifecycle = SearchRunLifecycle.None,
        candidateHints: SearchCandidateHints = SearchCandidateHints.None,
        branchSelection: SearchBranchSelection,
    ): SearchRun = openRunAt(
        numBoolVars, params, booleanBranching, decisionBudget, observer,
        modelContinuation, modelPolicy, nodePolicy, lifecycle, candidateHints, branchSelection, 0,
    )

    internal fun openConditionedRun(
        numBoolVars: Int,
        params: SearchSolveParams = SearchSolveParams(),
        nodePolicy: SearchNodePolicy = SearchNodePolicy.ExpandAll,
        booleanBranching: BooleanBranching = BooleanBranching.SourceOrder(numBoolVars),
        candidateHints: SearchCandidateHints = SearchCandidateHints.None,
        observer: SearchRunObserver = SearchRunObserver.None,
        branchSelection: SearchBranchSelection = SearchBranchSelection.Default,
    ): SearchRun = openRunAt(
        numBoolVars, params, booleanBranching, SearchDecisionBudget.Unlimited,
        observer, SearchModelContinuation.Chronological, SearchModelPolicy.SurfaceAll,
        nodePolicy, SearchRunLifecycle.None, candidateHints, branchSelection, decisionLevel,
    )

    private fun openRunAt(
        numBoolVars: Int,
        params: SearchSolveParams,
        booleanBranching: BooleanBranching,
        decisionBudget: SearchDecisionBudget,
        observer: SearchRunObserver,
        modelContinuation: SearchModelContinuation,
        modelPolicy: SearchModelPolicy,
        nodePolicy: SearchNodePolicy,
        lifecycle: SearchRunLifecycle,
        candidateHints: SearchCandidateHints,
        branchSelection: SearchBranchSelection,
        minimumDecisionLevel: Int,
    ): SearchRun {
        require(atoms == null || numBoolVars in 0..atoms.sourceBooleanCount) {
            "Boolean traversal must stay within the reserved source namespace"
        }
        require(minimumDecisionLevel in 0..decisionLevel) { "run scope must already be asserted" }
        require(minimumDecisionLevel == 0 || modelContinuation == SearchModelContinuation.Chronological) {
            "scoped runs require chronological model continuation"
        }
        decisionsExhausted = false
        executionControls.beginTraversal()
        val listener = booleanBranching as? SearchUnassignListener
        observeUnassignments(
            if (atoms != null && listener != null) {
                SearchUnassignListener { variable -> if (variable < numBoolVars) listener.onUnassign(variable) }
            } else {
                listener
            },
        )
        return SearchRun(
            this,
            params,
            booleanBranching.preferring(candidateHints),
            decisionBudget,
            observer,
            modelContinuation,
            modelPolicy,
            nodePolicy,
            lifecycle,
            branchSelection,
            minimumDecisionLevel,
        )
    }

    /** Open a run from one mode-specific [SearchTraversalPolicy]. */
    internal fun openRun(numBoolVars: Int, policy: SearchTraversalPolicy): SearchRun = openRun(
        numBoolVars = numBoolVars,
        params = policy.solveParams,
        booleanBranching = policy.booleanBranching,
        decisionBudget = policy.decisionBudget,
        observer = policy.observer,
        modelContinuation = policy.modelContinuation,
        modelPolicy = policy.modelPolicy,
        nodePolicy = policy.nodePolicy,
        lifecycle = policy.lifecycle,
        candidateHints = policy.candidateHints,
        branchSelection = policy.branchSelection,
    ).also { it.pauseBeforeDecision = policy.pauseBeforeDecision }

    internal fun blockModelAtRoot(model: AssembledSearchModel): ComponentResult {
        popTo(0)
        if (singleComponent != null) {
            val component = singleComponent
            if (component is SearchModelBlocker) {
                activeComponent = component
                val result = component.blockModel(model, this)
                activeComponent = null
                if (result !is ComponentResult.Consistent) return recordConflict(component, result)
            }
        } else {
            for (component in components) {
                if (component !is SearchModelBlocker) continue
                activeComponent = component
                val result = component.blockModel(model, this)
                activeComponent = null
                if (result !is ComponentResult.Consistent) return recordConflict(component, result)
            }
        }
        return propagate()
    }

    /** Number of sound clause-form explanations retained by the shared Boolean engine. */
    val learnedClauseCount: Int get() = learning.size

    /** Distinct learned clauses with a literal block distance of at most two: the short, reusable ones. */
    internal val glueClauseCount: Long get() = learning.glueClauses

    /** Booleans assigned at the root, however deep the search stands: progress no later search undoes. */
    internal val rootFixedCount: Int get() = trail.rootFixedCount

    /** Snapshot learned-clause telemetry for a completed open-theory solve. */
    fun learnedClauseStats(): OpenTheoryClauseStats = learning.snapshot()

    /** Retain a sound clause-form explanation for subsequent propagation. */
    fun learn(explanation: SearchExplanation?) {
        val literals = namedExplanation(explanation)?.literals ?: return
        learn(literals)
    }

    private fun learn(literals: IntArray) {
        learning.learn(literals, this)
    }

    private fun runComponents(call: (SearchComponent) -> ComponentResult): ComponentResult {
        singleComponent?.let { component ->
            activeComponent = component
            val result = call(component)
            activeComponent = null
            return if (result is ComponentResult.Consistent) {
                ComponentResult.Consistent
            } else {
                recordConflict(component, result)
            }
        }
        for (component in components) {
            activeComponent = component
            val result = call(component)
            activeComponent = null
            if (result !is ComponentResult.Consistent) return recordConflict(component, result)
        }
        return ComponentResult.Consistent
    }

    private fun reportRepresentationDecline() {
        if (executionControls.stopCause == null) executionControls.reportStop(SearchStopCause.RepresentationDecline)
    }

    private fun recordConflict(component: SearchComponent, result: ComponentResult): ComponentResult {
        if (result == ComponentResult.Indeterminate) reportRepresentationDecline()
        if (result is ComponentResult.Conflict) {
            conflictResolver = component as? SearchConflictResolver
            conflictOwner = component
        }
        return result
    }

    /**
     * Propagate the learned database from its watch index.
     *
     * Newly attached clauses are examined once, unit clauses are re-examined after a retraction, and
     * every other clause is visited only when one of its two watched literals is falsified.
     */
    private fun propagateLearnedClauses(): ComponentResult {
        conflictOwner = null
        return learning.propagate(this, trail.boolTrail)
    }

    override fun truth(literal: Int): Boolean? = literalTruth(literal)

    override fun levelOf(literal: Int): Int = trail.levelOf(literal)

    override fun implied(clause: Int, literal: Int): ComponentResult = imply(literal, learning.explanationOf(clause))

    override fun conflicted(clause: Int): ComponentResult = ComponentResult.Conflict(learning.explanationOf(clause))

    private fun literalTruth(literal: Int): Boolean? {
        val value = boolValue(literal ushr 1) ?: return null
        return value == (literal and 1 == 0)
    }

    private fun assignBool(variable: Int, value: Int, source: SearchComponent?) {
        trail.assignBool(variable, value, source, atoms)
    }

    private fun publishIntFact(
        decision: SearchDecision,
        premise: SearchAtomPremise = SearchAtomPremise.Unavailable,
    ): ComponentResult = trail.publishIntFact(decision, premise, this, activeComponent)

    private fun updateIntFact(
        decision: SearchDecision,
        premise: SearchAtomPremise = SearchAtomPremise.Unavailable,
    ): ComponentResult = trail.updateIntFact(decision, premise, this)

    private inner class ExplainedLearnedConflict(
        private val explanation: SearchExplanation,
        override val decisionLevel: Int,
        override val decisionLevels: IntArray,
    ) : SearchLearnedConflict {
        override val lbd: Int get() = decisionLevels.toSet().size
        override val guardLiterals: IntArray get() = explanation.literals

        override fun apply(session: SearchSession): SearchLearnedConflictResult {
            session.learn(explanation)
            return when (session.propagate()) {
                ComponentResult.Consistent -> SearchLearnedConflictResult.Resume
                is ComponentResult.Conflict -> SearchLearnedConflictResult.Chronological
                ComponentResult.Indeterminate -> SearchLearnedConflictResult.Indeterminate
            }
        }
    }

    private companion object {
        const val UNASSIGNED = -1
        const val FALSE = 0
        const val TRUE = 1
    }
}
