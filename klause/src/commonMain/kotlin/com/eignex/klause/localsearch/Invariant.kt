package com.eignex.klause.localsearch

/**
 * The local-search contract of a constraint: graded violation, move deltas, move application and move proposal.
 * Mutable payloads belong to [LocalSearchState]. Every method defaults to a sound no-op, so a propagation-only
 * factor implements nothing.
 */
interface Invariant {
    /** Builds this factor's payload from the current assignment; called once per restart. */
    fun initialize(state: LocalSearchState, factorId: Int) {}

    /** True iff this factor is violated under the current state. */
    fun isViolated(state: LocalSearchState, factorId: Int): Boolean = false

    /**
     * Graded violation: `0` when satisfied, otherwise a positive magnitude that grows with the distance from
     * satisfaction, so descent sees a gradient on arithmetic and global constraints. The default is `1` when
     * [isViolated], which is exact for inherently binary factors.
     *
     * Every `deltaIf*` must return exactly `violationDegree(after) - violationDegree(before)`, or candidates are
     * mis-scored. Degrees stay within `Int`; the engine sums them into a `Long` cost.
     */
    fun violationDegree(state: LocalSearchState, factorId: Int): Int = if (isViolated(state, factorId)) 1 else 0

    /** Change in [violationDegree] if [boolVar] were flipped, without mutating state. */
    fun deltaIfBoolFlipped(state: LocalSearchState, factorId: Int, boolVar: Int): Int = 0

    /** Δ violation-degree if [intVar] were set to [newValue], without mutating state. */
    fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Long): Int = 0

    /**
     * Updates this factor's payload after [boolVar] was flipped in the assignment, for committed moves and
     * reversible probes. The return value is implementation-specific; read [violationDegree] for the exact degree.
     */
    fun applyBoolFlip(state: LocalSearchState, factorId: Int, boolVar: Int): Int = 0

    /** Update payloads after setting [intVar] from [oldValue]. Return semantics match [applyBoolFlip]. */
    fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Long): Int = 0

    /** Δ violation-degree if real variable [realVar] were set to [newValue], without mutating state. */
    fun deltaIfRealSet(state: LocalSearchState, factorId: Int, realVar: Int, newValue: Double): Int = 0

    /** Update payloads after setting [realVar] from [oldValue]. Return semantics match [applyBoolFlip]. */
    fun applyRealSet(state: LocalSearchState, factorId: Int, realVar: Int, oldValue: Double): Int = 0

    /** Suggests moves that may repair this factor; the default flips each Boolean and steps each int to a neighbour. */
    fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        for (b in state.problem.factors[factorId].boolVars) sink.addBoolFlip(b)
        for (i in state.problem.factors[factorId].intVars) {
            val cur = state.assignment.intValue(i)
            val d = state.rootDomains[i]
            if (cur < d.max) sink.addChannelingIntSet(state, i, d.higher(cur))
            if (cur > d.min) sink.addChannelingIntSet(state, i, d.lower(cur))
        }
    }

    /**
     * Suggests moves that keep this satisfied factor satisfied, for objective descent; the engine scores them
     * against the objective. Called only when the factor is not violated.
     */
    fun proposeStructuredMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
    }

    /**
     * Costlier feasibility-preserving moves, such as a circuit 2-opt reversal, drawn only by the opt-in
     * extended-structured source.
     */
    fun proposeExtendedStructuredMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
    }

    /** Costlier repairs, such as a DP-optimal accepting run, drawn only by the opt-in extended-repair source. */
    fun proposeExtendedRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
    }

    /**
     * Appends the sibling updates that keep this factor consistent when [intVar] moves from [oldValue] to
     * [newValue], so a value-driven channeling move becomes one atomic [Move.Compound]. Contributors skip vars that
     * [ChannelingSink.isPinned] reports and vars frozen by [LocalSearchState.assumptions].
     */
    fun contributeChanneling(
        state: LocalSearchState,
        factorId: Int,
        intVar: Int,
        oldValue: Long,
        newValue: Long,
        sink: ChannelingSink,
    ) {}

    /**
     * True iff [proposeStructuredMoves] relocates values within the scope while keeping this global satisfied. The
     * engine then seeds it feasible and draws those moves even while infeasible, so it clears violations in coupled
     * constraints without breaking itself. Moves that only make sense at feasibility do not qualify.
     */
    val providesImplicitNeighbourhood: Boolean get() = false

    /**
     * Overwrites this factor's own variables with a satisfying configuration, leaving vars frozen by
     * [LocalSearchState.assumptions] untouched. False when it cannot seed feasibly; those vars then keep their
     * random values.
     */
    fun seedFeasible(state: LocalSearchState, factorId: Int): Boolean = false

    /** True iff [updateBoolBreakMakeForFlip] maintains this factor's share of [LocalSearchState.boolBreakCount] and
     *  [LocalSearchState.boolMakeCount], replacing the engine's O(arity²) recount per flip. */
    val maintainsBreakMakeIncrementally: Boolean get() = false

    /** Adjusts the break and make counts after [flippedVar] flipped and this factor's [applyBoolFlip] ran. The net
     *  change must equal the engine's recount. */
    fun updateBoolBreakMakeForFlip(state: LocalSearchState, factorId: Int, flippedVar: Int) {}

    /** Int-set counterpart of [maintainsBreakMakeIncrementally], using [updateIntBreakMakeForIntSet]. */
    val maintainsIntBreakMakeIncrementallyForIntSet: Boolean get() = false

    /** Adjusts the break and make counts after [intVar] moved from [oldValue] and this factor's [applyIntSet] ran.
     *  The net change must equal the engine's recount. */
    fun updateIntBreakMakeForIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Long) {}
}

/** The absence of a local-search role: the factor only propagates and is dropped from local-search occurrence lists. */
object NoInvariant : Invariant
