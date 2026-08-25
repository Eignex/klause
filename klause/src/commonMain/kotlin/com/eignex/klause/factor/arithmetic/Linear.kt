package com.eignex.klause.factor.arithmetic

import com.eignex.klause.factor.bool.internals.CoalescedTerms
import com.eignex.klause.factor.bool.internals.coalesceLinearTerms
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.NoInvariant
import com.eignex.klause.lp.LinearRow
import com.eignex.klause.lp.RelaxationBuilder
import com.eignex.klause.propagation.NoPropagator
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.FactorKind
import com.eignex.klause.solver.IntVars
import com.eignex.klause.solver.KeySink
import com.eignex.klause.solver.MixedVars
import com.eignex.klause.solver.RealConsts
import com.eignex.klause.solver.StructuralKey
import com.eignex.klause.solver.VarList
import com.eignex.klause.solver.VarRemap
import com.eignex.klause.solver.WideConsts
import com.eignex.klause.solver.constsOf
import com.eignex.klause.solver.hashRemappedKey
import com.eignex.klause.solver.materializeKey
import com.eignex.klause.util.EmptyDoubleArray
import com.eignex.klause.util.EmptyIntArray
import com.eignex.klause.util.EmptyLongArray
import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.math.nextDown
import kotlin.math.nextUp

/**
 * `Σ coeffs(i) * intVars(i) ⟨op⟩ bound`. Payload at `intPayload(factorId)` is the current
 * weighted sum, kept in sync incrementally by [Invariant.applyIntSet]. Repair moves propose, for each
 * variable, the integer value that on its own would put the sum on the right side of `bound`,
 * clamped to the variable's domain. Terms pair [vars] with the coefficients in [constants]; the sum is
 * compared by [op] against that shape's bound.
 */
class Linear private constructor(
    terms: CoalescedTerms,
    rawOp: LinearOp,
    rawBound: Long,
    realVarsIn: IntArray = EmptyIntArray,
    realCoeffsIn: DoubleArray = EmptyDoubleArray,
    // Double forms used only by an LP-only (real-bearing) row, where coefficients and the bound need not
    // be integral: the double coefficient of each integer term (index-aligned with [vars]) and the double
    // right-hand side. The [Long] `coeffs`/`bound` are then rounded placeholders the never-read integer
    // consumers keep well-formed. Empty / 0 for the integer core.
    intCoeffsRealIn: DoubleArray = EmptyDoubleArray,
    realBoundIn: Double = 0.0,
    strictRealIn: Boolean = false,
    // Over-64-bit integer coefficients and bound, carried exactly. Null for the integer core and for real
    // rows.
    wideCoeffsIn: Array<BigInteger>? = null,
    wideBoundIn: BigInteger? = null,
) : Factor {

    // Canonicalise inequalities to ≤ at construction — the LP/MIP convention the cut separators
    // (FlowCoverSeparator, knapsack cover) expect. A ≥ becomes ≤ by negating the coefficients and bound;
    // = / ≠ keep their orientation. So an inequality [Linear] always reports [op] `LE`, and a constraint
    // and its negation share a structural key (so the two dedup as one).
    val op: LinearOp = if (rawOp == LinearOp.GE) LinearOp.LE else rawOp
    val vars: IntArray = terms.vars

    /**
     * The row's coefficients and right-hand side, at the width that holds them exactly. Their shape is the
     * row's shape: a consumer narrows to [integerConstants], [wideConstants] or [realConstants] and reads
     * exact values, so no reading is a stand-in for constants that do not fit it.
     *
     * The GE→LE canonicalisation negates the constants here, once.
     */
    val constants: LinearConstants = run {
        val negate = rawOp == LinearOp.GE
        val canonicalBound = if (negate) -rawBound else rawBound
        when {
            wideCoeffsIn != null -> {
                val wide = WideConsts(wideCoeffsIn)
                WideConstants(if (negate) wide.negated() else wide, if (negate) -wideBoundIn!! else wideBoundIn!!)
            }

            realVarsIn.isNotEmpty() -> {
                val intCoeffs = RealConsts(intCoeffsRealIn)
                val realCoeffs = RealConsts(realCoeffsIn)
                RealConstants(
                    intCoefficients = if (negate) intCoeffs.negated() else intCoeffs,
                    realCoefficients = if (negate) realCoeffs.negated() else realCoeffs,
                    bound = if (negate) -realBoundIn else realBoundIn,
                    strict = strictRealIn,
                )
            }

            else -> {
                val integerCoeffs = constsOf(terms.coeffs)
                IntegerConstants(
                    vars,
                    if (negate) integerCoeffs.negated() else integerCoeffs,
                    op,
                    canonicalBound,
                )
            }
        }
    }

    /** The row read as plain 64-bit integer arithmetic — its terms, coefficients and bound — or `null`
     *  when its constants are wider than that ([wideConstants], [realConstants]). */
    val integerConstants: IntegerConstants? get() = constants as? IntegerConstants

    /** The row read as exact integer arithmetic of any width, or `null` when it carries a continuous
     *  constant ([realConstants]). */
    val integralConstants: IntegralConstants? get() = constants as? IntegralConstants

    /** The row's over-64-bit constants, or `null` when it is not one. */
    val wideConstants: WideConstants? get() = constants as? WideConstants

    /** The row's continuous constants, or `null` when it is not an LP-only row. */
    val realConstants: RealConstants? get() = constants as? RealConstants

    /**
     * LP-only continuous (real) variable terms, additional to the integer terms: real var ids paired with
     * the double coefficients in [RealConstants.realCoefficients]. Empty for the integer/Boolean core. When
     * present the row reasons over a continuous variable, so it is absent from CP propagation
     * ([asPropagator] is [NoPropagator]) and its feasibility is enforced by the LP relaxation and the
     * search leaf.
     */
    val realVars: IntArray = realVarsIn

    init {
        require(vars.isNotEmpty() || realVars.isNotEmpty()) { "linear sum must have at least one term" }
        val real = realConstants
        require(real == null || real.realCoefficients.size == realVars.size) { "real vars/coeffs length mismatch" }
        require(real == null || real.intCoefficients.size == vars.size) { "real int-coeff/var length mismatch" }
        require(real == null || !real.strict || op == LinearOp.LE) { "strictness needs an LP-only inequality row" }
        val wide = wideConstants
        require(wide == null || wide.coefficients.size == vars.size) { "wide coeff/var length mismatch" }
        require(wide == null || realVars.isEmpty()) { "a row cannot be both wide and real" }
        // A wide or real form passes empty integer terms for the shape it does not use. Reaching the
        // integer shape with them would read those placeholders as the row's own coefficients, so an
        // integer row states that its coefficients are its own.
        val integer = integerConstants
        require(integer == null || integer.coefficients.size == vars.size) { "int coeff/var length mismatch" }
    }

    // Real columns are declared here like any other kind. They were reachable only through the LP
    // payload before, so no consumer scanning a factor's variables could see them.

    override val variables: VarList = if (realVars.isEmpty()) {
        IntVars(vars)
    } else {
        MixedVars(boundInts = vars, reals = realVars)
    }

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
     * Over-64-bit integer form: coefficients and/or bound beyond the [Long] range, carried exactly as
     * [BigInteger]. The row propagates via [WideLinearPropagator] and is excluded from the LP relaxation;
     * [vars] must be distinct — this form does not coalesce duplicates.
     */
    constructor(vars: IntArray, wideCoeffs: Array<BigInteger>, op: LinearOp, wideBound: BigInteger) : this(
        CoalescedTerms(vars.copyOf(), EmptyLongArray),
        op,
        0L,
        wideCoeffsIn = wideCoeffs.copyOf(),
        wideBoundIn = wideBound,
    )

    /**
     * Mixed integer + LP-only-real form with integer integer-side data: `Σ intCoeffs(i)·intVars(i) +
     * Σ realCoeffs(j)·realVars(j) ⟨op⟩ bound`. [realVars] are ids in the problem's real-variable
     * namespace. A row with any real term is LP-only — it does not propagate in CP (see [realVars]) — so
     * integer-semantics consumers skip it. Terms are kept in the given order (no coalescing).
     */
    constructor(
        intCoeffs: LongArray,
        intVars: IntArray,
        realCoeffs: DoubleArray,
        realVars: IntArray,
        op: LinearOp,
        bound: Long,
    ) : this(
        CoalescedTerms(intVars.copyOf(), EmptyLongArray),
        op,
        0L,
        realVars.copyOf(),
        realCoeffs.copyOf(),
        DoubleArray(intCoeffs.size) { intCoeffs[it].toDouble() },
        bound.toDouble(),
    )

    /**
     * General LP-only real form with **double** integer-side coefficients and bound (the MPS / float
     * frontend case, where a row touching a continuous variable may carry fractional coefficients on its
     * integer variables and a fractional bound): `Σ intCoeffs(i)·intVars(i) + Σ realCoeffs(j)·realVars(j)
     * ⟨op⟩ bound`. [realVars] must be non-empty (it is what makes the row LP-only). Terms are kept in the
     * given order.
     */
    constructor(
        intVars: IntArray,
        intCoeffs: DoubleArray,
        realVars: IntArray,
        realCoeffs: DoubleArray,
        op: LinearOp,
        bound: Double,
        strict: Boolean = false,
    ) : this(
        CoalescedTerms(intVars.copyOf(), EmptyLongArray),
        op,
        0L,
        realVars.copyOf(),
        realCoeffs.copyOf(),
        intCoeffs.copyOf(),
        bound,
        strict,
    )

    override fun structuralKey(): StructuralKey = materializeKey(FactorKind.LINEAR, ::buildKey)

    // Allocation-free per-incidence key hash via the two-mode [KeySink] — symmetry refinement rebuilds
    // this once per incident variable each round. `pairsByVarKeyCoalescing` reproduces `remap()` (whose
    // constructor coalesces same-image terms) followed by the key sort, so the port hash stays equal to
    // `remap().structuralKey().hashCode()` even when the colouring map collapses two variables.
    override fun remapStructuralHash(mapping: VarRemap): Int = hashRemappedKey(FactorKind.LINEAR, mapping, ::buildKey)

    private fun buildKey(sink: KeySink) {
        sink.enum(op)
        // Each shape keys on its own constants: a continuous row on its exact doubles, a wide row on its
        // exact BigIntegers, an integer row on its bound and coalesced terms. No shape's key can collide
        // with another's, since only one of them is ever fed.
        when (val c = constants) {
            is RealConstants -> {
                sink.long(if (c.strict) 1L else 0L)
                sink.long(c.bound.toRawBits())
                for (i in vars.indices) {
                    sink.long(vars[i].toLong())
                    sink.long(c.intCoefficients.at(i).toRawBits())
                }
                for (j in realVars.indices) {
                    sink.long(realVars[j].toLong())
                    sink.long(c.realCoefficients.at(j).toRawBits())
                }
            }

            is WideConstants -> {
                // Feed the decimal strings char-by-char with an out-of-char-range separator; unambiguous,
                // so different wide rows never share a key.
                for (ch in c.bound.toString()) sink.long(ch.code.toLong())
                for (i in vars.indices) {
                    sink.long(Long.MIN_VALUE)
                    sink.long(vars[i].toLong())
                    for (ch in c.coefficients.at(i).toString()) sink.long(ch.code.toLong())
                }
            }

            is IntegerConstants -> {
                sink.long(c.bound)
                sink.pairsByVarKeyCoalescing(vars) { c.coeff(it) }
            }
        }
    }

    override fun remap(mapping: VarRemap): Factor = when (val c = constants) {
        // The row was already canonicalised (any `>=` negated) at construction, so re-emit as-is (op is
        // LE/EQ/NE, never GE) via the double form to preserve the exact continuous coefficients and bound.
        is RealConstants -> Linear(
            mapping.ints(vars),
            c.intCoefficients.toDoubleArray(),
            mapping.reals(realVars),
            c.realCoefficients.toDoubleArray(),
            op,
            c.bound,
            c.strict,
        )

        // Already canonical (op is LE/EQ/NE); re-emit over remapped vars. A colouring map can collapse two
        // of the row's vars onto one image, so coalesce their exact coefficients (summing) to keep the term
        // set duplicate-free — the propagator's interval reasoning requires one term per variable.
        is WideConstants -> {
            val (rv, rc) = coalesceWide(mapping.ints(vars), c.coefficients.toTypedArray())
            Linear(rv, rc, op, c.bound)
        }

        is IntegerConstants -> Linear(c.coeffs, mapping.ints(vars), op, c.bound)
    }

    /**
     * A pure binary value relation `c·x ⟨=|≠⟩ c·y` — two terms with opposite-equal coefficients and a
     * zero bound, comparing for equality or distinctness. Its allowed-tuple set (`{x = y}` / `{x ≠ y}`)
     * is invariant under *any* uniform relabeling of values, so it is value-anonymous. Every
     * other linear is value-meaningful: an ordering (`≤`/`≥`) is not relabeling-invariant, and a
     * nonzero bound or non-opposite coefficients tie the variables to specific magnitudes.
     */
    private fun isBinaryValueRelation(): Boolean {
        val c = integerConstants ?: return false
        return (op == LinearOp.EQ || op == LinearOp.NE) && c.bound == 0L &&
            vars.size == 2 && c.coeff(0) != 0L && c.coeff(0) == -c.coeff(1)
    }

    override fun isValueAnonymous(): Boolean = isBinaryValueRelation()

    // A value-anonymous factor names no value as a constant, so a relabeling maps it to itself.
    override fun remapValues(valueMap: (Long) -> Long): Factor? = if (isBinaryValueRelation()) this else null

    // A continuous row connects the objective through its integer terms via the LP double view, not the
    // integer objective cone; keep it out of the cone probe (which reasons over integer CORE rows only).
    override val extendsObjectiveCone: Boolean get() = constants is IntegerConstants

    // A continuous row is LP-only: it does not propagate in CP ([NoPropagator], so the occurrence index
    // never wakes it) — its feasibility is enforced by the LP relaxation and the search leaf. The
    // local-search engine is gated off for problems with real variables, so its invariant is never
    // consulted; an inert one keeps the factory total without pretending to evaluate the real terms.
    override fun asPropagator(): Propagator = when (val c = constants) {
        is RealConstants -> NoPropagator
        is WideConstants -> WideLinearPropagator(intVars, vars, c.coefficients.toTypedArray(), op, c.bound)
        is IntegerConstants -> LinearPropagator(boolVars, intVars, c.coeffs, vars, op, c.bound)
    }

    override fun asInvariant(): Invariant = integerConstants?.let { LinearInvariant(it.coeffs, vars, op, it.bound) }
        ?: NoInvariant

    // The integer reading is itself the exact [LinearRow], held by the row's own constants, so presolve
    // reads it with no extra allocation. A wide or continuous row has no integer LinearRow — its content
    // is not integer-valued — and emits a relaxation row instead.
    override val linearRows: List<LinearRow> get() = listOfNotNull(integerConstants)

    override fun linearize(builder: RelaxationBuilder, factorId: Int) {
        when (val c = constants) {
            // A wide row enters the LP only as directionally-rounded double outer-relaxation rows; its
            // exact coefficients never enter the LP (see [emitWideOuterRows]).
            is WideConstants -> emitWideOuterRows(builder, c)

            is IntegerConstants -> builder.linearRow(op, vars, c.coeffs, c.bound)

            // Mixed integer + real row: map integer vars to their LP columns and real vars to their LP-only
            // continuous columns, and emit one double-precision row over the combined columns.
            is RealConstants -> {
                val cols = IntArray(vars.size + realVars.size)
                val dcoeffs = DoubleArray(cols.size)
                for (i in vars.indices) {
                    cols[i] = builder.intColumn(vars[i])
                    dcoeffs[i] = c.intCoefficients.at(i)
                }
                for (j in realVars.indices) {
                    cols[vars.size + j] = builder.realColumn(realVars[j])
                    dcoeffs[vars.size + j] = c.realCoefficients.at(j)
                }
                builder.realRow(cols, dcoeffs, op, c.bound, c.strict)
            }
        }
    }

    /**
     * Emit a wide row into the LP as double **outer-relaxation** rows: an inequality (canonicalised to
     * `≤`) as one row, an equality as a bracketing `≤`/`≥` pair. Each coefficient is directionally rounded
     * so the emitted double row can only *weaken* the constraint — it never cuts a feasible integer point —
     * and the row goes through [RelaxationBuilder.realRow], which forces the double LP view so the exact
     * integer certificate declines (no certified prune ever reads these rounded coefficients; the row only
     * tightens the rigorous float objective bound). A `≠` row has no LP relaxation, so nothing is emitted.
     */
    private fun emitWideOuterRows(builder: RelaxationBuilder, constants: WideConstants) {
        // GE is canonicalised to LE at construction; NE has no single LP row.
        if (op != LinearOp.LE && op != LinearOp.EQ) return
        // A value past the `Double` range has no finite double to round outward to. The row is still
        // enforced exactly by its wide propagator, and a relaxation is only ever a bound, so leaving this
        // one out weakens the LP and nothing else.
        val rounded = wideRounding(constants) ?: return
        // A sign-straddling variable cannot be single-rounded to weaken both signs, so split it into
        // nonnegative parts `x = x⁺ − x⁻` (each roundable like a nonnegative variable). Decline only the
        // pathological var whose negative extent `−min` overflows Long — then leave the whole row CP-only.
        for (i in vars.indices) {
            val dom = builder.declaredDomain(vars[i])
            if (dom.min < 0L && dom.max > 0L && dom.min == Long.MIN_VALUE) return
        }
        val plusCol = IntArray(vars.size) { -1 }
        val minusCol = IntArray(vars.size) { -1 }
        for (i in vars.indices) {
            val dom = builder.declaredDomain(vars[i])
            if (dom.min < 0L && dom.max > 0L) {
                val cp = builder.auxColumn(0L, dom.max)
                val cm = builder.auxColumn(0L, -dom.min)
                // Exact link `x = x⁺ − x⁻` ties the nonnegative parts to the variable's own column; it never
                // rounds, so it cannot cut a feasible point (the canonical split `x⁺=max(x,0)` satisfies it).
                builder.realRow(
                    intArrayOf(builder.intColumn(vars[i]), cp, cm),
                    doubleArrayOf(1.0, -1.0, 1.0),
                    LinearOp.EQ,
                    0.0,
                    strict = false,
                )
                plusCol[i] = cp
                minusCol[i] = cm
            }
        }
        emitWideOuterRow(builder, rounded, ge = false, plusCol, minusCol)
        if (op == LinearOp.EQ) emitWideOuterRow(builder, rounded, ge = true, plusCol, minusCol)
    }

    private var wideRoundingMemo: WideRounding? = null

    /** Set once [wideRounding] has found a value with no finite `Double`, which no later call can change. */
    private var wideExceedsDouble = false

    /** The outward-rounded double form of this row's wide values, or `null` when one of them has no finite
     *  `Double` to round to. Both the verdict and the rounded values are functions of the row's immutable
     *  wide coefficients, while a relaxation is rebuilt per node, so they are computed once: an
     *  arbitrary-precision conversion costs a pass over the whole magnitude and there are two per
     *  coefficient. */
    private fun wideRounding(constants: WideConstants): WideRounding? {
        if (wideExceedsDouble) return null
        wideRoundingMemo?.let { return it }
        val exactBound = constants.bound
        val exactCoeffs = constants.coefficients.toTypedArray()
        if (!fitsDouble(exactBound) || !exactCoeffs.all { fitsDouble(it) }) {
            wideExceedsDouble = true
            return null
        }
        return WideRounding(
            DoubleArray(exactCoeffs.size) { floorToDouble(exactCoeffs[it]) },
            DoubleArray(exactCoeffs.size) { ceilToDouble(exactCoeffs[it]) },
            floorToDouble(exactBound),
            ceilToDouble(exactBound),
        ).also { wideRoundingMemo = it }
    }

    /**
     * One outer-relaxation row for the wide constraint, oriented `≥` when [ge] else `≤`. Round each
     * coefficient in the direction that weakens the row given the variable's declared sign: for `≤`, a
     * sign-nonnegative variable rounds its coefficient down and a nonpositive one rounds up (the bound
     * rounds up); `≥` is the mirror (bound rounds down). A sign-straddling variable is emitted through its
     * split columns ([plusCol]/[minusCol], both nonnegative): `x⁺` carries the coefficient and `x⁻` its
     * negation, each rounded like a nonnegative term. Uses the *declared* domain: the coefficient is fixed,
     * so the row must stay a valid relaxation at every node, not only the current one.
     */
    private fun emitWideOuterRow(
        builder: RelaxationBuilder,
        rounded: WideRounding,
        ge: Boolean,
        plusCol: IntArray,
        minusCol: IntArray,
    ) {
        var straddle = 0
        for (i in vars.indices) if (plusCol[i] >= 0) straddle++
        val cols = IntArray(vars.size + straddle)
        val dcoeffs = DoubleArray(cols.size)
        var w = 0
        for (i in vars.indices) {
            if (plusCol[i] >= 0) {
                // x⁺ (nonnegative, coefficient wc) and x⁻ (nonnegative, coefficient −wc), each rounded to
                // weaken: floor a nonnegative term for `≤`, ceil for `≥`.
                cols[w] = plusCol[i]
                dcoeffs[w] = if (ge) rounded.ceilCoeffs[i] else rounded.floorCoeffs[i]
                w++
                cols[w] = minusCol[i]
                dcoeffs[w] = if (ge) -rounded.floorCoeffs[i] else -rounded.ceilCoeffs[i]
                w++
            } else {
                val dom = builder.declaredDomain(vars[i])
                // floor for (≤ & nonneg) or (≥ & nonpositive); ceil otherwise.
                val roundDown = (dom.min >= 0L) != ge
                cols[w] = builder.intColumn(vars[i])
                dcoeffs[w] = if (roundDown) rounded.floorCoeffs[i] else rounded.ceilCoeffs[i]
                w++
            }
        }
        val rhs = if (ge) rounded.floorBound else rounded.ceilBound
        builder.realRow(cols, dcoeffs, if (ge) LinearOp.GE else LinearOp.LE, rhs, strict = false)
    }
}

/** A wide row's coefficients and bound rounded outward, index-aligned with the row's variables: the
 *  direction each term needs is only known per emitted row, so both are kept. */
private class WideRounding(
    val floorCoeffs: DoubleArray,
    val ceilCoeffs: DoubleArray,
    val floorBound: Double,
    val ceilBound: Double,
)

/** Magnitudes strictly below `2^DOUBLE_CERTAIN_FINITE_BITS` always have a finite `Double`; see [fitsDouble]. */
private const val DOUBLE_CERTAIN_FINITE_BITS = 1023

/**
 * Whether [x] converts to a finite `Double`. A conversion is a shift/subtract loop over the whole
 * magnitude, so the verdict is read off the bit length instead: `bitLength` is the 1-based position of
 * the leading bit, a magnitude under `2^1023` is far inside the double range and one at or above `2^1024`
 * is past its largest finite value `2^1024 − 2^971`. Only the single exponent band in between straddles
 * the round-to-infinity threshold, so only there is the conversion actually needed.
 */
private fun fitsDouble(x: BigInteger): Boolean {
    val bits = x.bitLength()
    return when {
        bits <= DOUBLE_CERTAIN_FINITE_BITS -> true
        bits > DOUBLE_CERTAIN_FINITE_BITS + 1 -> false
        else -> x.doubleValue(exactRequired = false).isFinite()
    }
}

/** The largest `Double` that is `≤ x`. `doubleValue` rounds to nearest; step one ULP down when that
 *  landed above `x` so the result is a sound lower bound for outward relaxation. */
private fun floorToDouble(x: BigInteger): Double {
    val d = x.doubleValue(exactRequired = false)
    return if (BigInteger.tryFromDouble(d, exactRequired = false) > x) d.nextDown() else d
}

/** The smallest `Double` that is `≥ x` (one ULP up when nearest rounding landed below `x`). */
private fun ceilToDouble(x: BigInteger): Double {
    val d = x.doubleValue(exactRequired = false)
    return if (BigInteger.tryFromDouble(d, exactRequired = false) < x) d.nextUp() else d
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

/** Sum the exact coefficients of any repeated variable and drop zero-sum terms, preserving first-seen
 *  order — the wide analogue of [coalesceLinearTerms], so a wide row always has one term per variable. */
internal fun coalesceWide(vars: IntArray, coeffs: Array<BigInteger>): Pair<IntArray, Array<BigInteger>> {
    val order = ArrayList<Int>(vars.size)
    val sum = HashMap<Int, BigInteger>(vars.size)
    for (i in vars.indices) {
        val v = vars[i]
        val prev = sum[v]
        if (prev == null) {
            order.add(v)
            sum[v] = coeffs[i]
        } else {
            sum[v] = prev + coeffs[i]
        }
    }
    val keptVars = ArrayList<Int>(order.size)
    val keptCoeffs = ArrayList<BigInteger>(order.size)
    for (v in order) {
        val c = sum.getValue(v)
        if (c != BigInteger.ZERO) {
            keptVars.add(v)
            keptCoeffs.add(c)
        }
    }
    return keptVars.toIntArray() to keptCoeffs.toTypedArray()
}
