package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.DefinitionHint
import com.eignex.klause.ir.EffectiveSourceSemantics
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SearchHints
import com.eignex.klause.ir.SourceSemanticsOwner
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.solver.FactorProjectionProviders

/** Borrowed canonical source structure with owned invocation metadata. */
class SourceAttachment(
    problem: Problem,
    definitionHints: List<DefinitionHint> = emptyList(),
    searchHints: SearchHints? = null,
) : SourceSemanticsOwner {
    internal val problem: Problem = problem
    internal val providers: FactorProjectionProviders = (problem as? BakedProblem)?.projectionProviders
        ?: FactorProjectionProviders.Default
    private val definitions = definitionHints.toList()
    private val search = searchHints?.captured()

    /** Original source identity, borrowed read-only for every attached invocation. */
    val originalSource: SourceSemanticsOwner = problem

    /** Effective interpretation captured from the original source. */
    override val semantics: EffectiveSourceSemantics = problem.semantics

    /** Source orientations; their payloads retain the source's borrowed-read-only lifetime. */
    val definitionHints: List<DefinitionHint> get() = definitions.toList()

    /** Independent branching-intent containers in original source coordinates. */
    val searchHints: SearchHints? get() = search?.captured()
}

private fun SearchHints.captured(): SearchHints = copy(tiers = tiers.map { group ->
    group.copy(boolVars = group.boolVars.copyOf(), intVars = group.intVars.copyOf())
})
