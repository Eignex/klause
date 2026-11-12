package com.eignex.klause.portfolio

import com.eignex.klause.solver.ProblemClass

/**
 * What an arm covers in a roster. Every catalog arm declares one role, [needed] names the roles a model calls
 * for, and [PortfolioRoster] seats the best eligible arm of each before it fills by rank. Roles decide who is
 * in the roster; [ArmFamily] decides who runs next.
 */
internal enum class ArmRole(
    /** The name the record reports. */
    val label: String,
) {
    /** Reaches a first feasible assignment. */
    FirstSolution("first-solution"),

    /** Complete search whose learned clauses carry the proof. */
    LearningProof("learning-proof"),

    /** Complete search bounded by the LP relaxation. */
    LpBounding("lp-bounding"),

    /** Raises the objective floor from unsatisfiable cores. */
    ObjectiveCores("objective-cores"),

    /** Complete search under a different variable or value order. */
    SelectorDiversity("selector-diversity"),

    /** Improves an incumbent inside a neighbourhood of it. */
    NeighbourhoodImprovement("neighbourhood-improvement"),
    ;

    /** The roles a model calls for. */
    companion object {
        /**
         * The roles the model behind [facts] needs, in seating order. A role named twice seats its two best
         * arms, and a role no eligible arm covers seats none. [annotated] says the model carries its own search
         * order, and [relaxation] that some arm can bound it with the LP.
         *
         * An optimization seats its complete arm first: a sequential pool warms arms in order, and local search
         * cannot close a trivial optimum. A satisfaction model seats local search first, whose first solution
         * ends the run. Pure integer rows pay for a second LP emphasis. A finite model over 0/1 integers, or one
         * without a relaxation, gets its diversity from the branching order.
         */
        fun needed(kind: Kind, facts: ProblemFacts, annotated: Boolean, relaxation: Boolean): List<ArmRole> =
            buildList {
                if (kind == Kind.COP) {
                    add(LearningProof)
                    add(FirstSolution)
                } else {
                    add(FirstSolution)
                    add(LearningProof)
                }
                add(LpBounding)
                if (kind == Kind.COP) {
                    add(NeighbourhoodImprovement)
                    add(ObjectiveCores)
                }
                if (annotated) add(SelectorDiversity)
                if (kind == Kind.COP && !facts.continuousObjective &&
                    (facts.integerLinear || facts.binaryEqualityObjective)
                ) {
                    add(LpBounding)
                }
                if (facts.profile.problemClass == ProblemClass.FiniteCp && (facts.binaryIntegers || !relaxation)) {
                    add(SelectorDiversity)
                }
            }
    }
}
