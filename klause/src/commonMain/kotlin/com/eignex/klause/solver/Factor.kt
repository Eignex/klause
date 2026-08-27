package com.eignex.klause.solver

import com.eignex.klause.ir.IntDomain

import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.lp.HullFamily
import com.eignex.klause.lp.HullFlags
import com.eignex.klause.lp.LinearRow
import com.eignex.klause.lp.LpSizeEstimate
import com.eignex.klause.lp.RelaxationBuilder
import com.eignex.klause.lp.Term
import com.eignex.klause.propagation.Propagator

/**
 * Structural contract for a constraint in [Problem]: variable membership, remapping, and
 * structural identity. The deductive half is [Propagator] (returned by [asPropagator]) and the
 * local-search half is [Invariant] (returned by [asInvariant]); each is a separate object whose
 * allocation is deferred to when the corresponding engine is initialised, because it carries the
 * engine's per-constraint state and precomputed structures. The LP-relaxation half needs no such
 * state, so it is emitted directly by [linearize] rather than through a factory object.
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
     * Whether an integer-linear theory can hold this factor whole, so CP need not own its columns.
     *
     * Declared by the factor, which knows its own shape, rather than decided by the plan naming classes.
     * The default is `false`: a factor that has not said a theory can take it is held by CP, which is the
     * conservative direction — CP can always index a finite column, while a theory handed a constraint it
     * cannot represent would simply not see it.
     */
    val integerTheoryOwnable: Boolean get() = false

    /**
     * Whether the exact rational lane can hold this factor whole, once the model has continuous columns.
     *
     * Separate from [integerTheoryOwnable] because it is a question about the factor's *data*, not only its
     * shape: a row whose coefficients are not exactly representable is not exact whatever its kind.
     */
    val exactTheoryOwnable: Boolean get() = false

    /**
     * A copy of this factor with every variable id renumbered through [mapping]. Non-variable data — coefficients,
     * bounds, constant arrays, domain offsets, DFA tables — is carried over unchanged. Used by
     * presolve passes that renumber or substitute variables.
     *
     * Every factor must implement this (no default): a variable being renumbered or substituted can
     * appear in any factor, so a silent miss would leave stale ids in the rewritten problem. A
     * factor that genuinely touches no variables returns `this`.
     */
    fun remap(mapping: VarRemap): Factor

    /**
     * A copy of this factor with integer variable [x] replaced by the affine expression
     * `scale·replacement + offset`, or `null` when this factor cannot represent that substitution
     * exactly. Lets affine variable elimination (`AffineSingletons`) project out an `x` defined by
     * `x = scale·y + offset` even when it appears in a non-linear global — provided the global can
     * absorb the affine view (e.g. an [com.eignex.klause.factor.table.Element] index shift
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
     * Whether [substituteAffine] with these arguments would succeed (return non-null), decided without
     * materialising the rewritten factor. Affine elimination's candidate scan tests the substitutability
     * of a candidate's other occurrences repeatedly, but only the *accepted* candidate is ever rewritten;
     * a factor whose [substituteAffine] is expensive (a [com.eignex.klause.factor.table.Table] rewrites
     * every tuple) overrides this with a cheap early-exit test so the scan does not pay the full rewrite
     * per check. The default runs [substituteAffine] and discards the result, so it agrees by construction.
     */
    fun canSubstituteAffine(x: Int, scale: Int, offset: Int, replacement: Int): Boolean =
        substituteAffine(x, scale, offset, replacement) != null

    /**
     * A canonical [StructuralKey] identifying this constraint up to variable identity: same factor
     * type, same constants (coefficients, bounds, polarities), and the same multiset of variables — in
     * a representation that does not depend on internal ordering — produce equal keys. Used by symmetry
     * detection to check whether permuting variables maps the factor set to itself (an automorphism).
     * Every factor type supplies one.
     */
    fun structuralKey(): StructuralKey

    /**
     * `hashCode` of `remap(mapping).structuralKey()`, the per-incidence port signature symmetry
     * refinement computes for every variable–factor arc each round. The default builds the remapped
     * factor and its key; a hot factor type overrides to fold the same hash directly from its remapped
     * variables, skipping the intermediate [Factor] and [StructuralKey] allocations. An override must
     * return a value that *discriminates* ports as well as the key's hash — soundness never rests on it
     * (every candidate is re-checked by an automorphism test), so a collision only coarsens the
     * colouring, but the result should match the key hash to keep the colouring (and the symmetries
     * found) unchanged.
     */
    fun remapStructuralHash(mapping: VarRemap): Int = remap(mapping).structuralKey().hashCode()

    /**
     * An estimate of [structuralKey]'s size — the cost of building and hashing it once. Symmetry
     * detection's colour refinement rebuilds a factor's key once per incident variable each round, so a
     * factor with a large constant payload (a [com.eignex.klause.factor.table.Table]'s tuple set,
     * an Element constant array) costs `Θ(weight)` per such rebuild; the search uses `Σ degree·weight`
     * to decide when refinement is too expensive to attempt. The default — the variable count — fits
     * factors whose key is just their variables; data-heavy factors override to add their constant size.
     */
    val structuralKeyWeight: Int get() = intVars.size + boolVars.size

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
     * Whether this factor extends the LP **objective cone**: it emits feasibility-defining
     * (CORE) linear or Boolean rows that connect its variables, so the minimal linear+Boolean
     * sub-relaxation grows through it. Big-M reified factors (whose rows are dropped in cone mode) and
     * hard globals (which contribute no CORE rows there) return the default `false`, keeping them out
     * of the cone. Read only when building the cone relaxation, to decide membership without matching
     * the concrete factor type.
     */
    val extendsObjectiveCone: Boolean get() = false

    /**
     * A copy of this factor with every *value-dependent constant* relabeled through [valueMap]
     * (`newValue = valueMap(oldValue)`) — the value analog of [remap]. Relabels things that
     * name domain values: an [com.eignex.klause.factor.global.GlobalCardinality] cover, a
     * [com.eignex.klause.factor.table.Table]'s tuples, an Element constant array, Regular/Mdd
     * symbols. Variable ids, coefficients, and structural positions are unchanged.
     *
     * Used by value-symmetry detection to *verify* that a value permutation maps the factor set to
     * itself: applying it to every factor and comparing the [structuralKey] multiset proves the
     * permutation is a symmetry, the value analog of the [remap]-based automorphism check.
     *
     * `null` (the default) means "not value-relabelable" — arithmetic / value-meaningful factors
     * ([com.eignex.klause.factor.arithmetic.Linear], Product) where a value carries magnitude, not just
     * identity, and a factor whose values live in more than one universe (e.g. a GCC with count
     * *variables*) which can't be relabeled by a single map. A `null` anywhere conservatively blocks
     * value symmetry for the whole problem. A [isValueAnonymous] factor returns `this` (no constant
     * names a value).
     */
    fun remapValues(valueMap: (Long) -> Long): Factor? = null

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

    /**
     * This factor's constraint as one or more **solution-set-exact** [LinearRow]s over its integer
     * variables and Boolean literals, or the empty list (the default) when the factor has no exact
     * linear form. "Exact" means the conjunction of the returned rows accepts exactly the assignments
     * this factor accepts — it *is* the constraint, not a relaxation. A factor whose only linear form is
     * a relaxation (big-M, convex hull) leaves this empty and exposes that through [linearize] instead.
     *
     * Lets presolve analyses (redundancy, domination) read the linear content of any factor
     * uniformly instead of pattern-matching the concrete factor type. Read-only: it carries no
     * write-back, so passes that *rewrite* a factor still go through [structuralReduce] /
     * [substituteAffine]. Cache it in the override (the content is immutable) rather than rebuilding
     * per call.
     */
    val linearRows: List<LinearRow> get() = emptyList()

    /** The [Propagator] the CP engine uses for this constraint. */
    fun asPropagator(): Propagator

    /** The [Invariant] the LS engine uses for this constraint. */
    fun asInvariant(): Invariant

    /**
     * Emit this factor's LP-relaxation rows, columns, and auxiliary variables into [builder] — the
     * LP-engine analogue of [asPropagator] / [asInvariant], but a stateless emitter rather than a
     * factory object. The driver calls it once per relaxation build, passing the factor's index in
     * [Problem.factors] as [factorId]. A single pass may mix [com.eignex.klause.lp.Contribution.CORE]
     * and [com.eignex.klause.lp.Contribution.HULL] rows — the kind is chosen per row at emit time.
     *
     * An exact linear row *is* the tightest valid relaxation, so the default emits the factor's
     * [linearRows] when it exposes any; a factor whose only linear form is a relaxation (big-M, convex
     * hull) leaves [linearRows] empty and overrides this to emit that relaxation. Default when neither
     * applies: nothing (no relaxation).
     */
    fun linearize(builder: RelaxationBuilder, factorId: Int) {
        for (row in linearRows) emitExactRow(builder, row)
    }

    /**
     * An upper-bound estimate of the LP columns and rows this factor's convex-hull contribution would
     * add under the declared [domains], or `null` when it contributes no sized hull — over its size cap,
     * no applicable structure, or no hull at all. The LP auto-config sums these to keep the per-node
     * tableau under budget, so the estimate must track [linearize]'s own caps and structure. Default: `null`.
     */
    fun lpSizeEstimate(@Suppress("UNUSED_PARAMETER") domains: Array<IntDomain>): LpSizeEstimate? = null

    /**
     * The gated convex-hull family this factor's [linearize] contributes to, or `null` (default) when it
     * has no gated hull. Named once here and used both by [hullFamilyEnabled] (the relaxation driver's
     * per-build gate) and by the LP auto-config (which groups factors by family), so neither place
     * pattern-matches the concrete factor type.
     */
    val hullFamily: HullFamily? get() = null

    /**
     * Whether this factor's convex-hull family is switched on by the relaxation's [flags] for this
     * build. Derived from [hullFamily]: a hull-emitting factor's family flag, else `true` (a factor with
     * no gated hull emits no [com.eignex.klause.lp.Contribution.HULL] rows, so the flag never applies).
     * The driver combines this with the build-level cone and per-factor suppression gates and exposes the
     * result through [RelaxationBuilder.hullEnabled], which [linearize] consults before allocating its
     * hull columns and rows.
     */
    fun hullFamilyEnabled(flags: HullFlags): Boolean = hullFamily?.let(flags::enabled) ?: true
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

/**
 * Emit one exact [LinearRow] into [builder]. A pure integer row goes through
 * [RelaxationBuilder.linearRow] verbatim; a row carrying Boolean literals is folded to mixed columns,
 * each negative literal's `coeff · (1 − x)` moving its constant to the right-hand side. Used by the
 * default [Factor.linearize] so a factor exposing exact rows needs no bespoke relaxation code.
 */
private fun emitExactRow(builder: RelaxationBuilder, row: LinearRow) {
    val n = row.size
    val columns = IntArray(n)
    val coeffs = LongArray(n)
    var rhs = row.bound
    for (k in 0 until n) {
        val ref = row.ref(k)
        val c = row.coeff(k)
        if (Term.isBool(ref)) {
            val lit = Term.lit(ref)
            columns[k] = builder.boolColumn(Lit.variable(lit))
            if (Lit.isPositive(lit)) {
                coeffs[k] = c
            } else {
                coeffs[k] = -c
                rhs -= c
            }
        } else {
            columns[k] = builder.intColumn(Term.intVar(ref))
            coeffs[k] = c
        }
    }
    builder.row(columns, coeffs, row.relation, rhs)
}
