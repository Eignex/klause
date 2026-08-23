package com.eignex.klause.solver.search

/**
 * A trailed decision visible to every component in a [SearchSession].
 *
 * Decisions are opaque to the shared engine. A finite-domain component may own an integer-bound
 * decision; a theory normally consumes only [Bool]. This keeps finite domains out of the theory
 * contract while allowing every component to follow the same decision levels.
 */
sealed interface SearchDecision {
    /**
     * A Boolean literal asserted at one shared decision level.
     *
     * @property literal Encoded Boolean literal.
     */
    data class Bool(val literal: Int) : SearchDecision

    /**
     * A finite-domain component-owned integer upper-bound branch.
     *
     * @property variable Source integer variable id.
     * @property upper Inclusive upper bound.
     */
    data class IntAtMost(val variable: Int, val upper: Long) : SearchDecision

    /**
     * A finite-domain component-owned integer lower-bound branch.
     *
     * @property variable Source integer variable id.
     * @property lower Inclusive lower bound.
     */
    data class IntAtLeast(val variable: Int, val lower: Long) : SearchDecision

    /**
     * A finite-domain equality exposed to peers without exposing a CP domain object.
     *
     * @property variable Source integer variable id.
     * @property value Required value.
     */
    data class IntEqual(val variable: Int, val value: Long) : SearchDecision

    /**
     * A theory-owned split or assertion whose representation is opaque to the shared engine.
     *
     * This is how arbitrary-precision arithmetic, arrays, functions, and quantified instantiation
     * components retain their native terms while still sharing one decision level and backtracking
     * lifecycle with CP and Boolean reasoning.
     */
    data class Theory(
        /** Theory-local assertion payload. */
        val decision: SearchTheoryDecision,
    ) : SearchDecision
}

/** A typed theory-local assertion carried by [SearchDecision.Theory]. */
interface SearchTheoryDecision

/** A clause-form explanation that can enter the shared learned-clause database. */
data class SearchExplanation(
    /** The clause implied by the component. */
    val literals: IntArray,
)

/** One component's result while the shared session advances. */
sealed interface ComponentResult {
    /** The component made no terminal deduction. */
    data object Consistent : ComponentResult

    /**
     * The component derived a conflict. A missing [explanation] is still a sound conflict, but the
     * shared engine must chronologically backtrack instead of learning from it.
     */
    data class Conflict(val explanation: SearchExplanation? = null) : ComponentResult

    /** The component cannot complete an exact check under the active limits. */
    data object Indeterminate : ComponentResult
}

/** Complete-check result contributed by one component at a candidate leaf. */
sealed interface ComponentCheck {
    /** The component accepts the leaf. */
    data object Feasible : ComponentCheck

    /**
     * The component rejects the leaf. A missing [explanation] cannot enter shared learning, but it
     * remains a valid leaf refutation.
     */
    data class Infeasible(val explanation: SearchExplanation? = null) : ComponentCheck

    /** The component cannot decide the leaf exactly. */
    data object Indeterminate : ComponentCheck
}

/**
 * A deductive participant in [SearchSession].
 *
 * Components receive every shared decision and every retraction. They may retain theory-local or
 * finite-domain state. Only a [SearchExplanation] may enter the engine's learned-clause database;
 * an unexplained conflict remains usable for chronological backtracking but is never learned.
 */
interface SearchComponent {
    /** Called once after the session has constructed its post-build root. */
    fun initialize(context: SearchContext): ComponentResult = ComponentResult.Consistent

    /** Assert one shared decision at the current decision level. */
    fun assert(decision: SearchDecision, context: SearchContext): ComponentResult = ComponentResult.Consistent

    /** Restore this component to [decisionLevel]. */
    fun retract(decisionLevel: Int) {}

    /** Run any work made pending by assertions or peer deductions. */
    fun propagate(context: SearchContext): ComponentResult = ComponentResult.Consistent

    /** Decide the component's residual at a candidate leaf. */
    fun check(context: SearchContext): ComponentCheck = ComponentCheck.Feasible

    /** Called after the shared engine has returned every component to its root level. */
    fun onRestart(context: SearchContext) {}

    /** Add this component's values to the shared complete model. */
    fun contributeModel(model: SearchModel, context: SearchContext) {}
}

/**
 * A component that can supply a complete, trailed split for the shared search engine.
 *
 * The component selects from its private state, but the returned alternatives are public
 * [SearchDecision]s. Consequently the session, rather than a CP or theory-specific loop, owns
 * levels, propagation scheduling, retraction, cancellation, and terminal model assembly.
 */
interface SearchBrancher : SearchComponent {
    /**
     * Return the next exhaustive alternatives, or `null` when this component has no residual
     * search state. Alternatives must be mutually exclusive and cover the residual component state.
     */
    fun nextBranch(context: SearchContext): List<SearchDecision>?
}

/** Restart schedule owned by the shared search engine. */
sealed interface SearchRestart {
    /** Do not restart the current search. */
    data object Never : SearchRestart

    /** Restart after each [decisions] shared decisions. */
    data class Every(val decisions: Long) : SearchRestart {
        init {
            require(decisions > 0) { "restart interval must be positive" }
        }
    }
}

/** Limits and restart policy for one invocation of [SearchSession.solve]. */
data class SearchSolveParams(
    /** Maximum shared decisions across all restart runs. */
    val maxDecisions: Long = Long.MAX_VALUE,
    /** Schedule used by the shared engine after decision boundaries. */
    val restart: SearchRestart = SearchRestart.Never,
) {
    init {
        require(maxDecisions >= 0) { "maximum decisions must not be negative" }
    }
}

/**
 * A [SearchComponent] whose state is theory-local rather than finite-domain state.
 *
 * This marker records the dependency rule for SMT theories: implementations may use the shared
 * decisions and explanations, but must not read or materialize CP domains. Difference logic, linear
 * arithmetic, EUF, arrays, and quantifier-instantiation engines all fit this lifecycle.
 */
interface TheoryComponent : SearchComponent

/** Shared state exposed to components without exposing finite CP domains. */
interface SearchContext {
    /** Current shared decision level. */
    val decisionLevel: Int

    /** Current value of Boolean variable [variable], or null when it is not assigned. */
    fun boolValue(variable: Int): Boolean?

    /** Tightest lower bound published for [variable], or null when no component has published one. */
    fun intLowerBound(variable: Int): Long?

    /** Tightest upper bound published for [variable], or null when no component has published one. */
    fun intUpperBound(variable: Int): Long?

    /**
     * Assert a Boolean consequence at the current shared level.
     *
     * [explanation] is retained by the shared session for conflict analysis and clause learning. A
     * component must not imply a literal without one.
     */
    fun imply(literal: Int, explanation: SearchExplanation): ComponentResult

    /**
     * Publish a Boolean consequence whose explanation remains owned by the producing component.
     *
     * The fact is visible to peers but cannot enter shared learning until that component exposes a
     * clause-form reason. This is the bridge for existing CP propagators during migration.
     */
    fun publish(literal: Int): ComponentResult

    /** Publish a non-Boolean consequence, such as a CP bound tightening, to peer components. */
    fun publish(decision: SearchDecision): ComponentResult

    /** Consume one complete component check from the solve-wide allowance. */
    fun consumeCheck(): Boolean

    /** True when the solve-wide cancellation token has fired. */
    fun cancelled(): Boolean
}

/** Mutable model assembly owned by [SearchSession], not by any individual component. */
interface SearchModel {
    /** Store one component-owned value under an identity-stable [key]. */
    fun put(key: Any, value: Any)
}

/**
 * A typed source-model location whose value can be contributed by exactly one search component.
 *
 * The standard scalar keys below cover the current fragments. The interface remains open so an array,
 * function, or quantified-theory component can define its own source-symbol key without extending the
 * shared engine or exposing private theory state.
 */
interface SearchValueKey

/** Source Boolean variable value. The session contributes assigned Boolean values itself. */
data class SearchBoolValue(
    /** Source Boolean variable id. */
    val variable: Int,
) : SearchValueKey

/** Source integer value. CP and integer theories contribute this without exchanging domain objects. */
data class SearchIntValue(
    /** Source integer variable id. */
    val variable: Int,
) : SearchValueKey

/** Source real value contributed by an exact or floating theory component. */
data class SearchRealValue(
    /** Source real variable id. */
    val variable: Int,
) : SearchValueKey

/** Immutable complete model assembled from all active components. */
class AssembledSearchModel internal constructor(private val values: Map<Any, Any>) {
    /** Return the value contributed by [key], or null when that component has no model value. */
    @Suppress("UNCHECKED_CAST")
    fun <A : Any> valueOf(key: Any): A? = values[key] as A?
}
