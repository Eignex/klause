package com.eignex.klause.solver.search

import com.eignex.klause.solver.Cancellation

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
) : SearchContext {
    private val trail = ArrayList<SearchDecision>()
    private val boolValues = HashMap<Int, Int>()
    private val boolLevels = HashMap<Int, Int>()
    private val boolReasons = HashMap<Int, SearchExplanation>()
    private val valuesAtLevel = ArrayList<MutableList<Int>>().apply { add(ArrayList()) }
    private val intFacts = HashMap<Int, IntFact>()
    private val priorIntFactsAtLevel = ArrayList<MutableMap<Int, IntFact?>>().apply { add(HashMap()) }
    private val pendingAssertions = ArrayDeque<PendingAssertion>()
    private val learnedClauses = ArrayList<LearnedClause>()
    private var activeComponent: SearchComponent? = null
    private var checks = 0L

    /** Current shared decision level. */
    override val decisionLevel: Int get() = trail.size

    override fun boolValue(variable: Int): Boolean? = when (val value = boolValues[variable] ?: UNASSIGNED) {
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
        return when (boolValues[variable] ?: UNASSIGNED) {
            value -> ComponentResult.Consistent

            UNASSIGNED -> {
                assignBool(variable, value)
                if (explanation != null) boolReasons[variable] = explanation
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
    fun push(decision: SearchDecision): ComponentResult = recordAndDispatch(decision, source = null)

    /**
     * Record a decision already applied by [source], then deliver it to every other component.
     *
     * The CP adapter uses this while its specialised pin/analysis path remains in place. It avoids
     * applying the same CP pin twice while giving theory components the identical shared trail.
     */
    fun observe(decision: SearchDecision, source: SearchComponent): ComponentResult =
        recordAndDispatch(decision, source)

    private fun recordAndDispatch(decision: SearchDecision, source: SearchComponent?): ComponentResult {
        if (decision is SearchDecision.Bool) {
            val variable = decision.literal ushr 1
            val value = if (decision.literal and 1 == 0) TRUE else FALSE
            when (boolValues[variable] ?: UNASSIGNED) {
                value -> return ComponentResult.Consistent
                UNASSIGNED -> Unit
                else -> return ComponentResult.Conflict()
            }
        }
        trail.add(decision)
        valuesAtLevel.add(ArrayList())
        priorIntFactsAtLevel.add(HashMap())
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
        for (component in components) {
            if (component === source) continue
            activeComponent = component
            val result = component.assert(decision, this)
            activeComponent = null
            if (result !is ComponentResult.Consistent) return result
        }
        return propagate()
    }

    /** Retract all decisions above [decisionLevel] in reverse trail order. */
    fun popTo(decisionLevel: Int) {
        retractTo(decisionLevel, alreadyRetracted = null)
    }

    /**
     * Remove a failed assertion after [alreadyRetracted] has reverted its own native trail.
     *
     * CP's pin API rolls a conflicting pin back before returning its conflict. The shared trail and
     * peer components still saw that assertion, so they must be reverted without popping CP twice.
     */
    fun rollbackFailedPush(decisionLevel: Int, alreadyRetracted: SearchComponent) {
        retractTo(decisionLevel, alreadyRetracted)
    }

    private fun retractTo(decisionLevel: Int, alreadyRetracted: SearchComponent?) {
        require(decisionLevel in 0..trail.size) { "invalid search decision level $decisionLevel" }
        if (decisionLevel == trail.size) return
        for (level in trail.size downTo decisionLevel + 1) {
            for (variable in valuesAtLevel[level].asReversed()) {
                boolValues.remove(variable)
                boolLevels.remove(variable)
                boolReasons.remove(variable)
            }
            valuesAtLevel.removeAt(level)
            val prior = priorIntFactsAtLevel.removeAt(level)
            for ((variable, fact) in prior) {
                if (fact == null) intFacts.remove(variable) else intFacts[variable] = fact
            }
        }
        trail.subList(decisionLevel, trail.size).clear()
        pendingAssertions.clear()
        for (component in components) if (component !== alreadyRetracted) component.retract(decisionLevel)
    }

    /** Run all components until each has observed the current shared state once. */
    fun propagate(): ComponentResult {
        while (true) {
            while (pendingAssertions.isNotEmpty()) {
                val pending = pendingAssertions.removeFirst()
                for (component in components) {
                    if (component === pending.source) continue
                    activeComponent = component
                    val result = component.assert(pending.decision, this)
                    activeComponent = null
                    if (result !is ComponentResult.Consistent) return result
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
        for (component in components) {
            when (val result = component.check(this)) {
                ComponentCheck.Feasible -> Unit
                is ComponentCheck.Infeasible -> return result
                ComponentCheck.Indeterminate -> return result
            }
        }
        return ComponentCheck.Feasible
    }

    /** Assemble a complete model from the active components at the current shared level. */
    fun model(): AssembledSearchModel {
        val values = LinkedHashMap<Any, Any>()
        val model = object : SearchModel {
            override fun put(key: Any, value: Any) {
                check(values.put(key, value) == null) { "duplicate model contribution for $key" }
            }
        }
        for ((variable, value) in boolValues) {
            model.put(SearchBoolValue(variable), value == TRUE)
        }
        for (component in components) component.contributeModel(model, this)
        return AssembledSearchModel(values)
    }

    /** Return to root, notify components, and propagate retained learned clauses. */
    fun restart(): ComponentResult {
        popTo(0)
        for (component in components) component.onRestart(this)
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
        valuesAtLevel.clear()
        valuesAtLevel.add(ArrayList())
        intFacts.clear()
        priorIntFactsAtLevel.clear()
        priorIntFactsAtLevel.add(HashMap())
        pendingAssertions.clear()
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
        var sawIndeterminate = false
        var decisions = 0L
        var decisionsSinceRestart = 0L

        fun search(): SolveStep {
            if (cancelled()) return SolveStep.Indeterminate
            val alternatives = (0 until numBoolVars).firstOrNull { boolValue(it) == null }?.let { variable ->
                listOf(SearchDecision.Bool((variable shl 1) or 1), SearchDecision.Bool(variable shl 1))
            } ?: components.asSequence().filterIsInstance<SearchBrancher>()
                .mapNotNull { it.nextBranch(this) }
                .firstOrNull()
            if (alternatives == null) {
                return when (val result = check()) {
                    ComponentCheck.Feasible -> SolveStep.Satisfied(model())

                    is ComponentCheck.Infeasible -> {
                        learn(result.explanation)
                        SolveStep.Exhausted
                    }

                    ComponentCheck.Indeterminate -> {
                        sawIndeterminate = true
                        SolveStep.Indeterminate
                    }
                }
            }
            for (decision in alternatives) {
                if (decisions == params.maxDecisions) return SolveStep.Indeterminate
                if (params.restart is SearchRestart.Every && decisionsSinceRestart == params.restart.decisions) {
                    return SolveStep.Restart
                }
                val level = decisionLevel
                decisions++
                decisionsSinceRestart++
                when (val result = push(decision)) {
                    ComponentResult.Consistent -> when (val result = search()) {
                        is SolveStep.Satisfied -> return result
                        SolveStep.Indeterminate -> sawIndeterminate = true
                        SolveStep.Exhausted -> Unit
                        SolveStep.Restart -> return result
                    }

                    ComponentResult.Indeterminate -> sawIndeterminate = true

                    is ComponentResult.Conflict -> learn(result.explanation)
                }
                popTo(level)
            }
            return if (sawIndeterminate) SolveStep.Indeterminate else SolveStep.Exhausted
        }

        while (true) {
            when (val result = search()) {
                is SolveStep.Satisfied -> return SearchResult.Satisfied(result.model)

                SolveStep.Exhausted -> return if (sawIndeterminate) {
                    SearchResult.Indeterminate
                } else {
                    SearchResult.Exhausted
                }

                SolveStep.Indeterminate -> return SearchResult.Indeterminate

                SolveStep.Restart -> when (restart()) {
                    ComponentResult.Consistent -> decisionsSinceRestart = 0L
                    is ComponentResult.Conflict -> return SearchResult.Exhausted
                    ComponentResult.Indeterminate -> return SearchResult.Indeterminate
                }
            }
        }
    }

    /** Number of sound clause-form explanations retained by the shared Boolean engine. */
    val learnedClauseCount: Int get() = learnedClauses.size

    /** Retain a sound clause-form explanation for subsequent propagation. */
    fun learn(explanation: SearchExplanation?) {
        val literals = explanation?.literals ?: return
        learn(literals, source = null)
    }

    /**
     * Retain a clause learned by [source], whose native state already contains the same constraint.
     *
     * Subsequent unit consequences are delivered to every peer but not back to [source]. This is what
     * lets a CP analyzer share its learned clause with theory components without creating a duplicate
     * CP pin or an extra private propagation level.
     */
    internal fun learnFrom(source: SearchComponent, explanation: SearchExplanation) {
        learn(explanation.literals, source)
    }

    private fun learn(literals: IntArray, source: SearchComponent?) {
        if (literals.isEmpty() || learnedClauses.any { it.literals.contentEquals(literals) }) return
        learnedClauses.add(LearnedClause(literals.copyOf(), source))
    }

    private fun runComponents(call: (SearchComponent) -> ComponentResult): ComponentResult {
        for (component in components) {
            activeComponent = component
            val result = call(component)
            activeComponent = null
            if (result !is ComponentResult.Consistent) return result
        }
        return ComponentResult.Consistent
    }

    private fun propagateLearnedClauses(): ComponentResult {
        for (learned in learnedClauses) {
            val clause = learned.literals
            var unit = -1
            var unresolved = 0
            var satisfied = false
            for (literal in clause) {
                val expected = literal and 1 == 0
                when (boolValue(literal ushr 1)) {
                    expected -> {
                        satisfied = true
                        break
                    }

                    null -> {
                        unit = literal
                        unresolved++
                    }

                    else -> Unit
                }
            }
            if (satisfied) continue
            if (unresolved == 0) return ComponentResult.Conflict(SearchExplanation(clause.copyOf()))
            if (unresolved == 1) {
                val result = implyFrom(learned.source, unit, SearchExplanation(clause.copyOf()))
                if (result !is ComponentResult.Consistent) return result
            }
        }
        return ComponentResult.Consistent
    }

    private fun assignBool(variable: Int, value: Int) {
        boolValues[variable] = value
        boolLevels[variable] = decisionLevel
        valuesAtLevel[decisionLevel].add(variable)
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
        if (!changedAtLevel.containsKey(variable)) changedAtLevel[variable] = prior
        intFacts[variable] = candidate
        return ComponentResult.Consistent
    }

    private fun SearchDecision.intVariable(): Int = when (this) {
        is SearchDecision.IntAtMost -> variable
        is SearchDecision.IntAtLeast -> variable
        is SearchDecision.IntEqual -> variable
        is SearchDecision.Bool, is SearchDecision.Theory -> error("only integer decisions carry an integer variable")
    }

    private data class PendingAssertion(val decision: SearchDecision, val source: SearchComponent?)

    private data class LearnedClause(val literals: IntArray, val source: SearchComponent?)

    private data class IntFact(val lower: Long?, val upper: Long?)

    private sealed interface SolveStep {
        data class Satisfied(val model: AssembledSearchModel) : SolveStep
        data object Exhausted : SolveStep
        data object Indeterminate : SolveStep
        data object Restart : SolveStep
    }

    private companion object {
        const val UNASSIGNED = -1
        const val FALSE = 0
        const val TRUE = 1
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
