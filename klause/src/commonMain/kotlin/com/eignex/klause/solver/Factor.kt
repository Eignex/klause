package com.eignex.klause.solver

/** Shared singleton for the empty-int-var-set case. Factors with no variables in one of
 *  the two var spaces (purely-Boolean ones leave [Factor.intVars] empty; purely-integer
 *  ones leave [Factor.boolVars] empty) wire this in instead of allocating their own
 *  per-class empty array. */
internal val EmptyIntArray: IntArray = IntArray(0)

/** Shared singleton empty `LongArray`, for scratch slots that some code paths leave unused
 *  (e.g. the wide-only term-contribution snapshot in `propagateLinearBounds`)
 *  so the common path binds this instead of allocating. */
internal val EmptyLongArray: LongArray = LongArray(0)

/**
 * Full constraint contract for [Problem]: the deductive half ([Propagator]), the local-search
 * half ([Invariant]), and the presolve/symmetry concern below.
 *
 * Variables touched by a factor split into two id spaces: Boolean vars in [boolVars] and
 * integer vars in [intVars]. Pure-Boolean factors leave [intVars] empty; pure-integer factors
 * leave [boolVars] empty; reified or mixed factors populate both.
 *
 * Both halves default to a sound no-op, so a factor that only propagates (no LS support) just
 * inherits the LS defaults — it reports always-satisfied with zero deltas — and a pure-LS
 * factor leaves [propagate] at its no-op.
 */
interface Factor :
    Propagator,
    Invariant {

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
     * A canonical string identifying this constraint up to variable identity: same factor type,
     * same constants (coefficients, bounds, polarities), and the same multiset of variables — in a
     * representation that does not depend on internal ordering — produce the same key. Used by
     * symmetry detection to check whether permuting variables maps the factor set to itself
     * (an automorphism). `null` (the default) means "not keyed"; verification falls back to the
     * conservative same-factor-set heuristic when any factor in the problem is unkeyed.
     */
    fun structuralKey(): String? = null

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
}
