package com.eignex.klause.ir

/**
 * Structural contract for a constraint in `Problem`: variable membership, remapping, and
 * structural identity. Payload arrays are borrowed read-only for the lifetime of every model using
 * the factor; custom implementations must keep membership and structural keys stable.
 * Engine-specific projections, relaxations included, are allocated by their engines from this immutable data.
 *
 * Variables touched by a factor split into two id spaces: Boolean vars in [boolVars] and
 * integer vars in [intVars]. Pure-Boolean factors leave [intVars] empty; pure-integer factors
 * leave [boolVars] empty; reified or mixed factors populate both.
 */
interface Factor {
    /**
     * The variables this factor reads and what it needs from them.
     *
     * The one place a factor states its variables. Its kind carries the demand: [IntVars] reasons over
     * bounds, [SpanIntVars] enumerates values, [MixedVars] composes the two per role. Real columns are
     * declared here like any other kind rather than riding along in a factor-specific payload.
     */
    val variables: VarList

    /** Boolean variables this factor constrains, as raw variable ids (0-based). Derived from [variables]. */
    val boolVars: IntArray get() = variables.boolVars

    /** Integer variables this factor constrains, as raw variable ids (0-based). Derived from [variables]. */
    val intVars: IntArray get() = variables.ints

    /**
     * A copy of this factor with every variable id renumbered through [mapping]; non-variable data is
     * carried over unchanged.
     *
     * There is no default: a renumbered variable can appear in any factor, so a silent miss would leave
     * stale ids. A factor that touches no variables returns `this`.
     */
    fun remap(mapping: VarRemap): Factor

    /**
     * A copy of this factor with integer variable [x] replaced by `scale·replacement + offset` at
     * **every** occurrence, or `null` when this factor cannot represent that substitution exactly.
     *
     * The default declines: substituting an affine expression is unsound for a factor that reasons over
     * the variable's value directly, so a factor opts in only for forms it can rewrite faithfully.
     */
    fun substituteAffine(
        @Suppress("UNUSED_PARAMETER") x: Int,
        @Suppress("UNUSED_PARAMETER") scale: Int,
        @Suppress("UNUSED_PARAMETER") offset: Int,
        @Suppress("UNUSED_PARAMETER") replacement: Int,
    ): Factor? = null

    /**
     * Whether [substituteAffine] with these arguments would return non-null. Candidate scans call this
     * repeatedly but rewrite only the accepted candidate, so a factor with an expensive rewrite
     * overrides it with a cheap test. The default runs [substituteAffine] and discards the result.
     */
    fun canSubstituteAffine(x: Int, scale: Int, offset: Int, replacement: Int): Boolean =
        substituteAffine(x, scale, offset, replacement) != null

    /**
     * A canonical [StructuralKey] identifying this constraint up to variable identity: same factor
     * type, same constants and the same multiset of variables produce equal keys, independent of
     * internal ordering. Symmetry detection compares them to test automorphisms.
     */
    fun structuralKey(): StructuralKey

    /**
     * `hashCode` of `remap(mapping).structuralKey()`, computed by symmetry refinement for every
     * variable–factor arc each round. A hot factor type overrides it to fold the hash without allocating
     * the remapped factor and key. Soundness never rests on it, since every candidate is re-checked by an
     * automorphism test, but an override should match the key hash so the symmetries found are unchanged.
     */
    fun remapStructuralHash(mapping: VarRemap): Int = remap(mapping).structuralKey().hashCode()

    /**
     * An estimate of the cost of building and hashing [structuralKey] once. Symmetry detection uses
     * `Σ degree·weight` to decide when refinement is too expensive. The default is the variable count;
     * factors with a large constant payload add its size.
     */
    val structuralKeyWeight: Int get() = intVars.size + boolVars.size

    /**
     * Whether this factor's meaning is invariant under any relabeling of domain values, as for
     * AllDifferent. A value permutation is a symmetry only if every factor is value-anonymous, so the
     * default `false` conservatively blocks value symmetry for the whole problem.
     */
    fun isValueAnonymous(): Boolean = false

    /**
     * A copy of this factor with every constant that names a domain value relabeled through [valueMap],
     * the value analog of [remap]. Value-symmetry detection compares the resulting [structuralKey]
     * multiset to verify a permutation.
     *
     * `null` (the default) means not relabelable: a value carries magnitude, or values live in more than
     * one universe. A `null` anywhere blocks value symmetry for the whole problem. An [isValueAnonymous]
     * factor returns `this`.
     */
    fun remapValues(valueMap: (Long) -> Long): Factor? = null

    /**
     * A structural self-reduction of this factor under the current integer [domains]: a rewrite into
     * simpler or lower-arity factors when its own structure (a fixed selector, a degenerate arity, a
     * constant table) pins it. [FactorReduction.Unchanged] (the default) means none applies.
     *
     * The contract is **solution-set exact**: the [FactorReduction.Rewrite.replacement] conjoined with
     * its [FactorReduction.Rewrite.tightenedBounds] must accept exactly the assignments this factor does.
     */
    fun structuralReduce(domains: Array<IntDomain>): FactorReduction = FactorReduction.Unchanged

    /**
     * The structural linear content of this factor, or `null` when none is declared.
     * Returned rows retain their identity across reads: engine caches attach derived data to each row.
     */
    val linearForm: LinearForm? get() = null
}

/**
 * The outcome of `Factor.structuralReduce`: either no reduction, or a solution-set-exact rewrite into
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
