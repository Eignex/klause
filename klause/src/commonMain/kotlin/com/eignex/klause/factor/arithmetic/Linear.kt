package com.eignex.klause.factor.arithmetic

import com.eignex.klause.factor.bool.internals.CoalescedTerms
import com.eignex.klause.factor.bool.internals.coalesceLinearTerms
import com.eignex.klause.factor.remapVars
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.NoInvariant
import com.eignex.klause.lp.LinearRow
import com.eignex.klause.lp.RelaxationBuilder
import com.eignex.klause.lp.Term
import com.eignex.klause.propagation.NoPropagator
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.FactorKind
import com.eignex.klause.solver.KeySink
import com.eignex.klause.solver.StructuralKey
import com.eignex.klause.solver.hashRemappedKey
import com.eignex.klause.solver.materializeKey
import com.eignex.klause.util.EmptyDoubleArray
import com.eignex.klause.util.EmptyIntArray

/**
 * `Σ coeffs(i) * intVars(i) ⟨op⟩ bound`. Payload at `intPayload(factorId)` is the current
 * weighted sum, kept in sync incrementally by [Invariant.applyIntSet]. Repair moves propose, for each
 * variable, the integer value that on its own would put the sum on the right side of `bound`,
 * clamped to the variable's domain. Terms pair [coeffs] with [vars]; the sum is compared by [op]
 * against [bound].
 */
class Linear private constructor(
    terms: CoalescedTerms,
    rawOp: LinearOp,
    rawBound: Long,
    realVarsIn: IntArray = EmptyIntArray,
    realCoeffsIn: DoubleArray = EmptyDoubleArray,
) :
    Factor,
    LinearRow {

    // Canonicalise inequalities to ≤ at construction — the LP/MIP convention the cut separators
    // (FlowCoverSeparator, knapsack cover) expect. A ≥ becomes ≤ by negating the coefficients and bound;
    // = / ≠ keep their orientation. So an inequality [Linear] always reports [op] `LE`, and a constraint
    // and its negation share a structural key (so the two dedup as one).
    val op: LinearOp = if (rawOp == LinearOp.GE) LinearOp.LE else rawOp
    override val bound: Long = if (rawOp == LinearOp.GE) -rawBound else rawBound
    val vars: IntArray = terms.vars
    val coeffs: LongArray =
        if (rawOp == LinearOp.GE) LongArray(terms.coeffs.size) { -terms.coeffs[it] } else terms.coeffs

    /**
     * LP-only continuous (real) variable terms, additional to the integer terms: real var ids in
     * [realVars] paired with double coefficients in [realCoeffs]. Empty for the integer/Boolean core.
     * When present the row `Σ coeffs(i)·vars(i) + Σ realCoeffs(j)·realVars(j) ⟨op⟩ bound` reasons over a
     * continuous variable, so it is absent from CP propagation ([asPropagator] is [NoPropagator]) and its
     * feasibility is enforced by the LP relaxation and the search leaf. A `>=` row negates these
     * coefficients alongside the integer ones.
     */
    val realVars: IntArray = realVarsIn
    val realCoeffs: DoubleArray =
        if (rawOp == LinearOp.GE) DoubleArray(realCoeffsIn.size) { -realCoeffsIn[it] } else realCoeffsIn

    /** Whether this row carries a continuous (real) term, making it an LP-only row (see [realVars]). */
    val hasReals: Boolean get() = realVars.isNotEmpty()

    init {
        require(coeffs.isNotEmpty() || realVars.isNotEmpty()) { "linear sum must have at least one term" }
        require(realVars.size == realCoeffs.size) { "real vars/coeffs length mismatch" }
    }

    override val intVars: IntArray = vars

    /**
     * `Σ coeffs(i) * vars(i) ⟨op⟩ bound`. Duplicate variables are coalesced (their coefficients
     * summed) so the local-search payload stays consistent regardless of caller.
     */
    constructor(coeffs: IntArray, vars: IntArray, op: LinearOp, bound: Int) :
        this(coalesceLinearTerms(vars, coeffs), op, bound.toLong())

    /** Wide form: coefficients and bound that may exceed 32-bit range (SMT cut lemmas, dense folds). */
    constructor(coeffs: LongArray, vars: IntArray, op: LinearOp, bound: Long) :
        this(coalesceLinearTerms(vars, coeffs), op, bound)

    /**
     * Mixed integer + LP-only-real form: `Σ intCoeffs(i)·intVars(i) + Σ realCoeffs(j)·realVars(j) ⟨op⟩
     * bound`. [realVars] are ids in the problem's real-variable namespace. A row with any real term is
     * LP-only — it does not propagate in CP (see [realVars]) — so integer-semantics consumers skip it.
     */
    constructor(
        intCoeffs: LongArray,
        intVars: IntArray,
        realCoeffs: DoubleArray,
        realVars: IntArray,
        op: LinearOp,
        bound: Long,
    ) : this(coalesceLinearTerms(intVars, intCoeffs), op, bound, realVars.copyOf(), realCoeffs.copyOf())

    override fun structuralKey(): StructuralKey = materializeKey(FactorKind.LINEAR, ::buildKey)

    // Allocation-free per-incidence key hash via the two-mode [KeySink] — symmetry refinement rebuilds
    // this once per incident variable each round. `pairsByVarKeyCoalescing` reproduces `remap()` (whose
    // constructor coalesces same-image terms) followed by the key sort, so the port hash stays equal to
    // `remap().structuralKey().hashCode()` even when the colouring map collapses two variables.
    override fun remapStructuralHash(boolMap: IntArray, intMap: IntArray): Int =
        hashRemappedKey(FactorKind.LINEAR, boolMap, intMap, ::buildKey)

    private fun buildKey(sink: KeySink) {
        sink.enum(op)
        sink.long(bound)
        sink.pairsByVarKeyCoalescing(vars) { coeffs[it] }
        // Distinguish rows that differ only in their continuous terms so symmetry never merges them.
        for (j in realVars.indices) {
            sink.long(realVars[j].toLong())
            sink.long(realCoeffs[j].toRawBits())
        }
    }

    override fun remap(boolMap: IntArray, intMap: IntArray): Factor =
        if (hasReals) {
            // Real var ids live in a separate namespace and are not remapped by [intMap]; the row was
            // already canonicalised (any `>=` negated) at construction, so re-emit as-is with the op that
            // reproduces the stored coefficients (LE / EQ / NE — a stored row is never GE).
            Linear(coeffs, vars.remapVars(intMap), realCoeffs, realVars, op, bound)
        } else {
            Linear(coeffs, vars.remapVars(intMap), op, bound)
        }

    /**
     * A pure binary value relation `c·x ⟨=|≠⟩ c·y` — two terms with opposite-equal coefficients and a
     * zero bound, comparing for equality or distinctness. Its allowed-tuple set (`{x = y}` / `{x ≠ y}`)
     * is invariant under *any* uniform relabeling of values, so it is value-anonymous. Every
     * other linear is value-meaningful: an ordering (`≤`/`≥`) is not relabeling-invariant, and a
     * nonzero bound or non-opposite coefficients tie the variables to specific magnitudes.
     */
    private fun isBinaryValueRelation(): Boolean = !hasReals &&
        (op == LinearOp.EQ || op == LinearOp.NE) && bound == 0L &&
        vars.size == 2 && coeffs[0] != 0L && coeffs[0] == -coeffs[1]

    override fun isValueAnonymous(): Boolean = isBinaryValueRelation()

    // A value-anonymous factor names no value as a constant, so a relabeling maps it to itself (#501).
    override fun remapValues(valueMap: (Long) -> Long): Factor? = if (isBinaryValueRelation()) this else null

    override val boolVars: IntArray = EmptyIntArray

    // A continuous row connects the objective through its integer terms via the LP double view, not the
    // integer objective cone; keep it out of the cone probe (which reasons over integer CORE rows only).
    override val extendsObjectiveCone: Boolean get() = !hasReals

    // A continuous row is LP-only: it does not propagate in CP ([NoPropagator], so the occurrence index
    // never wakes it) — its feasibility is enforced by the LP relaxation and the search leaf. The
    // local-search engine is gated off for problems with real variables, so its invariant is never
    // consulted; an inert one keeps the factory total without pretending to evaluate the real terms.
    override fun asPropagator(): Propagator =
        if (hasReals) NoPropagator else LinearPropagator(boolVars, intVars, coeffs, vars, op, bound)

    override fun asInvariant(): Invariant =
        if (hasReals) NoInvariant else LinearInvariant(coeffs, vars, op, bound)

    // The factor *is* its own exact linear row (integer terms), so presolve reads it with no extra
    // allocation. [linearize] emits the row over the factor's arrays directly rather than through the
    // interface accessors, keeping the per-node LP path allocation-free. A continuous row exposes no
    // integer LinearRow (its content is not integer-valued) and emits a real row instead.
    override val size: Int get() = vars.size
    override fun ref(k: Int): Int = Term.ofIntVar(vars[k])
    override fun coeff(k: Int): Long = coeffs[k]
    override val relation: LinearOp get() = op
    override val isIntegerOnly: Boolean get() = !hasReals
    override val linearRows: List<LinearRow> get() = if (hasReals) emptyList() else listOf(this)

    override fun linearize(builder: RelaxationBuilder, factorId: Int) {
        if (!hasReals) {
            builder.linearRow(op, vars, coeffs, bound)
            return
        }
        // Mixed integer + real row: map integer vars to their LP columns and real vars to their LP-only
        // continuous columns, and emit one double-precision row over the combined columns.
        val cols = IntArray(vars.size + realVars.size)
        val dcoeffs = DoubleArray(cols.size)
        for (i in vars.indices) {
            cols[i] = builder.intColumn(vars[i])
            dcoeffs[i] = coeffs[i].toDouble()
        }
        for (j in realVars.indices) {
            cols[vars.size + j] = builder.realColumn(realVars[j])
            dcoeffs[vars.size + j] = realCoeffs[j]
        }
        builder.realRow(cols, dcoeffs, op, bound.toDouble())
    }
}

/** True when every coefficient and the bound fit 32-bit range — the precondition for the Int-coefficient
 *  reasoning a consumer keeps (ReifiedLinear's big-M rows, GCD modulus fixing, coefficient strengthening). */
internal fun fitsInt32(coeffs: LongArray, bound: Long): Boolean =
    bound in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() &&
        coeffs.all { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }

/** True when every value in [values] fits 32-bit range. The value-symmetry relabel (`remapValues`) is
 *  `(Int)`-typed, so a value-carrying global (GCC cover, Table tuples, Mdd symbols, AllDifferent
 *  except-set) declines value symmetry (`null`) when wide, to avoid truncating two values into one. */
internal fun fitsInt32(values: LongArray): Boolean = values.all { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }
