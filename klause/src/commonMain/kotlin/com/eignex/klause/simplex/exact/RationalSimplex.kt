package com.eignex.klause.simplex.exact

import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.EmptyIntArray
import com.eignex.klause.util.IntArrayList
import com.ionspin.kotlin.bignum.integer.BigInteger

/**
 * Exact rational feasibility of an [ExactSimplexModel]: slack-form rows `A·x = rhs` over boxes `0 ≤ xⱼ ≤ uⱼ`
 * (`hasUpper[j]` false ⇒ open above). Coefficients come from the double view when the model has one —
 * every finite double is exactly a rational `±m·2ᵉ`, so no scaling ladder or tolerance is involved —
 * and from the Long arrays otherwise. The last-resort certifier runs
 * only when the float solve plus the cheap exact checks leave the verdict INDETERMINATE, and decides
 * it in exact arithmetic.
 *
 * The method is a bounded-variable feasibility simplex on a sparse fraction tableau, starting from
 * the slack basis. The objective is identically zero, so every basis is dual-feasible and no dual
 * ratio test is needed: repeatedly take the least-index basic variable outside its box, move it exactly
 * onto the violated bound with the least-index sign-eligible nonbasic column, and pivot. When no
 * eligible column exists the row itself is an exact certificate of infeasibility (the row expresses
 * the basic variable as its extreme attainable value, which still violates the box). Least-index
 * (Bland-style) selection plus the `maxPivots` cap bounds the run; a capped or cancelled run returns
 * [RationalFeasibility.UNKNOWN] — sound, the verdict just stays undecided.
 *
 * The arithmetic is two-level ([FracOps]): the fixed-width 128-bit fraction level ([Frac128Ops])
 * runs first — the common case at a leaf, two-word integer kernels, no big-integer work — and the
 * run escalates to the unbounded [BigFraction] level only when a pivot chain genuinely escapes 128
 * bits (the level latches its overflow flag, the run is voided, and the whole solve restarts at the
 * big level, so every reported verdict is computed in one consistent arithmetic).
 */
internal enum class RationalFeasibility { FEASIBLE, INFEASIBLE, UNKNOWN }

/** The exact verdict plus, on FEASIBLE, a concrete structural-column witness in the source model's
 *  coordinates (evaluated at a small positive delta when strict rows are present). */
internal class RationalOutcome(
    val feasibility: RationalFeasibility,
    val witness: DoubleArray? = null,
    /** On [RationalFeasibility.INFEASIBLE], the original rows carrying nonzero Farkas weight — the
     *  violated row's tableau slack coefficients are exactly `B⁻ᵀe_r` — so an explanation cites only
     *  the load-bearing rows. Null otherwise. */
    val rows: IntArray? = null,
)

/** Exact feasibility outcome for consumers that must retain a rational witness rather than round it
 *  back through the floating LP interface. */
internal class BigRationalOutcome(
    val feasibility: RationalFeasibility,
    val witness: List<BigFraction>? = null,
    /**
     * Final exact slack-form rows when feasibility was established.  Each row is
     * `x(basic) = rhs - sum(coefficients(k) * x(columns(k)))`; `columns` contains
     * only non-basic variables.  This is intentionally an exact-theory boundary:
     * mixed-integer clients can separate a cut without rebuilding a floating basis.
     */
    val tableau: List<BigRationalTableauRow>? = null,
)

/** Exact outcome of minimizing a linear activity over an [ExactSimplexModel]. */
internal class BigRationalOptimizationOutcome(
    val feasibility: RationalFeasibility,
    /** The exact infimum when [feasibility] is [RationalFeasibility.FEASIBLE]. */
    val infimum: BigFraction? = null,
    /** True when the activity descends along an open cone direction. */
    val unbounded: Boolean = false,
)

/** One final exact simplex row, expressed in the original structural/slack column space. */
internal class BigRationalTableauRow(
    val basic: Int,
    val rhs: BigFraction,
    val columns: IntArray,
    val coefficients: List<BigFraction>,
)

/**
 * An arithmetic level for the exact simplex: a rational number type with exact operations. A
 * fixed-width level signals exhaustion by latching [overflowed] — its results are then void and the
 * caller escalates; the unbounded level never latches. [ofDouble] returns null for a value the
 * level cannot represent (non-finite always; out-of-range for a fixed-width level).
 */
internal interface FracOps<F> {
    val zero: F
    val one: F
    val minusOne: F
    val half: F

    fun ofLong(v: Long): F

    fun ofDouble(v: Double): F?

    fun plus(a: F, b: F): F

    fun minus(a: F, b: F): F

    fun times(a: F, b: F): F

    fun reciprocal(a: F): F

    fun signum(a: F): Int

    fun compare(a: F, b: F): Int

    fun toDouble(a: F): Double

    fun overflowed(): Boolean

    fun isZero(a: F): Boolean = signum(a) == 0
}

/** The unbounded [BigFraction] level; never overflows. */
internal object BigFracOps : FracOps<BigFraction> {
    override val zero: BigFraction = BigFraction.ZERO
    override val one: BigFraction = BigFraction.ONE
    override val minusOne: BigFraction = BigFraction.MINUS_ONE
    override val half: BigFraction = BigFraction.of(BigInteger.ONE, BigInteger.TWO)

    override fun ofLong(v: Long): BigFraction = BigFraction.ofLong(v)

    override fun ofDouble(v: Double): BigFraction? = BigFraction.ofDouble(v)

    override fun plus(a: BigFraction, b: BigFraction): BigFraction = a + b

    override fun minus(a: BigFraction, b: BigFraction): BigFraction = a - b

    override fun times(a: BigFraction, b: BigFraction): BigFraction = a * b

    override fun reciprocal(a: BigFraction): BigFraction = a.reciprocal()

    override fun signum(a: BigFraction): Int = a.signum()

    override fun compare(a: BigFraction, b: BigFraction): Int = a.compareTo(b)

    override fun toDouble(a: BigFraction): Double = a.toDouble()

    override fun overflowed(): Boolean = false

    override fun isZero(a: BigFraction): Boolean = a.isZero
}

/** Engine-neutral normalized LP input consumed by the exact feasibility simplex. */
internal interface ExactSimplexModel {
    val n: Int
    val m: Int
    val numVars: Int
    val rhs: LongArray
    val upper: LongArray
    val hasUpper: BooleanArray
    val rowStrict: BooleanArray
    val probeClampedLo: BooleanArray
    val probeClampedHi: BooleanArray
    val doubleView: ExactSimplexDoubleView?

    /**
     * Optional arbitrary-precision input view.  The ordinary LP engine provides either its compact
     * integer columns or a double view; reduction kernels use this view when a source coefficient or
     * bound does not fit either representation.  It deliberately lives at the exact-simplex boundary
     * so callers do not have to smuggle big coefficients through a floating point relaxation.
     */
    val bigView: ExactSimplexBigView?
        get() = null

    fun forEachExactColumn(j: Int, action: (row: Int, value: Long) -> Unit)

    fun loShiftD(j: Int): Double
}

/** Double-precision normalized LP input used when the model contains real data. */
internal interface ExactSimplexDoubleView {
    val colPtr: IntArray
    val rowIdx: IntArray
    val colVal: DoubleArray
    val rhs: DoubleArray
    val upper: DoubleArray
    val hasUpper: BooleanArray
}

/** Arbitrary-precision counterpart of [ExactSimplexDoubleView]. */
internal class ExactSimplexBigView(
    val colPtr: IntArray,
    val rowIdx: IntArray,
    val colVal: List<BigFraction>,
    val rhs: List<BigFraction>,
    val upper: List<BigFraction?>,
)

/** One exact `a*x <= rhs` row for the unshifted non-negative simplex form. */
internal class ExactRationalInequality(
    val columns: IntArray,
    val coefficients: List<BigFraction>,
    val rhs: BigFraction,
    val strict: Boolean = false,
) {
    init {
        require(columns.size == coefficients.size) { "exact row columns and coefficients differ in size" }
        for (index in 1 until columns.size) {
            require(columns[index - 1] < columns[index]) { "exact row columns must be strictly ascending" }
        }
    }
}

/**
 * Exact arbitrary-precision feasibility model with non-negative structural columns.
 *
 * Free source variables are represented by callers as a positive and a negative column.  Keeping this
 * representation explicit makes homogeneous direction tests exact even for coefficients larger than a
 * machine word, and avoids introducing a numeric probe while classifying bounded rows.
 */
internal class ExactRationalFeasibilityModel(
    override val n: Int,
    rows: List<ExactRationalInequality>,
    structuralUpper: List<BigFraction?> = List(n) { null },
) : ExactSimplexModel {
    override val m: Int = rows.size
    override val numVars: Int = n + m
    override val rhs: LongArray = LongArray(m)
    override val upper: LongArray = LongArray(numVars)
    override val hasUpper: BooleanArray = BooleanArray(numVars)
    override val rowStrict: BooleanArray = BooleanArray(m) { rows[it].strict }
    override val probeClampedLo: BooleanArray = BooleanArray(n)
    override val probeClampedHi: BooleanArray = BooleanArray(n)
    override val doubleView: ExactSimplexDoubleView? = null

    override val bigView: ExactSimplexBigView

    init {
        require(structuralUpper.size == n) { "exact structural upper bounds differ from variable count" }
        val counts = IntArray(n)
        for (row in rows) {
            for (column in row.columns) {
                require(column in 0 until n) { "exact row column $column is outside 0 until $n" }
                counts[column]++
            }
        }
        val colPtr = IntArray(n + 1)
        for (column in 0 until n) colPtr[column + 1] = colPtr[column] + counts[column]
        val next = colPtr.copyOf()
        val rowIdx = IntArray(colPtr[n])
        val colVal = MutableList(colPtr[n]) { BigFraction.ZERO }
        for ((rowIndex, row) in rows.withIndex()) {
            for (entry in row.columns.indices) {
                val at = next[row.columns[entry]]++
                rowIdx[at] = rowIndex
                colVal[at] = row.coefficients[entry]
            }
        }
        bigView = ExactSimplexBigView(
            colPtr,
            rowIdx,
            colVal,
            rows.map(ExactRationalInequality::rhs),
            structuralUpper + List(m) { null },
        )
    }

    override fun forEachExactColumn(j: Int, action: (row: Int, value: Long) -> Unit) = Unit

    override fun loShiftD(j: Int): Double = 0.0
}

/**
 * Classify the row directions that are bounded by an exact homogeneous cone.
 *
 * A row `a*x <= b` has a finite lower activity bound precisely when its own activity descends along no
 * direction of the recession cone ([exactDescendingDirection]).
 *
 * A `null` result means cancellation or an interrupted exact simplex run; callers must keep the source
 * system rather than treating an undecided direction as bounded.
 */
internal fun exactBoundedRows(
    rows: List<ExactRationalInequality>,
    variables: Int,
    cancellation: Cancellation = Cancellation.Never,
): BooleanArray? {
    val bounded = BooleanArray(rows.size)
    for (target in rows.indices) {
        if (cancellation()) return null
        bounded[target] = !(exactDescendingDirection(rows, rows[target], variables, cancellation) ?: return null)
    }
    return bounded
}

/**
 * Whether the recession cone of [rows] carries a direction along which [activity] strictly decreases,
 * or `null` when the exact run was cut short.
 *
 * Such a direction exists exactly when `A*d <= 0` together with `a*d <= -1` is feasible, so the test
 * needs no numeric search radius and remains valid for coefficients and rational literals of arbitrary
 * precision. Strictness is intentionally ignored: it changes feasible offsets, not the recession cone.
 *
 * The affirmative answer is what a caller can build on. A cone direction is a ray of the system itself,
 * so an activity that descends along one is unbounded below over every point of it.
 */
internal fun exactDescendingDirection(
    rows: List<ExactRationalInequality>,
    activity: ExactRationalInequality,
    variables: Int,
    cancellation: Cancellation = Cancellation.Never,
): Boolean? {
    val coneRows = ArrayList<ExactRationalInequality>(rows.size + 1)
    for (row in rows) coneRows.add(row.homogeneousOverSplit(variables, BigFraction.ZERO))
    coneRows.add(activity.homogeneousOverSplit(variables, BigFraction.MINUS_ONE))
    return when (
        bigRationalOutcome(
            ExactRationalFeasibilityModel(2 * variables, coneRows),
            cancellation,
            Int.MAX_VALUE,
        ).feasibility
    ) {
        RationalFeasibility.FEASIBLE -> true
        RationalFeasibility.INFEASIBLE -> false
        RationalFeasibility.UNKNOWN -> null
    }
}

/** One source row together with the finite lower activity that makes it double-bounded. */
internal class ExactDoubleBoundedRow(val index: Int, val inequality: ExactRationalInequality, val lower: BigFraction)

/** Exact Double-Bounded Reduction split before its mixed-echelon/Hermite column transformation. */
internal sealed interface ExactDoubleBoundedSplit {
    data object Infeasible : ExactDoubleBoundedSplit

    data object Unknown : ExactDoubleBoundedSplit

    class Split(val bounded: List<ExactDoubleBoundedRow>, val unbounded: List<Int>) : ExactDoubleBoundedSplit
}

/**
 * Construct the Double-Bounded Reduction split for an exact rational inequality system.
 *
 * The homogeneous cone first identifies precisely the rows with finite lower activity. Each of those
 * activities is then minimized over the original system, yielding the explicit `lower <= a*x <= rhs`
 * row required by the bounded branch-and-bound phase. Rows without a finite lower activity are retained
 * by index as the absolutely unbounded lane used later for mixed witness extension. No result is exposed
 * if cancellation interrupts either phase.
 */
internal fun exactDoubleBoundedSplit(
    rows: List<ExactRationalInequality>,
    variables: Int,
    cancellation: Cancellation = Cancellation.Never,
): ExactDoubleBoundedSplit {
    val bounded = exactBoundedRows(rows, variables, cancellation) ?: return ExactDoubleBoundedSplit.Unknown
    val splitRows = rows.map { row -> row.homogeneousOverSplit(variables, row.rhs, row.strict) }
    val model = ExactRationalFeasibilityModel(2 * variables, splitRows)
    val result = ArrayList<ExactDoubleBoundedRow>()
    val unbounded = ArrayList<Int>()
    for (index in rows.indices) {
        if (cancellation()) return ExactDoubleBoundedSplit.Unknown
        if (!bounded[index]) {
            unbounded.add(index)
            continue
        }
        val costs = MutableList(2 * variables) { BigFraction.ZERO }
        val row = rows[index]
        for (entry in row.columns.indices) {
            val column = row.columns[entry]
            costs[column] = row.coefficients[entry]
            costs[variables + column] = row.coefficients[entry].negated()
        }
        val minimum = bigRationalMinimum(model, costs, cancellation)
        when (minimum.feasibility) {
            RationalFeasibility.INFEASIBLE -> return ExactDoubleBoundedSplit.Infeasible

            RationalFeasibility.UNKNOWN -> return ExactDoubleBoundedSplit.Unknown

            RationalFeasibility.FEASIBLE -> {
                if (minimum.unbounded) return ExactDoubleBoundedSplit.Unknown
                result.add(ExactDoubleBoundedRow(index, row, checkNotNull(minimum.infimum)))
            }
        }
    }
    return ExactDoubleBoundedSplit.Split(result, unbounded)
}

/**
 * Construct a mixed witness for an absolutely unbounded system.
 *
 * The rational columns are kept at the exact centre returned by simplex.  Every integer column is
 * rounded from a centre whose row right-hand side has been reduced by half that row's integer
 * one-norm.  The unit cube around the centre therefore remains inside every row.  Strict rows keep
 * their strict endpoint, so rounding cannot turn a valid strict centre into a boundary point.
 *
 * For the split systems produced by Double-Bounded Reduction, Lemma 22 of Bromberger's reduction
 * supplies exactly the absolute-unboundedness premise.  A null result is consequently reserved for
 * cancellation or an interrupted exact solve; callers must not substitute a finite search box.
 */
internal fun exactMixedUnitCubeSolution(
    rows: List<ExactRationalInequality>,
    realColumns: Int,
    integerColumns: Int,
    cancellation: Cancellation = Cancellation.Never,
): List<BigFraction>? {
    val variables = realColumns + integerColumns
    val shifted = rows.map { row ->
        var integerNorm = BigFraction.ZERO
        for (index in row.columns.indices) {
            if (row.columns[index] >= realColumns) {
                val coefficient = row.coefficients[index]
                integerNorm += if (coefficient < BigFraction.ZERO) coefficient.negated() else coefficient
            }
        }
        ExactRationalInequality(
            row.columns,
            row.coefficients,
            row.rhs - integerNorm * BigFracOps.half,
            row.strict,
        )
    }
    val outcome = bigRationalOutcome(
        ExactRationalFeasibilityModel(
            2 * variables,
            shifted.map { row -> row.homogeneousOverSplit(variables, row.rhs, row.strict) },
        ),
        cancellation,
        Int.MAX_VALUE,
    )
    if (outcome.feasibility != RationalFeasibility.FEASIBLE || cancellation()) return null
    val centre = checkNotNull(outcome.witness)
    val candidate = List(variables) { column ->
        val value = centre[column] - centre[variables + column]
        if (column < realColumns) {
            value
        } else {
            (value + BigFracOps.half).floorExact().let {
                BigFraction.of(it, BigInteger.ONE)
            }
        }
    }
    return candidate.takeIf { candidate -> candidate.satisfiesExactRows(rows) }
}

private fun BigFraction.floorExact(): BigInteger {
    val quotient = num / den
    return if (num < BigInteger.ZERO && num % den != BigInteger.ZERO) quotient - BigInteger.ONE else quotient
}

private fun List<BigFraction>.satisfiesExactRows(rows: List<ExactRationalInequality>): Boolean = rows.all { row ->
    var activity = BigFraction.ZERO
    for (index in row.columns.indices) activity += this[row.columns[index]] * row.coefficients[index]
    if (row.strict) activity < row.rhs else activity <= row.rhs
}

private fun ExactRationalInequality.homogeneousOverSplit(
    variables: Int,
    rhs: BigFraction,
    strict: Boolean = false,
): ExactRationalInequality {
    val splitTerms = ArrayList<Pair<Int, BigFraction>>(2 * columns.size)
    for (index in columns.indices) {
        val variable = columns[index]
        require(variable in 0 until variables) { "exact row column $variable is outside 0 until $variables" }
        splitTerms.add(variable to coefficients[index])
        splitTerms.add(variables + variable to coefficients[index].negated())
    }
    splitTerms.sortBy { it.first }
    return ExactRationalInequality(
        splitTerms.map { it.first }.toIntArray(),
        splitTerms.map { it.second },
        rhs,
        strict,
    )
}

internal fun rationalFeasible(
    model: ExactSimplexModel,
    cancellation: Cancellation = Cancellation.Never,
    maxPivots: Int = defaultRationalPivotCap(model),
): RationalFeasibility = rationalOutcome(model, cancellation, maxPivots).feasibility

internal fun rationalOutcome(
    model: ExactSimplexModel,
    cancellation: Cancellation = Cancellation.Never,
    maxPivots: Int = defaultRationalPivotCap(model),
): RationalOutcome {
    if (model.m == 0) return RationalOutcome(RationalFeasibility.FEASIBLE, DoubleArray(model.n))
    // Fixed-width level first; a voided run (latched overflow / unrepresentable input) escalates.
    runSimplex(Frac128Ops(), model, cancellation, maxPivots)?.let { return it }
    return runSimplex(BigFracOps, model, cancellation, maxPivots)
        ?: RationalOutcome(RationalFeasibility.UNKNOWN)
}

/** Decide [model] entirely with arbitrary-precision rationals and retain its structural witness. */
internal fun bigRationalOutcome(
    model: ExactSimplexModel,
    cancellation: Cancellation = Cancellation.Never,
    maxPivots: Int = defaultRationalPivotCap(model),
): BigRationalOutcome {
    if (model.m == 0) {
        return BigRationalOutcome(
            RationalFeasibility.FEASIBLE,
            List(model.n) { BigFraction.ZERO },
        )
    }
    val state = buildState(BigFracOps, model) ?: return BigRationalOutcome(RationalFeasibility.UNKNOWN)
    var pivots = 0
    while (true) {
        if (cancellation.isCancelled() || pivots >= maxPivots) {
            return BigRationalOutcome(RationalFeasibility.UNKNOWN)
        }
        state.refreshBasicValues()
        val row = state.selectViolatedRow()
        if (row < 0) {
            return BigRationalOutcome(
                RationalFeasibility.FEASIBLE,
                structuralBigWitness(state),
                bigTableau(state),
            )
        }
        val enter = state.selectEnteringColumn(row)
        if (enter < 0) return BigRationalOutcome(RationalFeasibility.INFEASIBLE)
        state.pivot(row, enter)
        pivots++
    }
}

/**
 * Minimize a structural-column activity in arbitrary-precision arithmetic.
 *
 * The feasibility phase is the same Bland-style bounded-variable simplex used by
 * [bigRationalOutcome].  Once it has a feasible basis, primal pivots choose the least-index improving
 * non-basic column and the least-index tied blocker.  This is the exact optimization primitive needed
 * by Double-Bounded Reduction to materialize a row's finite lower activity, rather than replacing it
 * with an arbitrary witness radius.  Strict rows are optimized over their closure: their infimum is a
 * valid lower activity even when strictness prevents it from being attained.
 */
internal fun bigRationalMinimum(
    model: ExactSimplexModel,
    costs: List<BigFraction>,
    cancellation: Cancellation = Cancellation.Never,
    maxPivots: Int = Int.MAX_VALUE,
): BigRationalOptimizationOutcome {
    require(costs.size == model.n) { "exact objective has ${costs.size} columns, expected ${model.n}" }
    val state = buildState(BigFracOps, model) ?: return BigRationalOptimizationOutcome(RationalFeasibility.UNKNOWN)
    var pivots = 0
    while (true) {
        if (cancellation.isCancelled() || pivots >= maxPivots) {
            return BigRationalOptimizationOutcome(RationalFeasibility.UNKNOWN)
        }
        state.refreshBasicValues()
        val row = state.selectViolatedRow()
        if (row < 0) break
        val enter = state.selectEnteringColumn(row)
        if (enter < 0) return BigRationalOptimizationOutcome(RationalFeasibility.INFEASIBLE)
        state.pivot(row, enter)
        pivots++
    }
    while (true) {
        if (cancellation.isCancelled() || pivots >= maxPivots) {
            return BigRationalOptimizationOutcome(RationalFeasibility.UNKNOWN)
        }
        state.refreshBasicValues()
        val enter = state.selectImprovingColumn(costs)
        if (enter < 0) {
            return BigRationalOptimizationOutcome(
                RationalFeasibility.FEASIBLE,
                state.objectiveValue(costs),
            )
        }
        val blocker = state.selectObjectiveBlocker(enter)
        if (blocker == null) {
            return BigRationalOptimizationOutcome(RationalFeasibility.FEASIBLE, unbounded = true)
        }
        if (blocker.row < 0) {
            state.flipNonbasicBound(enter)
        } else {
            state.pivotAtBound(blocker.row, enter, blocker.upper)
        }
        pivots++
    }
}

/** One simplex run at arithmetic level [ops]; null when the level cannot carry it (escalate). At the
 *  unbounded level null only arises from a non-finite input coefficient, which no level can carry —
 *  the caller maps that to UNKNOWN. */
private fun <F> runSimplex(
    ops: FracOps<F>,
    model: ExactSimplexModel,
    cancellation: Cancellation,
    maxPivots: Int,
): RationalOutcome? {
    val st = buildState(ops, model) ?: return null
    var pivots = 0
    while (true) {
        if (ops.overflowed()) return null
        if (cancellation.isCancelled() || pivots >= maxPivots) return unknownOutcome()
        st.refreshBasicValues()
        val row = st.selectViolatedRow()
        if (ops.overflowed()) return null
        if (row < 0) {
            val witness = structuralWitness(ops, st)
            // A latched overflow during witness extraction voids the run: the verdict may stand but
            // the point does not, and the big-level rerun produces both consistently.
            if (ops.overflowed()) return null
            return RationalOutcome(RationalFeasibility.FEASIBLE, witness)
        }
        val enter = st.selectEnteringColumn(row)
        if (enter < 0) return st.refutation(row)
        st.pivot(row, enter)
        pivots++
    }
}

private fun unknownOutcome(): RationalOutcome = RationalOutcome(RationalFeasibility.UNKNOWN)

/**
 * The initial state: the tableau `[A | I]` with the slack basis, so `x_slack(i) = rhs(i) −
 * Σ_struct A(i,j)·x(j)` with every structural column nonbasic at zero. Null when a coefficient is
 * not representable at this arithmetic level. The row lengths are counted from the model's own
 * sparsity first, so the tableau starts at the model's nonzero count and nothing quadratic in the
 * model size is ever allocated up front.
 */
private fun <F> buildState(ops: FracOps<F>, model: ExactSimplexModel): SimplexState<F>? {
    val m = model.m
    val dv = model.doubleView
    val bv = model.bigView
    val tab = SparseTableau(ops, m, model.numVars)
    val counts = IntArray(m)
    if (bv != null) {
        for (j in 0 until model.n) for (p in bv.colPtr[j] until bv.colPtr[j + 1]) counts[bv.rowIdx[p]]++
    } else if (dv != null) {
        for (j in 0 until model.n) for (p in dv.colPtr[j] until dv.colPtr[j + 1]) counts[dv.rowIdx[p]]++
    } else {
        for (j in 0 until model.n) model.forEachExactColumn(j) { i, _ -> counts[i]++ }
    }
    for (i in 0 until m) tab.reserveRow(i, counts[i] + 1)
    if (bv != null) {
        if (ops !== BigFracOps) return null
        for (j in 0 until model.n) {
            for (p in bv.colPtr[j] until bv.colPtr[j + 1]) {
                @Suppress("UNCHECKED_CAST")
                tab.append(bv.rowIdx[p], j, bv.colVal[p] as F)
            }
        }
    } else if (dv != null) {
        for (j in 0 until model.n) {
            for (p in dv.colPtr[j] until dv.colPtr[j + 1]) {
                tab.append(dv.rowIdx[p], j, ops.ofDouble(dv.colVal[p]) ?: return null)
            }
        }
    } else {
        for (j in 0 until model.n) model.forEachExactColumn(j) { i, a -> tab.append(i, j, ops.ofLong(a)) }
    }
    for (i in 0 until m) tab.append(i, model.n + i, ops.one)
    // A strict row `a·x < b` enters the delta-ordered field as `a·x ≤ b − δ`: the rhs carries a −1
    // delta component, and lexicographic feasibility is exactly strict feasibility of the original.
    // Only the rhs ever carries a delta part, so the tableau itself stays delta-free.
    val rhsA = MutableList(m) { ops.zero }
    val rhsD = MutableList(m) { ops.zero }
    for (i in 0 until m) {
        rhsA[i] = when {
            bv != null -> {
                if (ops !== BigFracOps) return null
                @Suppress("UNCHECKED_CAST")
                bv.rhs[i] as F
            }

            dv != null -> ops.ofDouble(dv.rhs[i]) ?: return null

            else -> ops.ofLong(model.rhs[i])
        }
        if (model.rowStrict[i]) rhsD[i] = ops.minusOne
    }
    val uppers = MutableList<F?>(model.numVars) { null }
    for (j in 0 until model.numVars) {
        if (bv != null) {
            if (ops !== BigFracOps) return null
            @Suppress("UNCHECKED_CAST")
            val upper = bv.upper[j] as F?
            uppers[j] = upper
            continue
        }
        if (!model.hasUpper[j]) continue
        uppers[j] = if (dv != null) ops.ofDouble(dv.upper[j]) ?: return null else ops.ofLong(model.upper[j])
    }
    return SimplexState(ops, model, tab, rhsA, rhsD, uppers)
}

/** The live simplex state: tableau, right-hand side (real and delta parts), basis, nonbasic bound
 *  flags, and the basic values derived from them. */
private class SimplexState<F>(
    val ops: FracOps<F>,
    val model: ExactSimplexModel,
    val tab: SparseTableau<F>,
    val rhsA: MutableList<F>,
    val rhsD: MutableList<F>,
    val uppers: MutableList<F?>,
) {
    val basis = IntArray(model.m) { model.n + it }
    val inBasisRow = IntArray(model.numVars) { -1 }

    /** Nonbasic columns sit at a bound; false = lower (0), true = upper (u). */
    val atUpper = BooleanArray(model.numVars)

    /** Real part of each basic value, as of the last [refreshBasicValues]; the delta part is [rhsD],
     *  since only the right-hand side ever carries one. */
    val basicA = MutableList(model.m) { ops.zero }

    /** The nonbasic columns pinned at a finite upper bound, ascending — the only columns that shift
     *  a basic value off its right-hand side. Empty at the slack basis and grown one pivot at a
     *  time, which is what lets [refreshBasicValues] cost their nonzeros rather than the tableau. */
    private val upperCols = IntArrayList(4)

    /** Set by [selectViolatedRow] alongside its result: the direction the violated basic variable
     *  must move, and the bound it is pinned at once it leaves. */
    var needIncrease = false
        private set

    var targetUpper = false
        private set

    init {
        for (i in 0 until model.m) inBasisRow[basis[i]] = i
    }

    /** `x_B(i) = rhs(i) − Σ_{nonbasic j at upper} T(i,j)·u(j)`, accumulated column-wise. */
    fun refreshBasicValues() {
        for (i in 0 until model.m) basicA[i] = rhsA[i]
        for (k in 0 until upperCols.size) {
            val j = upperCols[k]
            val u = uppers[j] ?: continue
            for (r in tab.columnRows(j)) {
                val c = tab.get(r, j)
                if (!ops.isZero(c)) basicA[r] = ops.minus(basicA[r], ops.times(c, u))
            }
        }
    }

    /** The row of the least-index basic variable outside its box, or `-1` when every one is inside. */
    fun selectViolatedRow(): Int {
        var row = -1
        for (i in 0 until model.m) {
            val bvA = basicA[i]
            val u = uppers[basis[i]]
            if (deltaSignum(bvA, rhsD[i]) < 0) {
                if (row < 0 || basis[i] < basis[row]) {
                    row = i
                    needIncrease = true
                    targetUpper = false
                }
            } else if (u != null && deltaSignum(ops.minus(bvA, u), rhsD[i]) > 0) {
                if (row < 0 || basis[i] < basis[row]) {
                    row = i
                    needIncrease = false
                    targetUpper = true
                }
            }
        }
        return row
    }

    /**
     * Least-index nonbasic column that can move the violated variable toward its box, or `-1`.
     * Increasing `x_B(row)` means decreasing `Σ T(row,j)·x(j)`: a column at lower moving up needs
     * `T < 0`, a column at upper moving down needs `T > 0` (mirrored for decreasing). A row's
     * entries are stored ascending in column index, so the forward scan is least-index.
     */
    fun selectEnteringColumn(row: Int): Int {
        for (k in 0 until tab.rowSize(row)) {
            val j = tab.colAt(row, k)
            if (inBasisRow[j] >= 0) continue
            val c = tab.valAt(row, k)
            if (ops.isZero(c)) continue
            val movesUp = if (atUpper[j]) ops.signum(c) > 0 else ops.signum(c) < 0
            if (movesUp == needIncrease) return j
        }
        return -1
    }

    /**
     * With no eligible column the violated variable already sits at its extreme attainable value:
     * exact infeasibility — unless the proof leans on a probe stand-in bound (a `±∞` side realized
     * as a huge finite box). Infeasibility relative to the probe box does not refute the true
     * unbounded model, so such a proof degrades to UNKNOWN. Null voids the run (latched overflow).
     */
    fun refutation(row: Int): RationalOutcome? {
        val leaving = basis[row]
        if (leaving < model.n && (model.probeClampedHi[leaving] || model.probeClampedLo[leaving])) {
            return unknownOutcome()
        }
        for (k in 0 until tab.rowSize(row)) {
            val j = tab.colAt(row, k)
            if (inBasisRow[j] >= 0 || ops.isZero(tab.valAt(row, k))) continue
            if (atUpper[j] && j < model.n && model.probeClampedHi[j]) return unknownOutcome()
        }
        val certRows = ArrayList<Int>()
        if (leaving >= model.n) certRows.add(leaving - model.n)
        for (k in 0 until tab.rowSize(row)) {
            val j = tab.colAt(row, k)
            if (j < model.n || j == leaving || ops.isZero(tab.valAt(row, k))) continue
            certRows.add(j - model.n)
        }
        if (ops.overflowed()) return null
        return RationalOutcome(RationalFeasibility.INFEASIBLE, rows = certRows.toIntArray())
    }

    /** Pivot fully: the leaving variable lands exactly on its violated bound; solve [row] for [enter]. */
    fun pivot(row: Int, enter: Int) {
        val leave = basis[row]
        val inv = ops.reciprocal(tab.get(row, enter))
        tab.scaleRow(row, inv)
        rhsA[row] = ops.times(rhsA[row], inv)
        rhsD[row] = ops.times(rhsD[row], inv)
        // A column is stored as the zero column while it is basic, so the leaving column's true
        // current value `e_row` is written back here rather than read out of the tableau; the
        // entering column becomes zero for the same reason.
        tab.set(row, leave, inv)
        tab.set(row, enter, ops.zero)
        for (i in tab.columnRows(enter)) {
            if (i == row) continue
            val f = tab.get(i, enter)
            if (ops.isZero(f)) continue
            tab.axpy(i, f, row)
            rhsA[i] = ops.minus(rhsA[i], ops.times(rhsA[row], f))
            rhsD[i] = ops.minus(rhsD[i], ops.times(rhsD[row], f))
            tab.set(i, enter, ops.zero)
        }
        tab.clearColumn(enter)
        basis[row] = enter
        inBasisRow[enter] = row
        inBasisRow[leave] = -1
        // Pin the leaving variable at the bound it was violating toward; the entering column leaves
        // its bound, so its old flag no longer applies as nonbasic state.
        atUpper[leave] = targetUpper
        atUpper[enter] = false
        removeUpperCol(enter)
        if (targetUpper && uppers[leave] != null) addUpperCol(leave)
    }

    /** Exact primal-simplex entering choice for a minimization objective on structural columns. */
    fun selectImprovingColumn(costs: List<BigFraction>): Int {
        require(ops === BigFracOps) { "exact objective pivots require arbitrary-precision fractions" }
        @Suppress("UNCHECKED_CAST")
        fun fraction(value: F): BigFraction = value as BigFraction
        for (column in 0 until model.numVars) {
            if (inBasisRow[column] >= 0) continue
            var reduced = if (column < model.n) costs[column] else BigFraction.ZERO
            for (row in tab.columnRows(column)) {
                val basic = basis[row]
                if (basic >= model.n) continue
                reduced -= costs[basic] * fraction(tab.get(row, column))
            }
            if ((!atUpper[column] && reduced < BigFraction.ZERO) || (atUpper[column] && reduced > BigFraction.ZERO)) {
                return column
            }
        }
        return -1
    }

    /** The first exact blocker when [enter] moves from its current bound in its improving direction. */
    fun selectObjectiveBlocker(enter: Int): ObjectiveBlocker? {
        require(ops === BigFracOps) { "exact objective pivots require arbitrary-precision fractions" }
        @Suppress("UNCHECKED_CAST")
        fun fraction(value: F): BigFraction = value as BigFraction
        val direction = if (atUpper[enter]) BigFraction.MINUS_ONE else BigFraction.ONE
        var best: BigFraction? = uppers[enter]?.let(::fraction)
        var blocker = if (best == null) null else ObjectiveBlocker(row = -1, upper = !atUpper[enter])
        for (row in tab.columnRows(enter)) {
            val slope = BigFraction.MINUS_ONE * fraction(tab.get(row, enter)) * direction
            if (slope.isZero) continue
            val upper = uppers[basis[row]]?.let(::fraction)
            val candidate: BigFraction
            val hitsUpper: Boolean
            if (slope > BigFraction.ZERO) {
                if (upper == null) continue
                candidate = (upper - fraction(basicA[row])) * slope.reciprocal()
                hitsUpper = true
            } else {
                candidate = fraction(basicA[row]) * (BigFraction.MINUS_ONE * slope).reciprocal()
                hitsUpper = false
            }
            if (candidate < BigFraction.ZERO) continue
            if (
                best == null || candidate < best ||
                (candidate == best && blocker != null && (blocker.row < 0 || basis[row] < basis[blocker.row]))
            ) {
                best = candidate
                blocker = ObjectiveBlocker(row, hitsUpper)
            }
        }
        return blocker
    }

    fun pivotAtBound(row: Int, enter: Int, upper: Boolean) {
        targetUpper = upper
        pivot(row, enter)
    }

    fun flipNonbasicBound(column: Int) {
        check(inBasisRow[column] < 0) { "only a non-basic column has a bound to flip" }
        atUpper[column] = !atUpper[column]
        if (atUpper[column]) addUpperCol(column) else removeUpperCol(column)
    }

    fun objectiveValue(costs: List<BigFraction>): BigFraction {
        require(ops === BigFracOps) { "exact objective values require arbitrary-precision fractions" }
        @Suppress("UNCHECKED_CAST")
        fun fraction(value: F): BigFraction = value as BigFraction
        var value = BigFraction.ZERO
        for (column in 0 until model.n) {
            val row = inBasisRow[column]
            val x = when {
                row >= 0 -> fraction(basicA[row])
                atUpper[column] -> fraction(uppers[column] ?: ops.zero)
                else -> BigFraction.ZERO
            }
            value += costs[column] * x
        }
        return value
    }

    private fun deltaSignum(a: F, d: F): Int {
        val sa = ops.signum(a)
        return if (sa != 0) sa else ops.signum(d)
    }

    private fun addUpperCol(j: Int) {
        val at = upperCols.lowerBound(j)
        if (at < upperCols.size && upperCols[at] == j) return
        upperCols.insertAt(at, j)
    }

    private fun removeUpperCol(j: Int) {
        val at = upperCols.lowerBound(j)
        if (at >= upperCols.size || upperCols[at] != j) return
        for (k in at until upperCols.size - 1) upperCols[k] = upperCols[k + 1]
        upperCols.truncateTo(upperCols.size - 1)
    }
}

private class ObjectiveBlocker(val row: Int, val upper: Boolean)

/**
 * Row-major sparse storage for the feasibility tableau: per row the column indices in strictly
 * ascending order with their values, plus per column a list of the rows holding a nonzero. A dense
 * `m x (n + m)` tableau is quadratic in the model size whatever its sparsity, and it is materialized
 * before the first pivot, so neither the pivot cap nor the cancellation token can intervene; here the
 * initial storage is the model's own nonzero count and growth is paid pivot by pivot, where both do.
 *
 * A pivot is a sequence of row combinations `row_i ← row_i − f·row_p`, each a two-pointer merge of
 * two ascending index lists in the style of Gustavson, "Two Fast Algorithms for Sparse Matrices:
 * Multiplication and Permuted Transposition", ACM TOMS 4(3), 1978. Exact cancellations are dropped,
 * so a row's length is its true nonzero count and a row that genuinely densifies simply grows to
 * dense — it degrades rather than declining the model. Fill-in is not steered by a pivot order (the
 * feasibility rule fixes the pivot, unlike Markowitz, "The Elimination Form of the Inverse and its
 * Application to Linear Programming", Management Science 3(3), 1957), so a long pivot chain can
 * still fill in; the pivot cap and the cancellation token are what bound that.
 *
 * The per-column row lists are a superset: fill-in appends a row, an exact cancellation does not
 * remove it. [columnRows] re-verifies every entry against the row and compacts in place, so a stale
 * entry costs one search and never accumulates.
 */
private class SparseTableau<F>(private val ops: FracOps<F>, m: Int, total: Int) {
    private val cols = Array(m) { EmptyIntArray }
    private val vals = Array(m) { EmptyFracs }
    private val sizes = IntArray(m)
    private val colRows = arrayOfNulls<IntArrayList>(total)
    private val seen = IntArray(m)
    private var seenTick = 0

    fun rowSize(i: Int): Int = sizes[i]

    fun colAt(i: Int, k: Int): Int = cols[i][k]

    @Suppress("UNCHECKED_CAST")
    fun valAt(i: Int, k: Int): F = vals[i][k] as F

    fun get(i: Int, j: Int): F {
        val k = search(i, j)
        return if (k >= 0) valAt(i, k) else ops.zero
    }

    /** Size row [i]'s storage exactly once, before the ascending [append] pass fills it. */
    fun reserveRow(i: Int, capacity: Int) {
        if (cols[i].size >= capacity) return
        cols[i] = IntArray(capacity)
        vals[i] = arrayOfNulls(capacity)
    }

    /** Append `(j, v)` to row [i] during the build, where columns arrive in ascending order. */
    fun append(i: Int, j: Int, v: F) {
        if (ops.isZero(v)) return
        val nnz = sizes[i]
        if (nnz > 0 && cols[i][nnz - 1] == j) {
            vals[i][nnz - 1] = v
            return
        }
        grow(i, nnz + 1)
        cols[i][nnz] = j
        vals[i][nnz] = v
        sizes[i] = nnz + 1
        register(j, i)
    }

    /** Write `T(i,j) = v`, inserting or dropping the entry as the value becomes nonzero or zero. */
    fun set(i: Int, j: Int, v: F) {
        val k = search(i, j)
        if (k >= 0) {
            if (ops.isZero(v)) removeAt(i, k) else vals[i][k] = v
            return
        }
        if (ops.isZero(v)) return
        val at = -(k + 1)
        val nnz = sizes[i]
        grow(i, nnz + 1)
        val c = cols[i]
        val w = vals[i]
        for (p in nnz downTo at + 1) {
            c[p] = c[p - 1]
            w[p] = w[p - 1]
        }
        c[at] = j
        w[at] = v
        sizes[i] = nnz + 1
        register(j, i)
    }

    fun scaleRow(i: Int, f: F) {
        val w = vals[i]
        for (k in 0 until sizes[i]) w[k] = ops.times(valAt(i, k), f)
    }

    /** `row(dest) ← row(dest) − f · row(src)`, merging two ascending index lists in one pass. */
    fun axpy(dest: Int, f: F, src: Int) {
        val dc = cols[dest]
        val dw = vals[dest]
        val dn = sizes[dest]
        val sc = cols[src]
        val sn = sizes[src]
        val outC = IntArray(dn + sn)
        val outW = arrayOfNulls<Any?>(dn + sn)
        var a = 0
        var b = 0
        var o = 0
        while (a < dn || b < sn) {
            val ca = if (a < dn) dc[a] else Int.MAX_VALUE
            val cb = if (b < sn) sc[b] else Int.MAX_VALUE
            if (ca < cb) {
                outC[o] = ca
                outW[o] = dw[a]
                o++
                a++
                continue
            }
            val v = if (cb < ca) {
                ops.minus(ops.zero, ops.times(f, valAt(src, b)))
            } else {
                ops.minus(valAt(dest, a), ops.times(f, valAt(src, b)))
            }
            if (!ops.isZero(v)) {
                outC[o] = minOf(ca, cb)
                outW[o] = v
                o++
                if (cb < ca) register(cb, dest)
            }
            if (cb <= ca) b++
            if (ca <= cb) a++
        }
        cols[dest] = if (o == outC.size) outC else outC.copyOf(o)
        vals[dest] = if (o == outW.size) outW else outW.copyOf(o)
        sizes[dest] = o
    }

    /** The rows holding a nonzero in column [j], as a snapshot; compacts the stored superset. */
    fun columnRows(j: Int): IntArray {
        val list = colRows[j] ?: return EmptyIntArray
        seenTick++
        var w = 0
        for (k in 0 until list.size) {
            val r = list[k]
            if (seen[r] == seenTick || search(r, j) < 0) continue
            seen[r] = seenTick
            list[w++] = r
        }
        list.truncateTo(w)
        return list.toIntArray()
    }

    fun clearColumn(j: Int) {
        colRows[j]?.clear()
    }

    private fun register(j: Int, i: Int) {
        val list = colRows[j] ?: IntArrayList(4).also { colRows[j] = it }
        list.add(i)
    }

    private fun removeAt(i: Int, k: Int) {
        val c = cols[i]
        val w = vals[i]
        val nnz = sizes[i]
        for (p in k until nnz - 1) {
            c[p] = c[p + 1]
            w[p] = w[p + 1]
        }
        w[nnz - 1] = null
        sizes[i] = nnz - 1
    }

    private fun grow(i: Int, needed: Int) {
        val c = cols[i]
        if (c.size >= needed) return
        val capacity = maxOf(needed, c.size * 2, MIN_ROW_CAPACITY)
        cols[i] = c.copyOf(capacity)
        vals[i] = vals[i].copyOf(capacity)
    }

    /** Index of column [j] in row [i], or `-(insertion point) - 1` when absent. */
    private fun search(i: Int, j: Int): Int {
        val c = cols[i]
        var lo = 0
        var hi = sizes[i] - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val v = c[mid]
            when {
                v < j -> lo = mid + 1
                v > j -> hi = mid - 1
                else -> return mid
            }
        }
        return -(lo + 1)
    }

    private companion object {
        const val MIN_ROW_CAPACITY = 4
        val EmptyFracs = arrayOfNulls<Any?>(0)
    }
}

/**
 * Concrete structural-column values in the source model's coordinates from a lex-feasible final
 * state, with δ instantiated at a positive rational small enough that every delta-dependent basic
 * value stays inside its box.
 * Every constraint is affine in δ, so any δ below the per-constraint thresholds works; the
 * thresholds are computed exactly and halved once to sit strictly inside.
 */
private fun <F> structuralWitness(ops: FracOps<F>, st: SimplexState<F>): DoubleArray {
    // δ threshold: each basic value a + d·δ needing `>= 0` (d < 0 ⇒ δ ≤ a/(−d)) and, with a finite
    // upper u, `<= u` (d > 0 ⇒ δ ≤ (u − a)/d). Lex-feasibility guarantees each ratio is positive.
    var delta = ops.one
    for (i in 0 until st.model.m) {
        val a = st.basicA[i]
        val d = st.rhsD[i]
        if (ops.signum(d) < 0) {
            val cap = ops.times(a, ops.reciprocal(ops.times(d, ops.minusOne)))
            if (ops.compare(cap, delta) < 0) delta = cap
        }
        val u = st.uppers[st.basis[i]]
        if (u != null && ops.signum(d) > 0) {
            val cap = ops.times(ops.minus(u, a), ops.reciprocal(d))
            if (ops.compare(cap, delta) < 0) delta = cap
        }
    }
    delta = ops.times(delta, ops.half)
    val out = DoubleArray(st.model.n)
    for (j in 0 until st.model.n) {
        val row = st.inBasisRow[j]
        val value = when {
            row >= 0 -> ops.plus(st.basicA[row], ops.times(st.rhsD[row], delta))
            st.atUpper[j] -> st.uppers[j] ?: ops.zero
            else -> ops.zero
        }
        out[j] = ops.toDouble(value) + st.model.loShiftD(j)
    }
    return out
}

/** [structuralWitness] without the final lossy conversion for exact-theory clients. */
private fun structuralBigWitness(st: SimplexState<BigFraction>): List<BigFraction> {
    val delta = bigWitnessDelta(st)
    return List(st.model.n) { j ->
        val row = st.inBasisRow[j]
        when {
            row >= 0 -> st.basicA[row] + st.rhsD[row] * delta
            st.atUpper[j] -> st.uppers[j] ?: BigFraction.ZERO
            else -> BigFraction.ZERO
        }
    }
}

/** Exact final rows in the same delta instantiation used for [structuralBigWitness]. */
private fun bigTableau(st: SimplexState<BigFraction>): List<BigRationalTableauRow> {
    val delta = bigWitnessDelta(st)
    return List(st.model.m) { row ->
        val size = st.tab.rowSize(row)
        val columns = IntArray(size) { index -> st.tab.colAt(row, index) }
        val coefficients = List(size) { index -> st.tab.valAt(row, index) }
        BigRationalTableauRow(
            basic = st.basis[row],
            rhs = st.rhsA[row] + st.rhsD[row] * delta,
            columns = columns,
            coefficients = coefficients,
        )
    }
}

private fun bigWitnessDelta(st: SimplexState<BigFraction>): BigFraction {
    var delta = BigFraction.ONE
    for (i in 0 until st.model.m) {
        val a = st.basicA[i]
        val d = st.rhsD[i]
        if (d.signum() < 0) {
            val cap = a * (BigFraction.MINUS_ONE * d).reciprocal()
            if (cap < delta) delta = cap
        }
        val upper = st.uppers[st.basis[i]]
        if (upper != null && d.signum() > 0) {
            val cap = (upper - a) * d.reciprocal()
            if (cap < delta) delta = cap
        }
    }
    return delta * BigFraction.of(BigInteger.ONE, BigInteger.TWO)
}

/** Pivot cap: generous for the small leaf models the fallback targets, tiny relative to a search. */
internal fun defaultRationalPivotCap(model: ExactSimplexModel): Int = 200 + 20 * (model.m + model.n)

/** Immutable rational number over the multiplatform big integer, always normalized (gcd 1, positive
 *  denominator). The unbounded second level of the exact rational arithmetic — the 128-bit
 *  fixed-width level ([Frac128Ops]) handles the common case and escalates here on overflow. */
class BigFraction private constructor(
    /** The reduced signed numerator. */
    val num: BigInteger,
    /** The reduced positive denominator. */
    val den: BigInteger,
) {

    /** Whether this fraction is zero. */
    val isZero: Boolean get() = num.isZero()

    /** The sign of this fraction: `-1`, `0`, or `1`. */
    fun signum(): Int = num.signum()

    /** Returns the additive inverse of this fraction. */
    fun negated(): BigFraction = if (isZero) this else BigFraction(-num, den)

    /** Returns this fraction converted to a [Double]. */
    fun toDouble(): Double = num.doubleValue(exactRequired = false) / den.doubleValue(exactRequired = false)

    /** Returns the sum of this fraction and [other]. */
    operator fun plus(other: BigFraction): BigFraction = of(num * other.den + other.num * den, den * other.den)

    /** Returns this fraction minus [other]. */
    operator fun minus(other: BigFraction): BigFraction = of(num * other.den - other.num * den, den * other.den)

    /** Returns the product of this fraction and [other]. */
    operator fun times(other: BigFraction): BigFraction = of(num * other.num, den * other.den)

    /** Returns the multiplicative inverse of this non-zero fraction. */
    fun reciprocal(): BigFraction {
        require(!isZero) { "reciprocal of zero" }
        return of(den, num)
    }

    /** Compares this fraction with [other]. */
    operator fun compareTo(other: BigFraction): Int = (num * other.den).compareTo(other.num * den)

    override fun equals(other: Any?): Boolean = other is BigFraction && num == other.num && den == other.den

    override fun hashCode(): Int = num.hashCode() * 31 + den.hashCode()

    override fun toString(): String = if (den == BigInteger.ONE) "$num" else "$num/$den"

    /** Factories and constants for exact rational values. */
    companion object {
        /** The additive identity. */
        val ZERO = BigFraction(BigInteger.ZERO, BigInteger.ONE)

        /** The multiplicative identity. */
        val ONE = BigFraction(BigInteger.ONE, BigInteger.ONE)

        /** The additive inverse of [ONE]. */
        val MINUS_ONE = BigFraction(-BigInteger.ONE, BigInteger.ONE)

        /** Returns the integer fraction represented by [v]. */
        fun ofLong(v: Long): BigFraction = if (v == 0L) ZERO else BigFraction(BigInteger.fromLong(v), BigInteger.ONE)

        /** Returns the normalized fraction [num] / [den]. */
        fun of(num: BigInteger, den: BigInteger): BigFraction {
            require(!den.isZero()) { "zero denominator" }
            if (num.isZero()) return ZERO
            val negative = den.signum() < 0
            val n = if (negative) -num else num
            val d = if (negative) -den else den
            val g = n.gcd(d)
            return BigFraction(n / g, d / g)
        }

        /** The exact rational value of a finite double: `v = ±m·2ᵉ` from its IEEE decomposition.
         *  Null for non-finite values. */
        fun ofDouble(v: Double): BigFraction? {
            if (v == 0.0) return ZERO
            if (!v.isFinite()) return null
            val bits = v.toRawBits()
            val expBits = ((bits ushr 52) and 0x7FFL).toInt()
            var m = bits and 0xFFFFFFFFFFFFFL
            var e = if (expBits == 0) {
                -1074
            } else {
                m = m or (1L shl 52)
                expBits - 1075
            }
            val tz = m.countTrailingZeroBits()
            m = m shr tz
            e += tz
            val mag = BigInteger.fromLong(if (bits < 0L) -m else m)
            return if (e >= 0) of(mag shl e, BigInteger.ONE) else of(mag, BigInteger.ONE shl -e)
        }
    }
}
