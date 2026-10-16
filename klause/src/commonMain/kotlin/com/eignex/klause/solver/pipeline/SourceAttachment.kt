package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.BoolFoldDefinition
import com.eignex.klause.ir.DefinitionHint
import com.eignex.klause.ir.EffectiveSourceSemantics
import com.eignex.klause.ir.LinearSourceProblem
import com.eignex.klause.ir.NonlinearIntegerProblem
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.PolynomialRealProblem
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.ProblemSettings
import com.eignex.klause.ir.SearchHints
import com.eignex.klause.ir.SourceSemanticsOwner
import com.eignex.klause.ir.TranscendentalProblem
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.solver.FactorProjectionProviders

/** Borrowed canonical source structure with owned invocation metadata. */
class SourceAttachment private constructor(
    private val factorSource: Problem?,
    internal val formula: AttachedSourceFormula?,
    definitionHints: List<DefinitionHint>,
    searchHints: SearchHints?,
    /** Immutable source storage and preparation policy captured by the caller. */
    val settings: ProblemSettings,
    internal val definitionReferences: SourceDefinitionReferences? = null,
) : SourceSemanticsOwner {
    /** Attach the canonical factor model and its neutral construction hints. */
    constructor(
        problem: Problem,
        definitionHints: List<DefinitionHint> = emptyList(),
        searchHints: SearchHints? = null,
    ) : this(problem, null, definitionHints, searchHints, problem.settings)

    /** Attach factor-backed integer orientations and Boolean folds for lazy heuristic construction. */
    constructor(
        problem: Problem,
        definedVars: IntArray,
        boolFolds: List<BoolFoldDefinition>,
        definitionHints: List<DefinitionHint> = emptyList(),
        searchHints: SearchHints? = null,
    ) : this(
        problem, null, definitionHints, searchHints, problem.settings,
        SourceDefinitionReferences(definedVars, boolFolds),
    )

    /** Attach original rational linear rows and their captured source-unit interpretation. */
    constructor(problem: LinearSourceProblem, settings: ProblemSettings) :
        this(null, AttachedSourceFormula.Linear(problem), emptyList(), null, settings)

    /** Attach an exact real formula without materializing a finite source model. */
    constructor(problem: PolynomialRealProblem, settings: ProblemSettings) :
        this(null, AttachedSourceFormula.Polynomial(problem), emptyList(), null, settings)

    /** Attach an exact mixed polynomial formula, retaining unrestricted integer declarations. */
    constructor(problem: NonlinearIntegerProblem, settings: ProblemSettings) :
        this(null, AttachedSourceFormula.IntegerPolynomial(problem), emptyList(), null, settings)

    /** Attach exact real functions and their original domains and declarations. */
    constructor(problem: TranscendentalProblem, settings: ProblemSettings) :
        this(null, AttachedSourceFormula.Functions(problem), emptyList(), null, settings)

    internal val problem: Problem get() = requireNotNull(factorSource) { "source attachment contains a native formula" }
    internal val providers: FactorProjectionProviders = (factorSource as? BakedProblem)?.projectionProviders
        ?: FactorProjectionProviders.Default
    private val definitions = definitionHints.toList()
    private val search = searchHints?.captured()

    /** Original source identity, borrowed read-only for every attached invocation. */
    val originalSource: SourceSemanticsOwner = factorSource ?: requireNotNull(formula).source

    /** Effective interpretation captured from the original source. */
    override val semantics: EffectiveSourceSemantics = originalSource.semantics

    /** Embedded source objective direction, when the formula itself retains an objective. */
    val embeddedObjectiveSense: ObjectiveSense? = when (formula) {
        is AttachedSourceFormula.Linear -> formula.problem.objective?.sense
        is AttachedSourceFormula.Functions -> formula.problem.takeIf { it.hasObjective }?.objectiveSense
        else -> null
    }

    /** Source orientations; their payloads retain the source's borrowed-read-only lifetime. */
    val definitionHints: List<DefinitionHint> get() = definitions.toList()

    /** Independent branching-intent containers in original source coordinates. */
    val searchHints: SearchHints? get() = search?.captured()
}

private fun SearchHints.captured(): SearchHints = copy(
    tiers = tiers.map { group ->
        group.copy(boolVars = group.boolVars.copyOf(), intVars = group.intVars.copyOf())
    },
)
