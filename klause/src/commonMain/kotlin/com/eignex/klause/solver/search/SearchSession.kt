package com.eignex.klause.solver.search

import com.eignex.klause.solver.Cancellation
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.MutableIntIntMap
import com.eignex.klause.util.MutableIntObjectMap

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
    private val cancellation: Cancellation = Cancellation.Never,
    private val learnedDb: SearchLearnedDbParams = SearchLearnedDbParams(),
    private val branchers: List<SearchBrancher> = emptyList(),
) : SearchContext,
    ClauseWatchHost {
    private val singleComponent = components.singleOrNull()
    private val trail = ArrayList<SearchDecision>()
    private val boolValues = MutableIntIntMap()
    private val boolLevels = MutableIntIntMap()
    private val boolReasons = MutableIntObjectMap<SearchExplanation>()

    /** Component that published a Boolean consequence without a clause, which may still explain it. */
    private val boolPublisher = MutableIntObjectMap<SearchComponent>()
    private val valuesAtLevel = ArrayList<IntArrayList>().apply { add(IntArrayList()) }
    private val intFacts = MutableIntObjectMap<IntFact>()
    private val priorIntFactsAtLevel = ArrayList<MutableIntObjectMap<IntFact?>>().apply {
        add(MutableIntObjectMap())
    }
    private val pendingAssertions = ArrayDeque<PendingAssertion>()
    private val learned = WatchedClauseStore()
    private val boolTrail = IntArrayList()
    private val trailStartAtLevel = IntArrayList().apply { add(0) }
    private val pendingAttach = ArrayDeque<Int>()
    private var propagatedTrail = 0
    private var unitsPending = false
    private var activeComponent: SearchComponent? = null
    private var conflictResolver: SearchConflictResolver? = null

    /** Component whose deduction produced the current conflict, or null when the shared engine did. */
    private var conflictOwner: SearchComponent? = null
    private var lastPushCreatedLevel = false
    private var checks = 0L

    /** Current shared decision level. */
    override val decisionLevel: Int get() = trail.size

    override fun boolValue(variable: Int): Boolean? = when (val value = boolValues.getOrDefault(variable, UNASSIGNED)) {
        FALSE -> false
        TRUE -> true
        else -> null
    }

    override fun intLowerBound(variable: Int): Long? = intFacts[variable]?.lower

    override fun intUpperBound(variable: Int): Long? = intFacts[variable]?.upper

    /** Clause-form reason for the current assignment of [variable], or null when it is a decision. */
    fun reasonFor(variable: Int): SearchExplanation? = boolReasons[variable]

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
            pendingAssertions.addLast(PendingAssertion(decision, activeComponent))
            ComponentResult.Consistent
        }
    }

    /**
     * Import a consequence that [source] has already applied to its native state.
     *
     * Native components use this outside their normal callback (for example after asserting a learned
     * clause at an existing level). The source is excluded from the pending delivery, preventing a
     * second native pin from creating an extra private decision level.
     */
    internal fun publishFrom(source: SearchComponent, decision: SearchDecision): ComponentResult {
        val previous = activeComponent
        activeComponent = source
        val result = publish(decision)
        activeComponent = previous
        return result
    }

    private fun assignImplied(literal: Int, explanation: SearchExplanation?): ComponentResult {
        val variable = literal ushr 1
        val value = if (literal and 1 == 0) TRUE else FALSE
        return when (boolValues.getOrDefault(variable, UNASSIGNED)) {
            value -> ComponentResult.Consistent

            UNASSIGNED -> {
                assignBool(variable, value)
                if (explanation != null) {
                    boolReasons.put(variable, explanation)
                } else {
                    activeComponent?.let { boolPublisher.put(variable, it) }
                }
                pendingAssertions.addLast(PendingAssertion(SearchDecision.Bool(literal), activeComponent))
                ComponentResult.Consistent
            }

            else -> ComponentResult.Conflict(explanation)
        }
    }

    override fun consumeCheck(): Boolean = checks++ < maxChecks

    override fun cancelled(): Boolean = cancellation()

    /** Initialize all components at the shared root. */
    fun initialize(): ComponentResult {
        val result = runComponents { it.initialize(this) }
        return if (result is ComponentResult.Consistent) propagate() else result
    }

    /** Assert [decision], then run a shared propagation round. */
    fun push(decision: SearchDecision): ComponentResult {
        lastPushCreatedLevel = false
        return recordAndDispatch(decision, source = null)
    }

    internal fun lastPushCreatedLevel(): Boolean = lastPushCreatedLevel

    private fun recordAndDispatch(decision: SearchDecision, source: SearchComponent?): ComponentResult {
        conflictResolver = null
        conflictOwner = null
        if (decision is SearchDecision.Bool) {
            val variable = decision.literal ushr 1
            val value = if (decision.literal and 1 == 0) TRUE else FALSE
            when (boolValues.getOrDefault(variable, UNASSIGNED)) {
                value -> return ComponentResult.Consistent
                UNASSIGNED -> Unit
                else -> return ComponentResult.Conflict()
            }
        }
        trail.add(decision)
        lastPushCreatedLevel = true
        trailStartAtLevel.add(boolTrail.size)
        valuesAtLevel.add(IntArrayList())
        priorIntFactsAtLevel.add(MutableIntObjectMap())
        when (decision) {
            is SearchDecision.Bool -> assignBool(
                decision.literal ushr 1,
                if (decision.literal and 1 == 0) TRUE else FALSE,
            )

            is SearchDecision.IntAtMost, is SearchDecision.IntAtLeast, is SearchDecision.IntEqual -> {
                when (val result = updateIntFact(decision)) {
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
        } else for (component in components) {
            if (component === source) continue
            activeComponent = component
            val result = component.assert(decision, this)
            activeComponent = null
            if (result !is ComponentResult.Consistent) return recordConflict(component, result)
        }
        return propagate()
    }

    /** Retract all decisions above [decisionLevel] in reverse trail order. */
    fun popTo(decisionLevel: Int) {
        retractTo(decisionLevel)
    }

    private fun retractTo(decisionLevel: Int) {
        require(decisionLevel in 0..trail.size) { "invalid search decision level $decisionLevel" }
        if (decisionLevel == trail.size) return
        for (level in trail.size downTo decisionLevel + 1) {
            val assignedAtLevel = valuesAtLevel[level]
            for (position in assignedAtLevel.size - 1 downTo 0) {
                val variable = assignedAtLevel[position]
                boolValues.remove(variable)
                boolLevels.remove(variable)
                boolReasons.remove(variable)
                boolPublisher.remove(variable)
            }
            valuesAtLevel.removeAt(level)
            priorIntFactsAtLevel.removeAt(level).forEach { variable, fact ->
                if (fact == null) intFacts.remove(variable) else intFacts.put(variable, fact)
            }
        }
        trail.subList(decisionLevel, trail.size).clear()
        boolTrail.truncateTo(trailStartAtLevel[decisionLevel + 1])
        trailStartAtLevel.truncateTo(decisionLevel + 1)
        if (propagatedTrail > boolTrail.size) propagatedTrail = boolTrail.size
        // A unit clause is woken by no assignment, so retraction is the only signal that its literal
        // may have become unassigned again.
        unitsPending = learned.units.size > 0
        pendingAssertions.clear()
        singleComponent?.retract(decisionLevel) ?: components.forEach { it.retract(decisionLevel) }
    }

    /** Run all components until each has observed the current shared state once. */
    fun propagate(): ComponentResult {
        while (true) {
            while (pendingAssertions.isNotEmpty()) {
                val pending = pendingAssertions.removeFirst()
                if (singleComponent != null) {
                    val component = singleComponent
                    if (component !== pending.source) {
                        activeComponent = component
                        val result = component.assert(pending.decision, this)
                        activeComponent = null
                        if (result !is ComponentResult.Consistent) return recordConflict(component, result)
                    }
                } else for (component in components) {
                    if (component === pending.source) continue
                    activeComponent = component
                    val result = component.assert(pending.decision, this)
                    activeComponent = null
                    if (result !is ComponentResult.Consistent) return recordConflict(component, result)
                }
            }
            val result = runComponents { it.propagate(this) }
            if (result !is ComponentResult.Consistent) return result
            val learned = propagateLearnedClauses()
            if (learned !is ComponentResult.Consistent) return learned
            if (pendingAssertions.isEmpty()) return ComponentResult.Consistent
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
                ComponentCheck.Indeterminate -> return result
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
        var clause = explanation?.literals?.toList() ?: return null
        if (clause.isEmpty()) return SearchConflictResolution.Exhausted
        if (!clause.all(::isFalseLiteral)) return null
        var remainingResolutions = boolValues.size
        while (true) {
            val conflictLevel = clause.maxOf { literal -> levelOf(literal) }
            if (conflictLevel == 0) return SearchConflictResolution.Exhausted
            if (clause.count { literal -> levelOf(literal) == conflictLevel } == 1) {
                val levels = clause.map { literal -> levelOf(literal) }
                    .distinct()
                    .sorted()
                    .toIntArray()
                val backjump = levels.lastOrNull { it < conflictLevel } ?: 0
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
        learn(SearchExplanation(clause.toIntArray()))
        return SearchConflictResolution.Chronological
    }

    private fun isFalseLiteral(literal: Int): Boolean {
        val value = boolValue(literal ushr 1) ?: return false
        return value != (literal and 1 == 0)
    }

    private fun latestResolvableLiteral(clause: List<Int>, level: Int): Pair<Int, SearchExplanation>? {
        val assignedAtLevel = valuesAtLevel[level]
        for (position in assignedAtLevel.size - 1 downTo 0) {
            val variable = assignedAtLevel[position]
            val assigned = boolValues.getOrDefault(variable, UNASSIGNED).takeIf { it != UNASSIGNED } ?: continue
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
        boolReasons[variable]?.let { return it }
        val published = boolPublisher[variable]?.reasonFor(implied) ?: return null
        if (implied !in published.literals) return null
        boolReasons.put(variable, published)
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
            override fun put(key: Any, value: Any) {
                check(values.put(key, value) == null) { "duplicate model contribution for $key" }
            }
        }
        boolValues.forEach { variable, value ->
            model.put(SearchBoolValue(variable), value == TRUE)
        }
        singleComponent?.contributeModel(model, this) ?: components.forEach { it.contributeModel(model, this) }
        return AssembledSearchModel(values)
    }

    /** First component-owned exhaustive split available at the current shared level. */
    internal fun branchAlternatives(): List<SearchDecision>? {
        singleComponent?.let { component ->
            if (component is SearchBrancher) component.nextBranch(this)?.let { return it }
        } ?: components.forEach { component ->
            if (component is SearchBrancher) component.nextBranch(this)?.let { return it }
        }
        for (brancher in branchers) brancher.nextBranch(this)?.let { return it }
        return null
    }

    /** Return to root, notify components, and propagate retained learned clauses. */
    fun restart(): ComponentResult {
        popTo(0)
        reduceLearnedDb()
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
        require(decisionLevel == 0) { "root facts can only be rebuilt at shared level zero" }
        boolValues.clear()
        boolLevels.clear()
        boolReasons.clear()
        boolPublisher.clear()
        valuesAtLevel.clear()
        valuesAtLevel.add(IntArrayList())
        intFacts.clear()
        priorIntFactsAtLevel.clear()
        priorIntFactsAtLevel.add(MutableIntObjectMap())
        pendingAssertions.clear()
        boolTrail.clear()
        trailStartAtLevel.clear()
        trailStartAtLevel.add(0)
        propagatedTrail = 0
        unitsPending = learned.units.size > 0
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
     */
    fun solve(numBoolVars: Int, params: SearchSolveParams = SearchSolveParams()): SearchResult {
        val run = openRun(numBoolVars, params)
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
    ): SearchRun = SearchRun(
        this,
        params,
        booleanBranching,
        decisionBudget,
        observer,
        modelContinuation,
        modelPolicy,
        nodePolicy,
        lifecycle,
    )

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
    )

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
        } else for (component in components) {
            if (component !is SearchModelBlocker) continue
            activeComponent = component
            val result = component.blockModel(model, this)
            activeComponent = null
            if (result !is ComponentResult.Consistent) return recordConflict(component, result)
        }
        return propagate()
    }

    /** Number of sound clause-form explanations retained by the shared Boolean engine. */
    val learnedClauseCount: Int get() = learned.size

    /** Retain a sound clause-form explanation for subsequent propagation. */
    fun learn(explanation: SearchExplanation?) {
        val literals = explanation?.literals ?: return
        learn(literals)
    }

    private fun learn(literals: IntArray) {
        if (literals.isEmpty()) return
        val index = learned.add(literals.copyOf(), lbdOf(literals))
        if (index >= 0) pendingAttach.addLast(index)
    }

    private fun lbdOf(literals: IntArray): Int {
        val levels = HashSet<Int>(literals.size)
        for (literal in literals) levels.add(levelOf(literal))
        return levels.size
    }

    /**
     * Drop the weakest retained clauses once the database exceeds its cap.
     *
     * Every learned clause is implied by the root problem, so forgetting one only weakens propagation.
     * Clauses that are glue, that have propagated since the last reduction, or that are unit are kept
     * regardless.
     */
    private fun reduceLearnedDb() {
        val cap = learnedDb.maxClauses ?: return
        // Reduction renumbers clauses, so it waits until no clause is queued for its first
        // examination. The next propagation drains that queue, so the following restart reduces.
        if (learned.size <= cap || pendingAttach.isNotEmpty()) return
        val droppable = IntArrayList(learned.size)
        for (index in 0 until learned.size) {
            val retained = learned.lbdAt(index) <= learnedDb.glueLbd ||
                learned.usedAt(index) ||
                learned.literalsAt(index).size == 1
            if (!retained) droppable.add(index)
        }
        val survivors = (cap - (learned.size - droppable.size)).coerceAtLeast(0)
        if (droppable.size <= survivors) {
            learned.clearUsed()
            return
        }
        droppable.sortByIntKey { learned.lbdAt(it) }
        val dropped = HashSet<Int>(droppable.size - survivors)
        for (position in survivors until droppable.size) dropped.add(droppable[position])
        learned.retain { index -> index !in dropped }
        learned.clearUsed()
        unitsPending = learned.units.size > 0
    }

    private fun runComponents(call: (SearchComponent) -> ComponentResult): ComponentResult {
        singleComponent?.let { component ->
            activeComponent = component
            val result = call(component)
            activeComponent = null
            return if (result is ComponentResult.Consistent) ComponentResult.Consistent else recordConflict(component, result)
        }
        for (component in components) {
            activeComponent = component
            val result = call(component)
            activeComponent = null
            if (result !is ComponentResult.Consistent) return recordConflict(component, result)
        }
        return ComponentResult.Consistent
    }

    private fun recordConflict(component: SearchComponent, result: ComponentResult): ComponentResult {
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
        // A conflict from this store belongs to the engine, not to whichever component last had one.
        conflictOwner = null
        while (pendingAttach.isNotEmpty()) {
            val result = learned.attach(pendingAttach.removeFirst(), this)
            if (result !is ComponentResult.Consistent) return result
        }
        if (unitsPending) {
            unitsPending = false
            val result = learned.propagateUnits(this)
            if (result !is ComponentResult.Consistent) {
                // The units after this one stay pending: leaving early must not drop them until the
                // next retraction.
                unitsPending = true
                return result
            }
        }
        while (propagatedTrail < boolTrail.size) {
            val falsified = boolTrail[propagatedTrail++] xor 1
            val result = learned.propagate(falsified, this)
            if (result !is ComponentResult.Consistent) {
                // The watchers of this literal were left partly unvisited, and a backjump does not
                // always rewind past it, so it stays queued rather than being silently skipped.
                propagatedTrail--
                return result
            }
        }
        return ComponentResult.Consistent
    }

    override fun truth(literal: Int): Boolean? = literalTruth(literal)

    override fun levelOf(literal: Int): Int = boolLevels.getOrDefault(literal ushr 1, 0)

    override fun implied(clause: Int, literal: Int): ComponentResult = imply(literal, learned.explanationOf(clause))

    override fun conflicted(clause: Int): ComponentResult = ComponentResult.Conflict(learned.explanationOf(clause))

    private fun literalTruth(literal: Int): Boolean? {
        val value = boolValue(literal ushr 1) ?: return null
        return value == (literal and 1 == 0)
    }

    private fun assignBool(variable: Int, value: Int) {
        boolValues.put(variable, value)
        boolLevels.put(variable, decisionLevel)
        valuesAtLevel[decisionLevel].add(variable)
        boolTrail.add((variable shl 1) or if (value == TRUE) 0 else 1)
    }

    private fun publishIntFact(decision: SearchDecision): ComponentResult {
        val before = intFacts[decision.intVariable()]
        val result = updateIntFact(decision)
        if (result !is ComponentResult.Consistent || intFacts[decision.intVariable()] == before) return result
        pendingAssertions.addLast(PendingAssertion(decision, activeComponent))
        return ComponentResult.Consistent
    }

    private fun updateIntFact(decision: SearchDecision): ComponentResult {
        val variable = decision.intVariable()
        val prior = intFacts[variable]
        val candidate = when (decision) {
            is SearchDecision.IntAtMost -> IntFact(prior?.lower, minOf(prior?.upper ?: Long.MAX_VALUE, decision.upper))

            is SearchDecision.IntAtLeast -> IntFact(maxOf(prior?.lower ?: Long.MIN_VALUE, decision.lower), prior?.upper)

            is SearchDecision.IntEqual -> IntFact(
                maxOf(prior?.lower ?: Long.MIN_VALUE, decision.value),
                minOf(prior?.upper ?: Long.MAX_VALUE, decision.value),
            )

            is SearchDecision.Bool, is SearchDecision.Theory -> error("only integer decisions carry integer facts")
        }
        if (candidate.lower != null && candidate.upper != null && candidate.lower > candidate.upper) {
            return ComponentResult.Conflict()
        }
        if (candidate == prior) return ComponentResult.Consistent
        val changedAtLevel = priorIntFactsAtLevel[decisionLevel]
        if (!changedAtLevel.containsKey(variable)) changedAtLevel.put(variable, prior)
        intFacts.put(variable, candidate)
        return ComponentResult.Consistent
    }

    private fun SearchDecision.intVariable(): Int = when (this) {
        is SearchDecision.IntAtMost -> variable
        is SearchDecision.IntAtLeast -> variable
        is SearchDecision.IntEqual -> variable
        is SearchDecision.Bool, is SearchDecision.Theory -> error("only integer decisions carry an integer variable")
    }

    private data class PendingAssertion(val decision: SearchDecision, val source: SearchComponent?)

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

    private data class IntFact(val lower: Long?, val upper: Long?)

    private companion object {
        const val UNASSIGNED = -1
        const val FALSE = 0
        const val TRUE = 1
    }
}

/**
 * Resumable traversal of a [SearchSession].
 *
 * Frames contain public shared decisions only. Consequently a CP split, an integer-theory split, and a
 * future array/function/quantifier split all follow the identical push, propagation, retraction, restart,
 * and budget path.
 */
class SearchRun internal constructor(
    private val session: SearchSession,
    private val params: SearchSolveParams,
    private val booleanBranching: BooleanBranching,
    private val decisionBudget: SearchDecisionBudget,
    private val observer: SearchRunObserver,
    private val modelContinuation: SearchModelContinuation,
    private val modelPolicy: SearchModelPolicy,
    private val nodePolicy: SearchNodePolicy,
    private val lifecycle: SearchRunLifecycle,
) {
    private val frames = ArrayList<Frame>()
    private var decisions = 0L
    private var decisionsSinceRestart = 0L
    private var sawIndeterminate = false
    private var resumeAfterSolution = false
    private var lastModel: AssembledSearchModel? = null
    private var consumedModel = false
    private var terminal: SearchRunEvent? = null
    private var started = false
    private val cancellationPoller = SearchCancellationPoller()

    init {
        params.restart.beginRun()
    }

    /** Advance until the next model or terminal verdict. */
    fun next(): SearchRunEvent {
        terminal?.let { return it }
        if (!started) {
            started = true
            lifecycle.onStart(session).toEvent()?.let { return it }
        }
        lifecycle.onResume(session).toEvent()?.let { return it }
        if (resumeAfterSolution) {
            resumeAfterSolution = false
            when (modelContinuation) {
                SearchModelContinuation.Chronological -> if (!backtrack()) return stopAfterBacktrack()

                SearchModelContinuation.BlockAtRoot -> when (session.blockModelAtRoot(checkNotNull(lastModel))) {
                    ComponentResult.Consistent -> {
                        frames.clear()
                        consumedModel = true
                        lifecycle.onModelBlocked(session).toEvent()?.let { return it }
                    }

                    is ComponentResult.Conflict -> return finish(SearchRunEvent.Exhausted)

                    ComponentResult.Indeterminate -> return finish(SearchRunEvent.Indeterminate.Component)
                }
            }
        }
        while (true) {
            if (cancellationPoller.due()) {
                if (session.cancelled()) {
                    return lifecycle.onCancellation(session).toEvent()
                        ?: finish(SearchRunEvent.Indeterminate.Cancelled)
                }
                cancellationPoller.rearm()
            }
            when (val node = nodePolicy.beforeBranch(session)) {
                SearchNodeDisposition.Expand -> Unit

                SearchNodeDisposition.Prune -> {
                    observer.onConflict(null)
                    if (!backtrack()) return stopAfterBacktrack()
                    continue
                }

                is SearchNodeDisposition.Backjump -> {
                    observer.onConflict(null)
                    observer.onLearnedNodeBackjump()
                    when (applyLearnedConflict(node.consequence)) {
                        SearchLearnedConflictResult.Resume -> continue

                        SearchLearnedConflictResult.Exhausted -> return finish(SearchRunEvent.Exhausted)

                        SearchLearnedConflictResult.Chronological -> if (!backtrack()) return stopAfterBacktrack()

                        SearchLearnedConflictResult.Indeterminate -> {
                            return finish(SearchRunEvent.Indeterminate.Component)
                        }

                        is SearchLearnedConflictResult.Backjump -> error("learned conflict cascade did not terminate")
                    }
                    continue
                }

                SearchNodeDisposition.Indeterminate -> return finish(SearchRunEvent.Indeterminate.Component)
            }
            when (val alternatives = alternatives()) {
                is Alternatives.Leaf -> when (val result = session.check()) {
                    ComponentCheck.Feasible -> {
                        params.restart.onSolution()
                        val model = session.model().also {
                            lastModel = it
                            observer.onModel(it)
                        }
                        resumeAfterSolution = true
                        when (modelPolicy.onModel(model, session)) {
                            SearchModelDisposition.Surface -> return SearchRunEvent.Satisfied(model)

                            SearchModelDisposition.Continue -> {
                                resumeAfterSolution = false
                                when (modelContinuation) {
                                    SearchModelContinuation.Chronological -> if (!backtrack()) {
                                        return stopAfterBacktrack()
                                    }

                                    SearchModelContinuation.BlockAtRoot -> when (
                                        session.blockModelAtRoot(checkNotNull(lastModel))
                                    ) {
                                        ComponentResult.Consistent -> {
                                            frames.clear()
                                            consumedModel = true
                                            lifecycle.onModelBlocked(session).toEvent()?.let { return it }
                                        }

                                        is ComponentResult.Conflict -> return finish(SearchRunEvent.Exhausted)

                                        ComponentResult.Indeterminate -> return finish(
                                            SearchRunEvent.Indeterminate.Component,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    is ComponentCheck.Infeasible -> {
                        observer.onConflict(null)
                        if (resolveExplainedConflict(result.explanation)) {
                            if (decisionsSinceRestart > 0 && params.restart.shouldRestart(decisionsSinceRestart)) {
                                restart()?.let { return it }
                            }
                            continue
                        }
                        retainExplanation(result.explanation)
                        if (decisionsSinceRestart > 0 && params.restart.shouldRestart(decisionsSinceRestart)) {
                            restart()?.let { return it }
                            continue
                        }
                        if (!resolveConflict() && !backtrack()) {
                            return stopAfterBacktrack()
                        }
                    }

                    ComponentCheck.Indeterminate -> {
                        sawIndeterminate = true
                        if (session.cancelled()) {
                            return lifecycle.onCancellation(session).toEvent()
                                ?: finish(SearchRunEvent.Indeterminate.Cancelled)
                        }
                        if (!backtrack()) return stopAfterBacktrack(indeterminate = true)
                    }
                }

                is Alternatives.Branch -> {
                    if (alternatives.decisions.isEmpty()) {
                        if (!backtrack()) return stopAfterBacktrack()
                        continue
                    }
                    if (decisionsSinceRestart > 0 && params.restart.shouldRestart(decisionsSinceRestart)) {
                        restart()?.let { return it }
                        continue
                    }
                    frames += Frame(session.decisionLevel, alternatives.decisions)
                    when (advanceFrame()) {
                        Advance.Expanded -> Unit
                        Advance.Budget -> return finish(SearchRunEvent.Indeterminate.Budget)
                        Advance.Exhausted -> if (!backtrack()) return stopAfterBacktrack()
                        Advance.Restart -> restart()?.let { return it }
                    }
                }
            }
        }
    }

    private fun alternatives(): Alternatives {
        booleanBranching.alternatives(session)?.let { return Alternatives.Branch(it) }
        val decisions = session.branchAlternatives()
        return if (decisions == null) Alternatives.Leaf else Alternatives.Branch(decisions)
    }

    private fun advanceFrame(): Advance {
        val frame = frames.last()
        while (frame.next < frame.decisions.size) {
            if (decisions == params.maxDecisions) return Advance.Budget
            val level = session.decisionLevel
            val decision = frame.decisions[frame.next++]
            if (!decision.tightens(session)) continue
            decisions++
            decisionsSinceRestart++
            when (val result = session.push(decision)) {
                ComponentResult.Consistent -> {
                    if (!session.lastPushCreatedLevel()) continue
                    observer.onCommit(decision, session.decisionLevel)
                    if (!decisionBudget.consume()) return Advance.Budget
                    return Advance.Expanded
                }

                ComponentResult.Indeterminate -> {
                    session.popTo(level)
                    sawIndeterminate = true
                }

                is ComponentResult.Conflict -> {
                    observer.onConflict(decision)
                    if (!session.hasNativeConflictResolver && resolveExplainedConflict(result.explanation)) {
                        return Advance.Expanded
                    }
                    if (session.decisionLevel < level) return Advance.Exhausted
                    retainExplanation(result.explanation)
                    session.popTo(level)
                    if (resolveConflict()) return Advance.Expanded
                    if (frames.isEmpty()) return Advance.Exhausted
                    if (frames.last() !== frame) return Advance.Exhausted
                }
            }
        }
        frames.removeLast()
        return Advance.Exhausted
    }

    private fun SearchDecision.tightens(context: SearchContext): Boolean = when (this) {
        is SearchDecision.Bool -> context.boolValue(literal ushr 1) == null

        is SearchDecision.IntAtMost -> (context.intUpperBound(variable) ?: Long.MAX_VALUE) > upper

        is SearchDecision.IntAtLeast -> (context.intLowerBound(variable) ?: Long.MIN_VALUE) < lower

        is SearchDecision.IntEqual -> context.intLowerBound(
            variable,
        ) != value || context.intUpperBound(variable) != value

        is SearchDecision.Theory -> true
    }

    private fun backtrack(): Boolean {
        while (frames.isNotEmpty()) {
            val frame = frames.last()
            if (session.decisionLevel > frame.level) session.popTo(frame.level)
            when (advanceFrame()) {
                Advance.Expanded -> return true
                Advance.Budget -> return false
                Advance.Exhausted -> Unit
                Advance.Restart -> return restart() == null
            }
        }
        return false
    }

    /**
     * Retain a conflict explanation the shared database does not already own elsewhere.
     *
     * A component with its own conflict analyzer keeps the clause in its own database and propagates it
     * there, so copying it here would give one clause two watch indexes and two reduction policies while
     * deducing nothing new. The shared analyzer still reaches that component's reasoning, through
     * [SearchComponent.reasonFor].
     */
    private fun retainExplanation(explanation: SearchExplanation?) {
        if (session.explanationIsOwnedElsewhere) return
        session.learn(explanation)
    }

    private fun resolveExplainedConflict(explanation: SearchExplanation?): Boolean {
        if (session.hasNativeConflictResolver) return false
        when (val resolution = session.explainedConflict(explanation)) {
            is SearchConflictResolution.Backjump -> {
                return when (applyLearnedConflict(resolution.conflict)) {
                    SearchLearnedConflictResult.Resume -> true

                    SearchLearnedConflictResult.Exhausted,
                    SearchLearnedConflictResult.Chronological,
                    SearchLearnedConflictResult.Indeterminate,
                    -> false

                    is SearchLearnedConflictResult.Backjump -> error("learned conflict cascade did not terminate")
                }
            }

            SearchConflictResolution.Exhausted -> return false

            SearchConflictResolution.Chronological, null -> Unit
        }
        return false
    }

    private fun resolveConflict(): Boolean {
        val (resolver, resolution) = session.conflictResolution() ?: return false
        if (consumedModel && !resolver.resolvesAfterModelBlock) return false
        when (resolution) {
            SearchConflictResolution.Chronological -> return false

            SearchConflictResolution.Exhausted -> return false

            is SearchConflictResolution.Backjump -> {
                return when (applyLearnedConflict(resolution.conflict)) {
                    SearchLearnedConflictResult.Resume -> true

                    SearchLearnedConflictResult.Exhausted,
                    SearchLearnedConflictResult.Chronological,
                    SearchLearnedConflictResult.Indeterminate,
                    -> false

                    is SearchLearnedConflictResult.Backjump -> error("learned conflict cascade did not terminate")
                }
            }
        }
    }

    private fun applyLearnedConflict(initial: SearchLearnedConflict): SearchLearnedConflictResult {
        var conflict = initial
        repeat(MAX_NODE_BACKJUMPS) {
            if (conflict.decisionLevel !in 0..session.decisionLevel) {
                return SearchLearnedConflictResult.Chronological
            }
            params.restart.recordConflict(conflict.lbd, session.decisionLevel)
            observer.onLearnedConflict(conflict)
            session.popTo(conflict.decisionLevel)
            while (frames.isNotEmpty() && frames.last().level >= conflict.decisionLevel) frames.removeLast()
            when (val result = conflict.apply(session)) {
                SearchLearnedConflictResult.Resume,
                SearchLearnedConflictResult.Exhausted,
                SearchLearnedConflictResult.Chronological,
                SearchLearnedConflictResult.Indeterminate,
                -> return result

                is SearchLearnedConflictResult.Backjump -> conflict = result.conflict
            }
        }
        return SearchLearnedConflictResult.Chronological
    }

    private fun exhausted(): SearchRunEvent = if (sawIndeterminate) {
        SearchRunEvent.Indeterminate.Component
    } else {
        SearchRunEvent.Exhausted
    }

    private fun finish(event: SearchRunEvent): SearchRunEvent {
        terminal = event
        return event
    }

    private fun stopAfterBacktrack(indeterminate: Boolean = false): SearchRunEvent {
        if (session.cancelled()) {
            return lifecycle.onCancellation(session).toEvent()
                ?: finish(SearchRunEvent.Indeterminate.Cancelled)
        }
        return if (indeterminate) finish(SearchRunEvent.Indeterminate.Component) else finish(exhausted())
    }

    /** Return the shared session to its root and run every mode-specific restart action. */
    private fun restart(): SearchRunEvent? = when (session.restart()) {
        ComponentResult.Consistent -> {
            frames.clear()
            params.restart.onRestart()
            observer.onRestart(decisionsSinceRestart)
            decisionsSinceRestart = 0L
            params.restart.beginRun()
            lifecycle.onRestart(session).toEvent()
        }

        is ComponentResult.Conflict -> finish(SearchRunEvent.Exhausted)

        ComponentResult.Indeterminate -> if (session.cancelled()) {
            lifecycle.onCancellation(session).toEvent() ?: finish(SearchRunEvent.Indeterminate.Cancelled)
        } else {
            finish(SearchRunEvent.Indeterminate.Component)
        }
    }

    private fun SearchRunDisposition.toEvent(): SearchRunEvent? = when (this) {
        SearchRunDisposition.Continue -> null
        SearchRunDisposition.Exhausted -> finish(exhausted())
        SearchRunDisposition.Pause -> SearchRunEvent.Paused
        SearchRunDisposition.Indeterminate -> finish(SearchRunEvent.Indeterminate.Component)
    }

    /** Reset traversal state after its components have been reseeded at root. */
    fun reset() {
        check(session.decisionLevel == 0) { "a search run may only reset at its root" }
        frames.clear()
        decisions = 0L
        decisionsSinceRestart = 0L
        sawIndeterminate = false
        resumeAfterSolution = false
        lastModel = null
        consumedModel = false
        terminal = null
        started = false
        cancellationPoller.reset()
        params.restart.beginRun()
    }

    private data class Frame(val level: Int, val decisions: List<SearchDecision>, var next: Int = 0)

    private sealed interface Alternatives {
        data object Leaf : Alternatives
        data class Branch(val decisions: List<SearchDecision>) : Alternatives
    }

    private enum class Advance {
        Expanded,
        Exhausted,
        Budget,
        Restart,
    }

    private companion object {
        const val MAX_NODE_BACKJUMPS = 64
    }
}

/** Charges successful shared decisions to an optional solve-wide allowance. */
fun interface SearchDecisionBudget {
    /** Record one decision and return whether traversal may continue. */
    fun consume(): Boolean

    /** No additional solve-wide decision limit. */
    data object Unlimited : SearchDecisionBudget {
        override fun consume(): Boolean = true
    }
}

/** Observes shared traversal lifecycle events without owning its trail. */
interface SearchRunObserver {
    /** A shared decision was accepted at [decisionLevel]. */
    fun onCommit(decision: SearchDecision, decisionLevel: Int) {}

    /** A branch assertion or candidate leaf was refuted. */
    fun onConflict(decision: SearchDecision?) {}

    /** A node policy supplied an asserting learned consequence. */
    fun onLearnedNodeBackjump() {}

    /**
     * A component produced a learned consequence before the runner retracts to its asserting level.
     *
     * The observer may retain analysis metadata for mode-specific reporting, such as projecting an
     * assumption core. Restart feedback remains owned by [SearchRun].
     */
    fun onLearnedConflict(conflict: SearchLearnedConflict) {}

    /** The runner returned the session to root after [decisions] decisions in the completed run. */
    fun onRestart(decisions: Long) {}

    /** The session assembled a complete model. */
    fun onModel(model: AssembledSearchModel) {}

    /** No-op lifecycle observer. */
    data object None : SearchRunObserver
}

/** Supplies Boolean alternatives before component-owned residual splits. */
fun interface BooleanBranching {
    /** Exhaustive alternatives for one unassigned Boolean, or null when Boolean branching is complete. */
    fun alternatives(context: SearchContext): List<SearchDecision>?

    /** Source-order Boolean variables with false before true. */
    class SourceOrder(private val numBoolVars: Int) : BooleanBranching {
        override fun alternatives(context: SearchContext): List<SearchDecision>? =
            (0 until numBoolVars).firstOrNull { context.boolValue(it) == null }?.let { variable ->
                listOf(SearchDecision.Bool((variable shl 1) or 1), SearchDecision.Bool(variable shl 1))
            }
    }

    /** Leave Boolean selection to a component-owned [SearchBrancher]. */
    data object None : BooleanBranching {
        override fun alternatives(context: SearchContext): List<SearchDecision>? = null
    }
}

/** A model or terminal verdict from a resumable [SearchRun]. */
sealed interface SearchRunEvent {
    /** A complete model; call [SearchRun.next] again to continue enumeration. */
    data class Satisfied(
        /** The complete model assembled by the participating search components. */
        val model: AssembledSearchModel,
    ) : SearchRunEvent

    /** Every branch was refuted. */
    data object Exhausted : SearchRunEvent

    /** The mode yielded cooperatively and may resume by calling [SearchRun.next]. */
    data object Paused : SearchRunEvent

    /** Cancellation, a limit, or a component prevented an exact verdict. */
    sealed interface Indeterminate : SearchRunEvent {
        /** A component could not decide a candidate leaf exactly. */
        data object Component : Indeterminate

        /** The shared decision allowance was spent. */
        data object Budget : Indeterminate

        /** The solve cancellation token fired. */
        data object Cancelled : Indeterminate
    }
}

/** Terminal result of [SearchSession.solveBoolean]. */
sealed interface BooleanSearchResult {
    /**
     * Every component accepted the complete shared assignment.
     *
     * @property model Values contributed by every active component.
     */
    data class Satisfied(val model: AssembledSearchModel) : BooleanSearchResult

    /** Every complete Boolean assignment was refuted. */
    data object Exhausted : BooleanSearchResult

    /** A component or a solve-wide limit prevented an exact verdict. */
    data object Indeterminate : BooleanSearchResult
}

/** Terminal result of [SearchSession.solve]. */
sealed interface SearchResult {
    /**
     * Every component accepted the complete shared assignment.
     *
     * @property model Values contributed by every active component.
     */
    data class Satisfied(val model: AssembledSearchModel) : SearchResult

    /** Every component-provided branch was refuted. */
    data object Exhausted : SearchResult

    /** A component or a solve-wide limit prevented an exact verdict. */
    data object Indeterminate : SearchResult
}
