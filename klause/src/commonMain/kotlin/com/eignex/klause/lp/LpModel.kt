package com.eignex.klause.lp

import com.eignex.klause.util.EmptyIntArray
import com.eignex.klause.util.EmptyLongArray
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.LongArrayList
import com.eignex.klause.util.MutableIntLongMap
import com.eignex.klause.util.toSortedIntArray

/** Constraint relation for a row added to the builder, before normalization to `<=` form. */
internal enum class Relation { LE, GE, EQ }

/**
 * The live variable bounds a non-global row's validity rests on — the premises an LP certificate
 * must cite alongside its column seats when the row carries dual weight (see [LpModel.rowGlobal]).
 * The canonical producer is a live-big-M `ReifiedLinear` row: its relaxed face spans the *live*
 * range of the linear form, so the row holds wherever each variable respects the live bound that
 * entered the M — exactly the `(variable, side, threshold)` triples recorded here. Only bounds
 * tighter than the declared ones are recorded (a declared bound holds everywhere). Parallel
 * arrays: premise `k` is `x_{vars[k]} ≤ thresholds[k]` when `isUpper[k]`, else `≥`.
 */
internal class LpRowPremises(
    val vars: IntArray,
    val isUpper: BooleanArray,
    val thresholds: LongArray,
    /** Boolean literals that must hold for the row to be valid (a reified real atom's activation);
     *  a learned clause leaning on the row cites their negations. */
    val boolLits: IntArray = EmptyIntArray,
)

/** Optimization sense. Branch-and-bound minimizes; [MAXIMIZE] is negated at build time. */
internal enum class Sense { MINIMIZE, MAXIMIZE }

/** The CPU simplex's private, finite stand-in for an unbounded variable bound (`±∞`). Large enough to
 *  behave as infinity for any realistic model, yet small enough (`Long.MAX / 4`) to leave headroom for
 *  the exact `subExact`/`mulExact` shift arithmetic in [LpBuilder.build]. A variable that can only be
 *  optimized *to* this frontier is genuinely unbounded, which [safeVariableBound] reports as null —
 *  callers express real infinity via [LpBuilder.addFreeVar] and never see this magnitude. */
internal const val LP_UNBOUNDED_PROBE: Long = Long.MAX_VALUE / 4

/**
 * A bounded-variable LP in the normalized form the revised simplex consumes. All input coefficients,
 * bounds and right-hand sides are integers — this is the "integer based" core: it exploits that every
 * klause datum is integral (the float [RevisedSimplex] solves, the integer-multiplier [integerCertify]
 * certifies) rather than carrying a general-purpose floating LP.
 *
 * Build instances with [LpBuilder], which performs three normalizations so the engine sees a
 * uniform shape:
 *
 *  1. **Lower bounds shifted to zero.** Each structural variable `x_j` with domain
 *     `[lo_j, hi_j]` becomes `x'_j = x_j - lo_j` with domain `[0, hi_j - lo_j]`. A nonbasic
 *     variable at its lower bound then contributes nothing to the basic values, which keeps
 *     the inner loops simple. The shift folds into the right-hand sides and an objective
 *     constant ([objConstant]); [loShift] records it so solutions map back.
 *  2. **`>=` rows negated to `<=`.** Multiplying a `>=` row by `-1` yields a `<=` row, so
 *     every inequality slack has lower bound `0` and only the (possibly infinite) upper bound
 *     varies. Equality rows keep a slack fixed at `[0, 0]`.
 *  3. **Slack columns appended.** Row `i` becomes `Σ a_ij x'_j + s_i = b_i`. With the
 *     all-slack basis the constraint matrix is `[A | I]`, an exact identity in the slack
 *     columns, so the initial determinant is `1` and no factorization is needed to start.
 *
 * Variables are indexed `0 until n` for structural columns and `n until n + m` for the slack
 * of row `i = column - n`. Lower bound is `0` for every variable; the upper bound is finite
 * for structural variables and equality slacks, and `+∞` for inequality slacks (see
 * [hasUpper]).
 */
internal class LpModel(
    /** Number of structural (original) variables. */
    val n: Int,
    /** Number of constraint rows, equivalently the number of slack variables. */
    val m: Int,
    /** Sparse CSC of the structural columns — the sole coefficient store (the sparse revised simplex
     *  is the only LP engine). Read column-wise through [forEachInColumn]; slack columns are the
     *  implicit unit vectors and are never stored. */
    val csc: Csc,
    /** Right-hand side per row, after the lower-bound shift. */
    val rhs: LongArray,
    /** Objective coefficient per variable (length `n + m`); minimization sense, slacks are `0`. */
    val cost: LongArray,
    /** Upper bound per variable (length `n + m`); meaningful only where [hasUpper] is true. */
    val upper: LongArray,
    /** Whether the variable has a finite upper bound; `false` means `+∞` (inequality slacks). */
    val hasUpper: BooleanArray,
    /** Lower bound that was shifted out of each structural variable (length `n`). */
    val loShift: LongArray,
    /** Constant folded into the objective by the shift; the true objective is `c·x' + objConstant`. */
    val objConstant: Long,
    /** Original optimization sense; the stored [cost] is always minimization. */
    val sense: Sense,
    /** Caller tag per structural column, for mapping LP columns back to `(varId, value)`. */
    val tag: IntArray,
    /**
     * Per-row global validity: `true` when row `i` holds at **every integer solution of the
     * problem** — not merely inside the current search node's box. Rows built from live
     * (branch-tightened) information — a [com.eignex.klause.factor.arithmetic.ReifiedLinear] big-M row
     * whose M came from tightened domains, a locally separated cut, a Gomory/MIR tableau cut — are
     * marked `false`. Learned artifacts (Farkas nogoods, objective-bound and reduced-cost reasons)
     * cite only variable-bound atoms and keep the rows implicit, so they are only valid when every
     * row their dual certificate leans on is globally valid; this array is what they check.
     */
    val rowGlobal: BooleanArray = BooleanArray(m) { true },
    /** Per row, whether the inequality is strict over the reals (`<` rather than `≤`). The float
     *  simplex ignores it (a sound relaxation); the exact deciders enforce it. */
    val rowStrict: BooleanArray = BooleanArray(m),
    /**
     * Per-row citation fallback for non-global rows: the live bounds whose atoms make row `i`
     * valid ([LpRowPremises]), or null when the row's validity is not expressible as bound atoms
     * (locally separated and Gomory/MIR cuts) — a certificate leaning on such a row is withheld.
     * Always null where [rowGlobal] is true.
     */
    val rowPremises: Array<LpRowPremises?> = arrayOfNulls(m),
    /**
     * Right-hand side per row after the `>=`-to-`<=` flip but **before** the lower-bound shift — i.e.
     * `rhs[i] = flippedRhs[i] − Σ_j csc(i,j)·loShift[j]`. Retained so a persistent relaxation can
     * [rebind] new column bounds over the fixed [csc] without re-running [LpBuilder]: the structure
     * (matrix, costs, tags, row relations) is node-invariant, only the bound-derived vectors change.
     * Defaults to the post-shift [rhs] for models that never rebind.
     */
    val flippedRhs: LongArray = rhs,
    /** Structural columns whose lower bound is the [LP_UNBOUNDED_PROBE] stand-in for `−∞` rather than a
     *  real bound (length `n`, all false for ordinary [LpBuilder.addVar] columns). The reject-at-cap
     *  logic in [safeVariableBound] consults this so an optimum riding to the probe frontier is reported
     *  unbounded rather than as a spurious finite bound. */
    val probeClampedLo: BooleanArray = BooleanArray(n),
    /** Counterpart to [probeClampedLo] for the upper bound (`+∞` stand-in). */
    val probeClampedHi: BooleanArray = BooleanArray(n),
    /** Per structural column, true when it is an LP-only continuous (real) column rather than an
     *  integer/bool one (length `n`, all false for the pure-integer core). A continuous column is present
     *  in this relaxation but absent from CP search. Informational; the exact-certification decline keys
     *  off [hasContinuous]. */
    val colContinuous: BooleanArray = BooleanArray(n),
    /**
     * The double-precision form the LP engine solves when real coefficients are present, else null (the
     * pure-integer core, where the engine reads the [Long] fields directly). It is the single uniform
     * matrix any engine — sparse simplex, dense, or a first-order GPU solver — consumes; the integer/real
     * split matters only to certification, which declines while it is present. Its coefficients, bounds,
     * costs and shift are all `Double`; the CSC mirrors [csc] (structural columns only, slacks implicit).
     */
    val doubleView: LpDoubleView? = null,
) {
    /** Total variable count: structural plus slack. */
    val numVars: Int get() = n + m

    /** Whether the model carries real coefficients, so it is solved through [doubleView] and the exact
     *  128-bit integer certification declines (a real coefficient is not integrally certifiable here). */
    val hasContinuous: Boolean get() = doubleView != null

    /** Objective coefficient of variable [j] as a double (from [doubleView] when present). */
    fun costD(j: Int): Double = doubleView?.cost?.get(j) ?: cost[j].toDouble()

    /** Right-hand side of row [i] as a double. */
    fun rhsD(i: Int): Double = doubleView?.rhs?.get(i) ?: rhs[i].toDouble()

    /** Upper bound of variable [j] as a double (meaningful only where [hasFiniteUpper] is true). */
    fun upperD(j: Int): Double = doubleView?.upper?.get(j) ?: upper[j].toDouble()

    /** Whether variable [j] has a finite upper bound. */
    fun hasFiniteUpper(j: Int): Boolean = doubleView?.hasUpper?.get(j) ?: hasUpper[j]

    /** Lower-bound shift of structural column [j] as a double. */
    fun loShiftD(j: Int): Double = doubleView?.loShift?.get(j) ?: loShift[j].toDouble()

    /** Objective constant as a double. */
    val objConstantD: Double get() = doubleView?.objConstant ?: objConstant.toDouble()

    /** Iterate column [j]'s nonzero structural entries as `(row, value)` in double precision — the
     *  uniform view the LP engine reads, from [doubleView] when present, else the [Long] CSC widened. */
    inline fun forEachInColumnD(j: Int, action: (row: Int, value: Double) -> Unit) {
        val dv = doubleView
        if (dv != null) {
            for (k in dv.colPtr[j] until dv.colPtr[j + 1]) action(dv.rowIdx[k], dv.colVal[k])
        } else {
            for (k in csc.colPtr[j] until csc.colPtr[j + 1]) action(csc.rowIdx[k], csc.colVal[k].toDouble())
        }
    }

    /**
     * A model identical in structure ([csc], [cost], [tag], [rowGlobal], [rowPremises], slack
     * relations) but with fresh structural-column bounds `[lo[j], hi[j]]`. Recomputes only the
     * bound-derived vectors — [rhs] (via the lower-bound shift over the fixed matrix), [upper],
     * [loShift] and [objConstant] — in `O(nnz)`. For a relaxation whose layout is node-invariant
     * (no auxiliary columns, no live-M rows) this yields the same model a per-node rebuild would,
     * so a search node can re-bind the persistent relaxation instead of rebuilding it.
     */
    fun rebind(lo: LongArray, hi: LongArray): LpModel {
        require(lo.size == n && hi.size == n) { "rebind expects $n bounds, got ${lo.size}/${hi.size}" }
        val newRhs = flippedRhs.copyOf()
        val newUpper = upper.copyOf()
        var newObjConstant = 0L
        for (j in 0 until n) {
            require(lo[j] <= hi[j]) { "empty domain [${lo[j]}, ${hi[j]}] for column $j" }
            forEachInColumn(j) { i, v -> newRhs[i] = subExact(newRhs[i], mulExact(v, lo[j])) }
            newUpper[j] = subExact(hi[j], lo[j])
            newObjConstant = addExact(newObjConstant, mulExact(cost[j], lo[j]))
        }
        return LpModel(
            n = n, m = m, csc = csc, rhs = newRhs, cost = cost,
            upper = newUpper, hasUpper = hasUpper, loShift = lo.copyOf(),
            objConstant = newObjConstant, sense = sense, tag = tag,
            rowGlobal = rowGlobal, rowPremises = rowPremises, flippedRhs = flippedRhs,
            probeClampedLo = probeClampedLo, probeClampedHi = probeClampedHi,
        )
    }

    /**
     * A model identical in structure and bounds but whose objective is a single unit cost [unitCost] on
     * structural column [col] and zero elsewhere — the per-variable objective optimization-based bound
     * tightening ([com.eignex.klause.lp.tightenOpenIntBounds]) swaps in for each open side. When the
     * variable is represented split (`x = x⁺ − x⁻`), [negCol] names the negative part and takes
     * `−unitCost`, so the objective is still exactly `unitCost·x`. The [cost] array is **shared and
     * mutated in place**: [col]/[negCol] are set and the columns of the previous call
     * ([prevCol]/[prevNegCol], `-1` on the first) are reset to `0`, so a whole sweep of single-column
     * objectives allocates no per-solve cost vector and each model is `O(1)` to form. The objective
     * constant tracks the nonzero columns' shifts (`unitCost·loShift`), matching a fresh build with that
     * cost, so the solve and its dual bound are identical to a per-objective rebuild. A [doubleView]
     * carries its own cost array and objective constant, mutated with the same in-place discipline (the
     * continuous-column solve reads them).
     */
    fun withSingleColumnObjective(
        col: Int,
        unitCost: Long,
        prevCol: Int,
        negCol: Int = -1,
        prevNegCol: Int = -1,
    ): LpModel {
        if (prevCol >= 0) cost[prevCol] = 0L
        if (prevNegCol >= 0) cost[prevNegCol] = 0L
        cost[col] = unitCost
        if (negCol >= 0) cost[negCol] = -unitCost
        var constant = mulExact(unitCost, loShift[col])
        if (negCol >= 0) constant = subExact(constant, mulExact(unitCost, loShift[negCol]))
        val dv = doubleView
        if (dv != null) {
            if (prevCol >= 0) dv.cost[prevCol] = 0.0
            if (prevNegCol >= 0) dv.cost[prevNegCol] = 0.0
            dv.cost[col] = unitCost.toDouble()
            if (negCol >= 0) dv.cost[negCol] = -unitCost.toDouble()
            dv.objConstant = dv.cost[col] * dv.loShift[col] +
                (if (negCol >= 0) dv.cost[negCol] * dv.loShift[negCol] else 0.0)
        }
        return LpModel(
            n = n, m = m, csc = csc, rhs = rhs, cost = cost,
            upper = upper, hasUpper = hasUpper, loShift = loShift,
            objConstant = constant, sense = sense, tag = tag,
            rowGlobal = rowGlobal, rowPremises = rowPremises, flippedRhs = flippedRhs,
            probeClampedLo = probeClampedLo, probeClampedHi = probeClampedHi,
            colContinuous = colContinuous,
            doubleView = dv,
        )
    }

    /**
     * A model identical in structure and bounds whose objective is `Σ coeffs[k]·x_cols[k]`, the
     * multi-column counterpart of [withSingleColumnObjective]. The boundedness test of a whole
     * *direction* — is `aᵢᵀx` bounded in this system — needs a row's coefficients as the objective, not
     * a unit cost on one column.
     *
     * Shares and mutates [cost] with the same discipline: [prevCols] (the columns the previous call set,
     * empty on the first) are reset to `0` before the new ones are written, so a sweep over every row
     * allocates no per-solve cost vector.
     */
    fun withRowObjective(cols: IntArray, coeffs: LongArray, prevCols: IntArray): LpModel {
        for (c in prevCols) cost[c] = 0L
        var constant = 0L
        for (k in cols.indices) {
            cost[cols[k]] = coeffs[k]
            constant = addExact(constant, mulExact(coeffs[k], loShift[cols[k]]))
        }
        val dv = doubleView
        if (dv != null) {
            for (c in prevCols) dv.cost[c] = 0.0
            var dc = 0.0
            for (k in cols.indices) {
                dv.cost[cols[k]] = coeffs[k].toDouble()
                dc += dv.cost[cols[k]] * dv.loShift[cols[k]]
            }
            dv.objConstant = dc
        }
        return LpModel(
            n = n, m = m, csc = csc, rhs = rhs, cost = cost,
            upper = upper, hasUpper = hasUpper, loShift = loShift,
            objConstant = constant, sense = sense, tag = tag,
            rowGlobal = rowGlobal, rowPremises = rowPremises, flippedRhs = flippedRhs,
            probeClampedLo = probeClampedLo, probeClampedHi = probeClampedHi,
            colContinuous = colContinuous,
            doubleView = dv,
        )
    }

    /** Column index of row `i`'s slack variable. */
    fun slackCol(i: Int): Int = n + i

    /**
     * Iterate the nonzero structural entries of column [j] as `(row, value)`, rows ascending, over the
     * CSC core — the column-oriented readers ([RevisedSimplex], [safeObjectiveLowerBound],
     * [integerCertify]) consume this. [j] must be a structural column (`< n`); slack columns are
     * the implicit unit vectors the callers handle separately.
     */
    inline fun forEachInColumn(j: Int, action: (row: Int, value: Long) -> Unit) {
        for (k in csc.colPtr[j] until csc.colPtr[j + 1]) action(csc.rowIdx[k], csc.colVal[k])
    }
}

/**
 * Compressed-sparse-column store of an [LpModel]'s structural columns: column `j` occupies
 * `rowIdx[colPtr[j] until colPtr[j + 1]]` with the parallel values in [colVal], row indices ascending.
 * Slack columns are the implicit unit vectors and are never stored. Built by [LpBuilder.build].
 */
internal class Csc(val colPtr: IntArray, val rowIdx: IntArray, val colVal: LongArray)

/**
 * The double-precision form of an [LpModel] with real coefficients, built by [LpBuilder.build] when any
 * continuous column or real-coefficient row is present. The CSC ([colPtr]/[rowIdx]/[colVal]) mirrors
 * [Csc] over the `n` structural columns (slacks are the implicit unit columns); [cost]/[upper]/[hasUpper]
 * are length `n + m` and [loShift] length `n`, all in double precision after the same lower-shift /
 * `>=`-to-`<=` normalizations the [Long] core applies.
 */
internal class LpDoubleView(
    val colPtr: IntArray,
    val rowIdx: IntArray,
    val colVal: DoubleArray,
    val rhs: DoubleArray,
    val cost: DoubleArray,
    val upper: DoubleArray,
    val hasUpper: BooleanArray,
    /** `Σ cost·loShift` folded out by the lower-bound shift; mutable because
     *  [LpModel.withSingleColumnObjective] rewrites [cost] in place and must keep this consistent. */
    var objConstant: Double,
    val loShift: DoubleArray,
)

/**
 * Builds an [LpModel] from structural variables and constraint rows. Coefficients are accumulated
 * sparsely during construction and emitted as the CSC core at [build]. The builder owns the
 * normalizations documented on [LpModel]; callers add variables and rows in natural `<=`/`>=`/`=`
 * form with integer data.
 *
 * Structural variables must have a finite lower bound (every klause integer variable does);
 * an infinite lower bound is rejected because the lower-shift normalization requires it.
 */
internal class LpBuilder {
    // Primitive-specialized accumulators: one Long/Int per variable, no per-element boxing.
    private val lo = LongArrayList()
    private val hi = LongArrayList()
    private val cost = LongArrayList()
    private val tags = IntArrayList()

    // Structural columns whose lower/upper bound is the finite [LP_UNBOUNDED_PROBE] stand-in for ±∞
    // ([addFreeVar]); surfaced on the built model as probeClampedLo/Hi. Empty in the common case.
    private val clampedLoCols = HashSet<Int>()
    private val clampedHiCols = HashSet<Int>()

    /** Structural columns added by [addOpenAboveVar]: genuinely `[lo, +∞)`, so they carry no upper. */
    private val openAboveCols = HashSet<Int>()

    // LP-only continuous columns and their real bounds/cost — the double data the [LpDoubleView] is built
    // from. Empty for the pure-integer core, which never materializes a double view.
    private val continuousCols = HashSet<Int>()
    private val contLo = HashMap<Int, Double>()
    private val contHi = HashMap<Int, Double>()
    private val contCost = HashMap<Int, Double>()
    private var anyRealRow = false

    // A row's coefficients as parallel primitive arrays (column index, value); no boxed map. A real-
    // coefficient row carries its doubles in [valsD]/[rhsD] and leaves [vals]/[rhs] as unused zeros (the
    // integer core drops it; the double view is authoritative).
    private class RawRow(
        val cols: IntArray,
        val vals: LongArray,
        val rel: Relation,
        val rhs: Long,
        val global: Boolean,
        val premises: LpRowPremises?,
        val valsD: DoubleArray? = null,
        val rhsD: Double? = null,
        val strict: Boolean = false,
    )

    private val rows = ArrayList<RawRow>()

    /** Number of structural variables added so far; valid column indices are `0 until varCount`. */
    val varCount: Int get() = lo.size

    /** Number of rows added so far; valid row indices are `0 until rowCount`. Lets a caller record
     *  which rows a given producer emitted (the relaxation cache). */
    val rowCount: Int get() = rows.size

    /**
     * Add a structural variable with domain `[lower, upper]` and objective coefficient [cost].
     * [tag] is an opaque caller identifier (e.g. an encoded `(varId, value)`) carried through to
     * [LpModel.tag] for reduced-cost fixing. Returns the variable's column index.
     */
    fun addVar(lower: Long, upper: Long, cost: Long = 0L, tag: Int = -1): Int {
        require(lower <= upper) { "empty domain [$lower, $upper]" }
        lo.add(lower)
        hi.add(upper)
        this.cost.add(cost)
        tags.add(tag)
        return lo.size - 1
    }

    /**
     * Add a structural variable that may be unbounded on either side: a null [lower]/[upper] means `−∞`
     * / `+∞`. The engine realizes each open side with the finite [LP_UNBOUNDED_PROBE] stand-in (so the
     * lower-shift normalization and the bounded-variable simplex are unchanged — a single ordinary
     * column, no free-variable ray) and flags it, so [safeVariableBound] can reject an optimum that only
     * reaches the probe frontier as truly unbounded. Callers thus express genuine `±∞`; the magnitude
     * stays private to this package.
     */
    fun addFreeVar(lower: Long?, upper: Long?, cost: Long = 0L, tag: Int = -1): Int {
        // A declared bound past the probe magnitude would make the stand-in box empty and reject a model
        // the caller never over-constrained; the open side widens to clear the declared one instead. The
        // side stays flagged, so [safeVariableBound] still refuses to read a bound off the frontier.
        val lo = lower ?: minOf(-LP_UNBOUNDED_PROBE, upper ?: -LP_UNBOUNDED_PROBE)
        val hi = upper ?: maxOf(LP_UNBOUNDED_PROBE, lo)
        val j = addVar(lo, hi, cost, tag)
        if (lower == null) clampedLoCols.add(j)
        if (upper == null) clampedHiCols.add(j)
        return j
    }

    /**
     * Add a structural column over `[lower, +∞)` that carries **no** upper bound at all, rather than the
     * [LP_UNBOUNDED_PROBE] stand-in [addFreeVar] uses. The simplex already runs unbounded-above columns —
     * every inequality slack is one — and the difference matters for certification: a refutation that
     * never reads an upper bound refutes the open model, whereas one resting on the probe only rules out
     * points inside a box the model never had.
     *
     * The cost is that bound extraction cannot see an optimum "ride to the frontier", which is how
     * [safeVariableBound] recognises an unbounded direction; a caller wanting a bound from such a column
     * must handle a genuinely unbounded optimum instead. Use [addFreeVar] unless refutation strength over
     * the open model is the point.
     */
    fun addOpenAboveVar(lower: Long, cost: Long = 0L, tag: Int = -1): Int {
        // The stand-in only has to keep the box non-empty for the float ride — the column is open above,
        // so nothing downstream reads this upper (certification declines on `hasUpper = false`). A model
        // whose own lower bound already exceeds the probe would otherwise build an empty domain.
        val j = addVar(lower, maxOf(lower, LP_UNBOUNDED_PROBE), cost, tag)
        openAboveCols.add(j)
        return j
    }

    /**
     * Add an LP-only **continuous** (real) column with domain `[lower, upper]` (a null side is `±∞`,
     * realized by the [LP_UNBOUNDED_PROBE] stand-in like [addFreeVar]) and real objective coefficient
     * [cost]. The column is present in the LP but absent from CP search; because it carries real data the
     * model is solved through the double view and the exact integer certification declines. Returns the
     * column index. The [Long] slot stays a placeholder zero — the double view is authoritative.
     */
    fun addRealVar(lower: Double?, upper: Double?, cost: Double = 0.0, tag: Int = -1): Int {
        val j = lo.size
        lo.add(0L)
        hi.add(0L)
        this.cost.add(0L)
        tags.add(tag)
        continuousCols.add(j)
        contLo[j] = lower ?: -LP_UNBOUNDED_PROBE.toDouble()
        contHi[j] = upper ?: LP_UNBOUNDED_PROBE.toDouble()
        contCost[j] = cost
        if (lower == null) clampedLoCols.add(j)
        if (upper == null) clampedHiCols.add(j)
        return j
    }

    /** Add a real-coefficient constraint `Σ vals(k)·x_{cols(k)} rel rhs`; like [addRow] but with double
     *  data. Forces the model onto the double view (and declines integer certification). */
    fun addRealRow(
        cols: IntArray,
        vals: DoubleArray,
        rel: Relation,
        rhs: Double,
        strict: Boolean = false,
        premiseLits: IntArray = EmptyIntArray,
    ) {
        require(cols.size == vals.size) { "cols/vals length mismatch: ${cols.size} vs ${vals.size}" }
        anyRealRow = true
        rows.add(
            RawRow(
                cols.copyOf(),
                LongArray(cols.size),
                rel,
                0L,
                global = premiseLits.isEmpty(),
                premises = if (premiseLits.isEmpty()) {
                    null
                } else {
                    LpRowPremises(EmptyIntArray, BooleanArray(0), EmptyLongArray, premiseLits.copyOf())
                },
                valsD = vals.copyOf(),
                rhsD = rhs,
                strict = strict,
            ),
        )
    }

    /**
     * Add a constraint `Σ vals[k]·x_{cols[k]}  rel  rhs`. [cols] are structural column indices as
     * returned by [addVar]; absent columns are zero, repeated columns are summed. The arrays are
     * copied, so the caller may reuse its buffers. [global] records whether the row holds at every
     * integer solution of the original problem (see [LpModel.rowGlobal]); pass `false` for rows
     * built from live, branch-tightened information — with [premises] naming the live bounds that
     * justify the row when they are expressible (see [LpRowPremises]).
     */
    fun addRow(
        cols: IntArray,
        vals: LongArray,
        rel: Relation,
        rhs: Long,
        global: Boolean = true,
        premises: LpRowPremises? = null,
    ) {
        require(cols.size == vals.size) { "cols/vals length mismatch: ${cols.size} vs ${vals.size}" }
        rows.add(RawRow(cols.copyOf(), vals.copyOf(), rel, rhs, global, premises))
    }

    /** Convenience overload for sparse maps (test call sites); unpacks into parallel arrays. */
    fun addRow(coeffs: Map<Int, Long>, rel: Relation, rhs: Long) {
        val cols = IntArray(coeffs.size)
        val vals = LongArray(coeffs.size)
        var k = 0
        for ((j, c) in coeffs) {
            cols[k] = j
            vals[k] = c
            k++
        }
        rows.add(RawRow(cols, vals, rel, rhs, global = true, premises = null))
    }

    /**
     * Materialize the normalized [LpModel] for the given [sense]. Only the CSC core of the structural
     * columns is built — the sparse revised simplex is the only LP engine, so the dense `m × n` matrix
     * is never allocated. Maximization is converted to minimization by negating the objective; the
     * reported objective re-applies the sign.
     */
    fun build(sense: Sense): LpModel {
        val n = lo.size
        val m = rows.size
        val rhs = LongArray(m)
        // Base rhs after the >=-to-<= flip but before the lower-bound shift; retained on the model so a
        // persistent relaxation can re-derive `rhs` for fresh bounds (see LpModel.rebind / flippedRhs).
        val flippedRhs = LongArray(m)
        val loShift = LongArray(n) { lo[it] }

        for ((i, row) in rows.withIndex()) {
            // Normalize >= to <= by negating both sides; == stays put (its slack is fixed at zero).
            val flip = row.rel == Relation.GE
            var b = if (flip) -row.rhs else row.rhs
            flippedRhs[i] = b
            for (k in row.cols.indices) {
                val j = row.cols[k]
                val coeff = if (flip) -row.vals[k] else row.vals[k]
                // Apply the lower-bound shift: substituting x_j = x'_j + lo_j moves the constant
                // coeff*lo_j across to the right-hand side.
                b = subExact(b, mulExact(coeff, lo[j]))
            }
            rhs[i] = b
        }

        val csc = buildCsc(n)

        val numVars = n + m
        val cost = LongArray(numVars)
        val upper = LongArray(numVars)
        val hasUpper = BooleanArray(numVars)
        val signedSense = if (sense == Sense.MAXIMIZE) -1L else 1L
        var objConstant = 0L
        for (j in 0 until n) {
            cost[j] = mulExact(signedSense, this.cost[j])
            upper[j] = subExact(hi[j], lo[j])
            hasUpper[j] = j !in openAboveCols
            // c_j·x_j = c_j·x'_j + c_j·lo_j; the shifted constants accumulate here.
            objConstant = addExact(objConstant, mulExact(cost[j], lo[j]))
        }
        for (i in 0 until m) {
            val sc = n + i
            cost[sc] = 0L
            upper[sc] = 0L
            // An equality slack is fixed at [0, 0] (finite upper); an inequality slack is [0, +inf).
            hasUpper[sc] = rows[i].rel == Relation.EQ
        }

        val doubleView = if (continuousCols.isNotEmpty() || anyRealRow) buildDoubleView(n, m, signedSense) else null

        return LpModel(
            n = n, m = m, csc = csc,
            rhs = rhs, cost = cost,
            upper = upper, hasUpper = hasUpper, loShift = loShift,
            objConstant = objConstant, sense = sense, tag = IntArray(n) { tags[it] },
            rowGlobal = BooleanArray(m) { rows[it].global },
            rowStrict = BooleanArray(m) { rows[it].strict },
            rowPremises = Array(m) { rows[it].premises },
            flippedRhs = flippedRhs,
            probeClampedLo = BooleanArray(n) { it in clampedLoCols },
            probeClampedHi = BooleanArray(n) { it in clampedHiCols },
            colContinuous = BooleanArray(n) { it in continuousCols },
            doubleView = doubleView,
        )
    }

    /** Build the double-precision [LpDoubleView] mirroring the [Long] normalizations (lower-shift,
     *  `>=`-to-`<=`) in double, from the real column data ([contLo]/[contHi]/[contCost]) and any real
     *  rows. Integer columns and rows contribute their exact [Long] values widened to double. */
    private fun buildDoubleView(n: Int, m: Int, signedSense: Long): LpDoubleView {
        val loD = DoubleArray(n) { contLo[it] ?: lo[it].toDouble() }
        val hiD = DoubleArray(n) { contHi[it] ?: hi[it].toDouble() }
        val costRawD = DoubleArray(n) { contCost[it] ?: this.cost[it].toDouble() }
        val rhsD = DoubleArray(m)
        for ((i, row) in rows.withIndex()) {
            val flip = row.rel == Relation.GE
            val rawRhs = row.rhsD ?: row.rhs.toDouble()
            var b = if (flip) -rawRhs else rawRhs
            val rvals = row.valsD ?: DoubleArray(row.vals.size) { row.vals[it].toDouble() }
            for (k in row.cols.indices) {
                val j = row.cols[k]
                val coeff = if (flip) -rvals[k] else rvals[k]
                b -= coeff * loD[j]
            }
            rhsD[i] = b
        }
        val colRowBuckets = Array(n) { IntArrayList() }
        val colValBuckets = Array(n) { ArrayList<Double>() }
        for ((i, row) in rows.withIndex()) {
            val flip = row.rel == Relation.GE
            val rvals = row.valsD ?: DoubleArray(row.vals.size) { row.vals[it].toDouble() }
            val summed = HashMap<Int, Double>(row.cols.size)
            for (k in row.cols.indices) {
                val j = row.cols[k]
                val coeff = if (flip) -rvals[k] else rvals[k]
                summed[j] = (summed[j] ?: 0.0) + coeff
            }
            for (j in summed.keys.sorted()) {
                val v = summed.getValue(j)
                if (v != 0.0) {
                    colRowBuckets[j].add(i)
                    colValBuckets[j].add(v)
                }
            }
        }
        val colPtr = IntArray(n + 1)
        for (j in 0 until n) colPtr[j + 1] = colPtr[j] + colRowBuckets[j].size
        val rowIdx = IntArray(colPtr[n])
        val colVal = DoubleArray(colPtr[n])
        for (j in 0 until n) {
            val br = colRowBuckets[j]
            val bv = colValBuckets[j]
            var w = colPtr[j]
            for (k in 0 until br.size) {
                rowIdx[w] = br[k]
                colVal[w] = bv[k]
                w++
            }
        }
        val numVars = n + m
        val costD = DoubleArray(numVars)
        val upperD = DoubleArray(numVars)
        val hasUpperD = BooleanArray(numVars)
        val signed = signedSense.toDouble()
        var objConstantD = 0.0
        for (j in 0 until n) {
            costD[j] = signed * costRawD[j]
            upperD[j] = hiD[j] - loD[j]
            hasUpperD[j] = true
            objConstantD += costD[j] * loD[j]
        }
        for (i in 0 until m) {
            hasUpperD[n + i] = rows[i].rel == Relation.EQ
        }
        return LpDoubleView(colPtr, rowIdx, colVal, rhsD, costD, upperD, hasUpperD, objConstantD, loD)
    }

    /** Build the CSC core over the `n` structural columns from the accumulated [rows]: `>=` rows are
     *  negated to `<=` (the row normalization), repeated columns within a row are summed,
     *  and entries land column-major with ascending row indices (rows walked in order). */
    private fun buildCsc(n: Int): Csc {
        val colRowBuckets = Array(n) { IntArrayList() }
        val colValBuckets = Array(n) { LongArrayList() }
        for ((i, row) in rows.withIndex()) {
            val flip = row.rel == Relation.GE
            val summed = MutableIntLongMap(row.cols.size)
            for (k in row.cols.indices) {
                val j = row.cols[k]
                val coeff = if (flip) -row.vals[k] else row.vals[k]
                summed.put(j, addExact(summed.getOrDefault(j, 0L), coeff))
            }
            // Ascending column order keeps the CSC deterministic; i ascends ⇒ rows ascend within a column.
            val summedCols = IntArrayList()
            summed.forEach { j, _ -> summedCols.add(j) }
            val sortedCols = summedCols.toSortedIntArray()
            for (j in sortedCols) {
                val v = summed.getOrDefault(j, 0L)
                if (v != 0L) {
                    colRowBuckets[j].add(i)
                    colValBuckets[j].add(v)
                }
            }
        }
        val colPtr = IntArray(n + 1)
        for (j in 0 until n) colPtr[j + 1] = colPtr[j] + colRowBuckets[j].size
        val rowIdx = IntArray(colPtr[n])
        val colVal = LongArray(colPtr[n])
        for (j in 0 until n) {
            val br = colRowBuckets[j]
            val bv = colValBuckets[j]
            var w = colPtr[j]
            for (k in 0 until br.size) {
                rowIdx[w] = br[k]
                colVal[w] = bv[k]
                w++
            }
        }
        return Csc(colPtr, rowIdx, colVal)
    }
}
