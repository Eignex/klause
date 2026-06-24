package com.eignex.klause.solver

/** Shared singleton for the empty-int-var-set case. Factors with no variables in one of
 *  the two var spaces (purely-Boolean ones leave [Factor.intVars] empty; purely-integer
 *  ones leave [Factor.boolVars] empty) wire this in instead of allocating their own
 *  per-class empty array. */
internal val EmptyIntArray: IntArray = IntArray(0)

/** Shared singleton empty `LongArray`, for scratch slots that some code paths leave unused
 *  (e.g. the wide-only term-contribution snapshot in `propagateLinearBounds`) so the
 *  common path binds this instead of allocating. */
internal val EmptyLongArray: LongArray = LongArray(0)

/**
 * Structural contract for a constraint in [Problem]: variable membership, remapping, and
 * structural identity. The deductive half is [Propagator] (returned by [asPropagator]), the
 * local-search half is [Invariant] (returned by [asInvariant]), and the LP-relaxation half is
 * [Linearizer] (returned by [asLinearizer]); each is a separate object whose allocation is deferred
 * to when the corresponding engine is initialised.
 *
 * Variables touched by a factor split into two id spaces: Boolean vars in [boolVars] and
 * integer vars in [intVars]. Pure-Boolean factors leave [intVars] empty; pure-integer factors
 * leave [boolVars] empty; reified or mixed factors populate both.
 */
interface Factor {
    /** Boolean variables this factor constrains, as raw variable ids (0-based). */
    val boolVars: IntArray

    /** Integer variables this factor constrains, as raw variable ids (0-based). */
    val intVars: IntArray

    /**
     * A copy of this factor with every Boolean variable id rewritten through [boolMap] and every
     * integer variable id through [intMap] (`newId = map[oldId]`). Non-variable data — coefficients,
     * bounds, constant arrays, domain offsets, DFA tables — is carried over unchanged. Used by
     * presolve passes that renumber or substitute variables.
     *
     * Every factor must implement this (no default): a variable being renumbered or substituted can
     * appear in any factor, so a silent miss would leave stale ids in the rewritten problem. A
     * factor that genuinely touches no variables returns `this`.
     */
    fun remap(boolMap: IntArray, intMap: IntArray): Factor

    /**
     * A copy of this factor with integer variable [x] replaced by the affine expression
     * `scale·replacement + offset`, or `null` when this factor cannot represent that substitution
     * exactly. Lets affine variable elimination (`AffineSingletons`) project out an `x` defined by
     * `x = scale·y + offset` even when it appears in a non-linear global — provided the global can
     * absorb the affine view (e.g. an [com.eignex.klause.solver.factor.table.Element] index shift
     * folds into its offset). The default declines (`null`): substituting an affine expression for a
     * bare variable is unsound for a factor that reasons over the variable's value directly, so a
     * factor opts in only for forms it can rewrite faithfully. Must replace **every** occurrence of
     * [x] (or return `null`); the pure-rename case (`scale = 1, offset = 0`) is handled by [remap].
     */
    fun substituteAffine(
        @Suppress("UNUSED_PARAMETER") x: Int,
        @Suppress("UNUSED_PARAMETER") scale: Int,
        @Suppress("UNUSED_PARAMETER") offset: Int,
        @Suppress("UNUSED_PARAMETER") replacement: Int,
    ): Factor? = null

    /**
     * A canonical [StructuralKey] identifying this constraint up to variable identity: same factor
     * type, same constants (coefficients, bounds, polarities), and the same multiset of variables — in
     * a representation that does not depend on internal ordering — produce equal keys. Used by symmetry
     * detection to check whether permuting variables maps the factor set to itself (an automorphism).
     * Every factor type supplies one.
     */
    fun structuralKey(): StructuralKey

    /**
     * Whether this factor's meaning is invariant under *any* relabeling of domain values — i.e. it
     * treats values as interchangeable symbols (AllDifferent: distinctness ignores which values).
     * Used by value-symmetry detection: a value permutation is a symmetry only if every
     * factor is value-anonymous (and every variable's domain is invariant under it). Arithmetic and
     * value-meaningful constraints return `false` (the default), conservatively blocking value
     * symmetry for the whole problem.
     */
    fun isValueAnonymous(): Boolean = false

    /**
     * A copy of this factor with every *value-dependent constant* relabeled through [valueMap]
     * (`newValue = valueMap(oldValue)`) — the value analog of [remap]. Relabels things that
     * name domain values: an [com.eignex.klause.solver.factor.global.GlobalCardinality] cover, a
     * [com.eignex.klause.solver.factor.table.Table]'s tuples, an Element constant array, Regular/Mdd
     * symbols. Variable ids, coefficients, and structural positions are unchanged.
     *
     * Used by value-symmetry detection to *verify* that a value permutation maps the factor set to
     * itself: applying it to every factor and comparing the [structuralKey] multiset proves the
     * permutation is a symmetry, the value analog of the [remap]-based automorphism check.
     *
     * `null` (the default) means "not value-relabelable" — arithmetic / value-meaningful factors
     * ([com.eignex.klause.solver.factor.arithmetic.Linear], Product) where a value carries magnitude, not just
     * identity, and a factor whose values live in more than one universe (e.g. a GCC with count
     * *variables*) which can't be relabeled by a single map. A `null` anywhere conservatively blocks
     * value symmetry for the whole problem. A [isValueAnonymous] factor returns `this` (no constant
     * names a value).
     */
    fun remapValues(valueMap: (Int) -> Int): Factor? = null

    /**
     * A structural self-reduction of this factor under the current integer [domains]: a rewrite into
     * simpler / lower-arity factors when the factor's own structure (a fixed selector, a degenerate
     * arity, a constant table) pins it, which plain propagation cannot do — propagation filters the
     * domains, it never removes the constraint. [FactorReduction.Unchanged] (the default) means no
     * structural reduction applies.
     *
     * The contract is **solution-set exact**: the returned [FactorReduction.Rewrite.replacement]
     * (conjoined with its [FactorReduction.Rewrite.tightenedBounds]) must accept exactly the same
     * assignments as this factor — so the driving pass can stay solution-set-preserving. A factor that
     * needs a *relaxation* (a superset, for deduction harvesting) belongs to a different hook, not this
     * one.
     */
    fun structuralReduce(domains: Array<IntDomain>): FactorReduction = FactorReduction.Unchanged

    /** The [Propagator] the CP engine uses for this constraint. */
    fun asPropagator(): Propagator

    /** The [Invariant] the LS engine uses for this constraint. */
    fun asInvariant(): Invariant

    /** The [Linearizer] the LP engine uses for this constraint. Default: [NoLinearizer] (no relaxation). */
    fun asLinearizer(): Linearizer = NoLinearizer
}

/**
 * The outcome of [Factor.structuralReduce]: either no reduction, or a solution-set-exact rewrite into
 * a (possibly empty) list of replacement factors plus optional per-variable bound narrowings.
 */
sealed interface FactorReduction {
    /** No structural reduction applies — the factor is kept as-is. */
    object Unchanged : FactorReduction

    /**
     * Replace the factor with [replacement] (an empty list drops it as vacuous) and narrow each variable
     * in [tightenedBounds] to the given `min..max` range. The conjunction of the replacement and the
     * narrowings must be solution-set-equivalent to the original factor.
     */
    class Rewrite(val replacement: List<Factor>, val tightenedBounds: Map<Int, IntRange> = emptyMap()) :
        FactorReduction
}
