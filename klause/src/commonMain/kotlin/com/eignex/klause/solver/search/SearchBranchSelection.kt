package com.eignex.klause.solver.search

/** A branch proposal's search domain, independent of deductive installation order. */
enum class SearchBranchKind {
    /** Source Boolean choices. */
    Boolean,
    /** Finite-domain splits. */
    Finite,
    /** Exact arithmetic splits. */
    Arithmetic,
}

/** Supplies an exhaustive split without participating in the deductive component lifecycle. */
interface SearchBranchProposal {
    /** Proposal category; reading it must not run a probe or deduction. */
    val branchKind: SearchBranchKind

    /** Mutually exclusive exhaustive alternatives, or null when no residual split is available. */
    fun nextBranch(context: SearchContext): List<SearchDecision>?
}

/** Lazy access to the proposals available at one traversal node. */
interface SearchBranchSources {
    /** Consult the supplied Boolean heuristic. */
    fun booleanAlternatives(): List<SearchDecision>?

    /** Consult installed and separate proposals in their retained order, optionally within one category. */
    fun componentAlternatives(kind: SearchBranchKind? = null): List<SearchDecision>?
}

/** Selects one exhaustive proposal without changing deduction, installation or traversal semantics. */
interface SearchBranchSelection {
    /**
     * Request only as many proposals as needed to choose the next split, returning the selected list unchanged.
     * Return null only after all available sources decline; selection must not discard residual alternatives.
     */
    fun alternatives(sources: SearchBranchSources): List<SearchDecision>?

    /** Boolean heuristic first, then installed components and separate proposals in their retained order. */
    data object Default : SearchBranchSelection {
        override fun alternatives(sources: SearchBranchSources): List<SearchDecision>? =
            sources.booleanAlternatives() ?: sources.componentAlternatives()
    }

    /** Explicit exhaustive category priority, retaining each category's existing proposal order. */
    class Priority(order: List<SearchBranchKind>) : SearchBranchSelection {
        private val order = order.toList()

        init {
            require(this.order.size == SearchBranchKind.entries.size &&
                this.order.toSet() == SearchBranchKind.entries.toSet()) { "priority must contain every category once" }
        }

        override fun alternatives(sources: SearchBranchSources): List<SearchDecision>? {
            for (kind in order) {
                if (kind == SearchBranchKind.Boolean) sources.booleanAlternatives()?.let { return it }
                sources.componentAlternatives(kind)?.let { return it }
            }
            return null
        }
    }
}
