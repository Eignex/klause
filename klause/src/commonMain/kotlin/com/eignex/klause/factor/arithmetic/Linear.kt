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
import com.eignex.klause.solver.IntVars
import com.eignex.klause.solver.KeySink
import com.eignex.klause.solver.MixedVars
import com.eignex.klause.solver.StructuralKey
import com.eignex.klause.solver.VarList
import com.eignex.klause.solver.hashRemappedKey
import com.eignex.klause.solver.materializeKey
import com.eignex.klause.util.EmptyDoubleArray
import com.eignex.klause.util.EmptyIntArray
import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.math.nextDown
import kotlin.math.nextUp
import kotlin.math.roundToLong

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
    // Double forms used only by an LP-only (real-bearing) row, where coefficients and the bound need not
    // be integral: the double coefficient of each integer term (index-aligned with [vars]) and the double
    // right-hand side. The [Long] `coeffs`/`bound` are then rounded placeholders the never-read integer
    // consumers keep well-formed. Empty / 0 for the integer core.
    intCoeffsRealIn: DoubleArray = EmptyDoubleArray,
    realBoundIn: Double = 0.0,
    strictRealIn: Boolean = false,
    // Wide (>64-bit) integer coefficients and bound, carried exactly as BigInteger. Present only on a wide
    // row, where the Long [coeffs]/[bound] are saturated placeholders and [WideLinearPropagator] reads
    // these. Null for the integer core and for real rows.
    wideCoeffsIn: Array<BigInteger>? = null,
    wideBoundIn: BigInteger? = null,
) : Factor,
    LinearRow {

    // Canonicalise inequalities to ≤ at construction — the LP/MIP convention the cut separators
    // (FlowCoverSeparator, knapsack cover) expect. A ≥ becomes ≤ by negating the coefficients and bound;
    // = / ≠ keep their orientation. So an inequality [Linear] always reports [op] `LE`, and a constraint
    // and its negation share a structural key (so the two dedup as one).
    val op: LinearOp = if (rawOp == LinearOp.GE) LinearOp.LE else rawOp
    override val bound: Long = if (rawOp == LinearOp.GE) -rawBound else rawBound
    val vars: IntArray = terms.vars

    // Integer coefficients, index-aligned with [vars], stored compactly to cut parse/presolve memory on the
    // Linear-dominated families (MPS, QF_LIRA): when every coefficient fits Int (the common case) only an
    // [IntArray] is retained (4 B/term), else a [LongArray] (8 B/term). The GE→LE canonicalisation negates
    // the coefficients here. [coeff] reads the store with no allocation (the per-node LP path); [coeffs]
    // materialises a full [LongArray] for whole-array sinks.
    private val coeffsInt: IntArray?
    private val coeffsLong: LongArray?

    /** Largest `|coefficient|` over the integer terms (0 when there are none), cached at construction so the
     *  presolve overflow gates (the affine-elimination fold, the small-model bound) need no per-row
     *  coefficient rescan. A saturated placeholder on a wide/real row (never read as authoritative there). */
    val maxAbsCoeff: Long

    init {
        val negate = rawOp == LinearOp.GE
        val src = terms.coeffs
        var maxAbs = 0L
        var fitsInt = true
        for (raw in src) {
            val c = if (negate) -raw else raw
            val a = if (c < 0L) -c else c
            if (a > maxAbs) maxAbs = a
            if (c < Int.MIN_VALUE.toLong() || c > Int.MAX_VALUE.toLong()) fitsInt = false
        }
        maxAbsCoeff = maxAbs
        if (fitsInt) {
            coeffsInt = IntArray(src.size) { (if (negate) -src[it] else src[it]).toInt() }
            coeffsLong = null
        } else {
            coeffsInt = null
            coeffsLong = if (negate) LongArray(src.size) { -src[it] } else src
        }
    }

    /** Integer coefficients as a [LongArray], index-aligned with [vars], materialised on demand from the
     *  compact store. Prefer [coeff] for indexed access; this allocates for an [coeffsInt]-backed row. */
    val coeffs: LongArray
        get() {
            val cl = coeffsLong
            if (cl != null) return cl
            val ci = checkNotNull(coeffsInt)
            return LongArray(ci.size) { ci[it].toLong() }
        }

    /**
     * The LP-only payload — real (continuous) terms and/or over-64-bit wide coefficients — held off to the
     * side. It is `null` for a plain integer row (the common case), so an integer [Linear] carries a single
     * null reference here instead of the seven empty/zero/false fields the payload would otherwise occupy on
     * every row. The GE→LE canonicalisation (negating real/wide coefficients and bounds) happens here so the
     * accessors below can expose the payload without re-deriving it.
     */
    private val lpExtra: LinearLpExtra? =
        if (realVarsIn.isNotEmpty() || wideCoeffsIn != null) {
            val negate = rawOp == LinearOp.GE
            LinearLpExtra(
                realVars = realVarsIn,
                realCoeffs = if (negate) DoubleArray(realCoeffsIn.size) { -realCoeffsIn[it] } else realCoeffsIn,
                realIntCoeffs =
                if (negate) DoubleArray(intCoeffsRealIn.size) { -intCoeffsRealIn[it] } else intCoeffsRealIn,
                realBound = if (negate) -realBoundIn else realBoundIn,
                strictReal = strictRealIn,
                wideCoeffs = when {
                    wideCoeffsIn == null -> null
                    negate -> Array(wideCoeffsIn.size) { -wideCoeffsIn[it] }
                    else -> wideCoeffsIn
                },
                wideBound = if (wideBoundIn != null && negate) -wideBoundIn else wideBoundIn,
            )
        } else {
            null
        }

    /**
     * LP-only continuous (real) variable terms, additional to the integer terms: real var ids paired with
     * double coefficients in [realCoeffs]. Empty for the integer/Boolean core. When present the row
     * `Σ coeffs(i)·vars(i) + Σ realCoeffs(j)·realVars(j) ⟨op⟩ bound` reasons over a continuous variable, so
     * it is absent from CP propagation ([asPropagator] is [NoPropagator]) and its feasibility is enforced by
     * the LP relaxation and the search leaf. A `>=` row negates these coefficients alongside the integer ones.
     */
    val realVars: IntArray get() = lpExtra?.realVars ?: EmptyIntArray

    /** Double coefficient of each [realVars] term, index-aligned; a `>=` row negates them with the
     *  integer coefficients (see [realVars]). */
    val realCoeffs: DoubleArray get() = lpExtra?.realCoeffs ?: EmptyDoubleArray

    /** Double coefficient of each integer term [vars] on an LP-only row (index-aligned with [vars]); the
     *  authoritative value the relaxation reads, since [coeffs] is only a rounded placeholder here. Empty
     *  for the integer core. A `>=` row negates these with the rest. */
    val realIntCoeffs: DoubleArray get() = lpExtra?.realIntCoeffs ?: EmptyDoubleArray

    /** Double right-hand side of an LP-only row ([bound] is only a rounded placeholder here); `>=` negates. */
    val realBound: Double get() = lpExtra?.realBound ?: 0.0

    /** Whether this row carries a continuous (real) term, making it an LP-only row (see [realVars]). */
    val hasReals: Boolean get() = lpExtra?.realVars?.isNotEmpty() == true

    /** Wide (>64-bit) integer coefficients, index-aligned with [vars]; null unless [wide]. A `>=` row
     *  negates them (with [wideBound]); [WideLinearPropagator] reads these exact values, not [coeffs]. */
    val wideCoeffs: Array<BigInteger>? get() = lpExtra?.wideCoeffs

    /** Wide (>64-bit) right-hand side; null unless [wide]. `>=` negates it (with [wideCoeffs]). */
    val wideBound: BigInteger? get() = lpExtra?.wideBound

    /** True when a coefficient or the bound exceeds the 64-bit range: the row propagates via
     *  [WideLinearPropagator] (exact arbitrary precision), is excluded from the LP relaxation, and its
     *  Long [coeffs]/[bound] are saturated placeholders never read as authoritative. */
    val wide: Boolean get() = lpExtra?.wideCoeffs != null

    /** A plain 64-bit integer CP row — neither LP-only real ([hasReals]) nor [wide]. The only shape whose
     *  Long [coeffs]/[bound] the integer-reasoning passes (presolve, cuts, LP, LS) may read directly. */
    val isIntegerCore: Boolean get() = !hasReals && !wide

    /** Strict inequality over reals (`Σ … < bound` after the ≤ canonicalisation). Only meaningful on an
     *  LP-only row: the float relaxation treats it as non-strict (a sound relaxation), and the exact
     *  deciders enforce the strictness (delta-rational feasibility, boundary-rejecting point checks). */
    val strictReal: Boolean get() = lpExtra?.strictReal == true

    init {
        require(vars.isNotEmpty() || realVars.isNotEmpty()) { "linear sum must have at least one term" }
        require(realVars.size == realCoeffs.size) { "real vars/coeffs length mismatch" }
        require(!hasReals || realIntCoeffs.size == vars.size) { "real int-coeff/var length mismatch" }
        require(!strictReal || (hasReals && op == LinearOp.LE)) { "strictness needs an LP-only inequality row" }
        val wc = wideCoeffs
        require(wc == null || wc.size == vars.size) { "wide coeff/var length mismatch" }
        require(!(wide && hasReals)) { "a row cannot be both wide and real" }
    }

    // Real columns are declared here like any other kind. They were reachable only through the LP
    // payload before, so no consumer scanning a factor's variables could see them.
    override val variables: VarList = if (realVars.isEmpty()) {
        IntVars(vars)
    } else {
        MixedVars(boundInts = vars, reals = realVars)
    }

    /** Interval reasoning over the row's terms; an endpoint it cannot bound simply yields no deduction. */
    override val needsFiniteDomains: Boolean get() = false

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
     * the [Long] `coeffs`/`bound` are saturated placeholders kept only so integer-shape invariants hold.
     * [vars] must be distinct — this form does not coalesce duplicates.
     */
    constructor(vars: IntArray, wideCoeffs: Array<BigInteger>, op: LinearOp, wideBound: BigInteger) : this(
        CoalescedTerms(vars.copyOf(), LongArray(wideCoeffs.size) { wideCoeffs[it].saturatedLong() }),
        op,
        wideBound.saturatedLong(),
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
        CoalescedTerms(intVars.copyOf(), intCoeffs.copyOf()),
        op,
        bound,
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
     * given order. The [Long] `coeffs`/`bound` become rounded placeholders; the relaxation reads the
     * double forms.
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
        CoalescedTerms(intVars.copyOf(), LongArray(intCoeffs.size) { intCoeffs[it].roundToLong() }),
        op,
        bound.roundToLong(),
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
    override fun remapStructuralHash(boolMap: IntArray, intMap: IntArray): Int =
        hashRemappedKey(FactorKind.LINEAR, boolMap, intMap, ::buildKey)

    private fun buildKey(sink: KeySink) {
        sink.enum(op)
        // A continuous row keys on its exact double bound / integer coefficients (the [Long] forms are
        // rounded placeholders); an integer row keys on the [Long] bound and coalesced integer terms.
        if (hasReals) {
            sink.long(if (strictReal) 1L else 0L)
            sink.long(realBound.toRawBits())
            for (i in vars.indices) {
                sink.long(vars[i].toLong())
                sink.long(realIntCoeffs[i].toRawBits())
            }
            for (j in realVars.indices) {
                sink.long(realVars[j].toLong())
                sink.long(realCoeffs[j].toRawBits())
            }
        } else if (wide) {
            // Key on the exact BigInteger coeffs/bound (the Long forms are saturated placeholders, so
            // distinct wide values would otherwise collide). Feed the decimal strings char-by-char with an
            // out-of-char-range separator; unambiguous, so different wide rows never share a key.
            val wc = checkNotNull(wideCoeffs)
            for (ch in checkNotNull(wideBound).toString()) sink.long(ch.code.toLong())
            for (i in vars.indices) {
                sink.long(Long.MIN_VALUE)
                sink.long(vars[i].toLong())
                for (ch in wc[i].toString()) sink.long(ch.code.toLong())
            }
        } else {
            sink.long(bound)
            sink.pairsByVarKeyCoalescing(vars) { coeff(it) }
        }
    }

    override fun remap(boolMap: IntArray, intMap: IntArray): Factor = if (hasReals) {
        // Real var ids live in a separate namespace and are not remapped by [intMap]; the row was
        // already canonicalised (any `>=` negated) at construction, so re-emit as-is (op is LE/EQ/NE,
        // never GE) via the double form to preserve the exact continuous-row coefficients and bound.
        Linear(vars.remapVars(intMap), realIntCoeffs, realVars, realCoeffs, op, realBound, strictReal)
    } else if (wide) {
        // Already canonical (op is LE/EQ/NE); re-emit over remapped vars. A colouring map can collapse two
        // of the row's vars onto one image, so coalesce their exact coefficients (summing) to keep the term
        // set duplicate-free — the propagator's interval reasoning requires one term per variable.
        val (rv, rc) = coalesceWide(vars.remapVars(intMap), checkNotNull(wideCoeffs))
        Linear(rv, rc, op, checkNotNull(wideBound))
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
    private fun isBinaryValueRelation(): Boolean = isIntegerCore &&
        (op == LinearOp.EQ || op == LinearOp.NE) && bound == 0L &&
        vars.size == 2 && coeff(0) != 0L && coeff(0) == -coeff(1)

    override fun isValueAnonymous(): Boolean = isBinaryValueRelation()

    // A value-anonymous factor names no value as a constant, so a relabeling maps it to itself.
    override fun remapValues(valueMap: (Long) -> Long): Factor? = if (isBinaryValueRelation()) this else null

    // A continuous row connects the objective through its integer terms via the LP double view, not the
    // integer objective cone; keep it out of the cone probe (which reasons over integer CORE rows only).
    override val extendsObjectiveCone: Boolean get() = isIntegerCore

    // A continuous row is LP-only: it does not propagate in CP ([NoPropagator], so the occurrence index
    // never wakes it) — its feasibility is enforced by the LP relaxation and the search leaf. The
    // local-search engine is gated off for problems with real variables, so its invariant is never
    // consulted; an inert one keeps the factory total without pretending to evaluate the real terms.
    override fun asPropagator(): Propagator = when {
        hasReals -> NoPropagator
        wide -> WideLinearPropagator(intVars, vars, checkNotNull(wideCoeffs), op, checkNotNull(wideBound))
        else -> LinearPropagator(boolVars, intVars, coeffs, vars, op, bound)
    }

    override fun asInvariant(): Invariant = if (isIntegerCore) LinearInvariant(coeffs, vars, op, bound) else NoInvariant

    // The factor *is* its own exact linear row (integer terms), so presolve reads it with no extra
    // allocation. [linearize] emits the row over the factor's arrays directly rather than through the
    // interface accessors, keeping the per-node LP path allocation-free. A continuous row exposes no
    // integer LinearRow (its content is not integer-valued) and emits a real row instead.
    override val size: Int get() = vars.size
    override fun ref(k: Int): Int = Term.ofIntVar(vars[k])
    override fun coeff(k: Int): Long {
        val ci = coeffsInt
        return if (ci != null) ci[k].toLong() else checkNotNull(coeffsLong)[k]
    }
    override val relation: LinearOp get() = op
    override val isIntegerOnly: Boolean get() = isIntegerCore
    override val linearRows: List<LinearRow> get() = if (isIntegerCore) listOf(this) else emptyList()

    override fun linearize(builder: RelaxationBuilder, factorId: Int) {
        // A wide row enters the LP as directionally-rounded double outer-relaxation rows (see
        // [emitWideOuterRows]); its exact Long coeffs/bound are placeholders and never enter the LP.
        if (wide) {
            emitWideOuterRows(builder)
            return
        }
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
            dcoeffs[i] = realIntCoeffs[i]
        }
        for (j in realVars.indices) {
            cols[vars.size + j] = builder.realColumn(realVars[j])
            dcoeffs[vars.size + j] = realCoeffs[j]
        }
        builder.realRow(cols, dcoeffs, op, realBound, strictReal)
    }

    /**
     * Emit a wide row into the LP as double **outer-relaxation** rows: an inequality (canonicalised to
     * `≤`) as one row, an equality as a bracketing `≤`/`≥` pair. Each coefficient is directionally rounded
     * so the emitted double row can only *weaken* the constraint — it never cuts a feasible integer point —
     * and the row goes through [RelaxationBuilder.realRow], which forces the double LP view so the exact
     * integer certificate declines (no certified prune ever reads these rounded coefficients; the row only
     * tightens the rigorous float objective bound). A `≠` row has no LP relaxation, so nothing is emitted.
     */
    private fun emitWideOuterRows(builder: RelaxationBuilder) {
        // GE is canonicalised to LE at construction; NE has no single LP row.
        if (op != LinearOp.LE && op != LinearOp.EQ) return
        // A value past the `Double` range has no finite double to round outward to. The row is still
        // enforced exactly by its wide propagator, and a relaxation is only ever a bound, so leaving this
        // one out weakens the LP and nothing else.
        val rounded = wideRounding() ?: return
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
    private fun wideRounding(): WideRounding? {
        if (wideExceedsDouble) return null
        wideRoundingMemo?.let { return it }
        val bound = checkNotNull(wideBound)
        val coeffs = checkNotNull(wideCoeffs)
        if (!fitsDouble(bound) || !coeffs.all { fitsDouble(it) }) {
            wideExceedsDouble = true
            return null
        }
        return WideRounding(
            DoubleArray(coeffs.size) { floorToDouble(coeffs[it]) },
            DoubleArray(coeffs.size) { ceilToDouble(coeffs[it]) },
            floorToDouble(bound),
            ceilToDouble(bound),
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

/** The LP-only payload of a [Linear] row — real (continuous) terms and/or over-64-bit wide coefficients —
 *  held off to the side so a plain integer row (which has none of these) carries a single null reference
 *  rather than the seven fields these values would otherwise occupy on every [Linear]. Values are already
 *  GE→LE-canonicalised by the [Linear] constructor. */
private class LinearLpExtra(
    val realVars: IntArray,
    val realCoeffs: DoubleArray,
    val realIntCoeffs: DoubleArray,
    val realBound: Double,
    val strictReal: Boolean,
    val wideCoeffs: Array<BigInteger>?,
    val wideBound: BigInteger?,
)

/** A wide row's coefficients and bound rounded outward, index-aligned with `Linear.wideCoeffs`: the
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

/** [Long] clamp of a [BigInteger] to the signed 64-bit range — the saturated placeholder a wide row keeps
 *  in its Long `coeffs`/`bound`, never read as authoritative (the exact value lives in [Linear.wideCoeffs]). */
internal fun BigInteger.saturatedLong(): Long = when {
    this > BigInteger.fromLong(Long.MAX_VALUE) -> Long.MAX_VALUE
    this < BigInteger.fromLong(Long.MIN_VALUE) -> Long.MIN_VALUE
    else -> longValue(exactRequired = false)
}
