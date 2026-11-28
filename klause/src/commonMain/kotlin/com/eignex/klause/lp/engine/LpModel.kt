package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.ExactSimplexDoubleView
import com.eignex.klause.simplex.exact.ExactSimplexModel
import com.eignex.util.arrays.binarySearchInt
import com.eignex.util.arrays.emptyIntArray
import com.eignex.util.arrays.emptyLongArray
import com.eignex.util.collections.IntArrayList
import com.eignex.util.collections.IntHashSet
import com.eignex.util.collections.LongArrayList
import com.eignex.util.collections.MutableIntDoubleMap
import com.eignex.util.collections.MutableIntLongMap
import com.eignex.util.collections.toSortedIntArray
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.addExact
import com.eignex.util.numbers.compareTo
import com.eignex.util.numbers.magnitudeBitLength
import com.eignex.util.numbers.mulExact
import com.eignex.util.numbers.subExact
import kotlin.math.ceil
import kotlin.math.floor

internal enum class Relation { LE, GE, EQ }

// The live bounds a non-global row's validity rests on, which a certificate giving the row dual weight must cite.
// A live-big-M row holds wherever each variable respects the live bound that entered its M. Only bounds tighter than
// the declared ones are recorded. Premise k is x(vars(k)) ≤ thresholds(k) when isUpper(k), else ≥.
internal class LpRowPremises(
    val vars: IntArray,
    val isUpper: BooleanArray,
    val thresholds: LongArray,
    // Literals the row's validity needs; a learned clause leaning on the row cites their negations.
    val boolLits: IntArray = emptyIntArray(),
) {
    fun toExact(): ExactLpPremises = ExactLpPremises(
        vars.indices.map { ExactLpPremise(vars[it], isUpper[it], ExactLpNumber.of(thresholds[it])) },
        boolLits.toList(),
    )
}

// [MAXIMIZE] is negated at build time.
internal enum class Sense { MINIMIZE, MAXIMIZE }

// Finite stand-in for ±∞, leaving headroom for the exact shift arithmetic in [LpBuilder.build]. A variable optimized
// only to this frontier is unbounded, which [safeVariableBound] reports as null; callers use [LpBuilder.addFreeVar].
internal const val LP_UNBOUNDED_PROBE: Long = Long.MAX_VALUE / 4

// The Long core holds integer data; real data rides in [doubleView] and exact rational data in [exactState].
// [LpBuilder] normalizes: lower bounds shift to zero (folded into [rhs] and [objConstant], recorded in [loShift]),
// >= rows negate to <=, and row i gains slack column n + i, so the all-slack basis is the identity. Every lower bound
// is 0; equality slacks are fixed at [0, 0] and inequality slacks have no upper bound.
internal class LpModel(
    override val n: Int,
    override val m: Int,
    // Slack columns are implicit unit vectors and never stored.
    val csc: Csc,
    override val rhs: LongArray,
    // Minimization sense, length n + m.
    val cost: LongArray,
    // Meaningful only where [hasUpper] is true.
    override val upper: LongArray,
    override val hasUpper: BooleanArray,
    val loShift: LongArray,
    // The true objective is c·x' + objConstant.
    val objConstant: Long,
    val sense: Sense,
    val tag: IntArray,
    // True when row i holds at every integer solution of the problem, not only inside the current node's box.
    // Learned artifacts cite only bound atoms and keep rows implicit, so they are valid only when every row their dual
    // certificate leans on is global.
    val rowGlobal: BooleanArray = BooleanArray(m) { true },
    // Strict over the reals. The float simplex ignores it, a sound relaxation; the exact deciders enforce it.
    override val rowStrict: BooleanArray = BooleanArray(m),
    // Citation fallback for non-global rows; null when validity is not expressible as bound atoms, and a certificate
    // leaning on such a row is withheld. Always null where [rowGlobal] is true.
    val rowPremises: Array<LpRowPremises?> = arrayOfNulls(m),
    // Right-hand sides before the lower-bound shift, so [rebind] can recompute [rhs] over the fixed [csc].
    val flippedRhs: LongArray = rhs,
    // Columns whose bound is the [LP_UNBOUNDED_PROBE] stand-in, so [safeVariableBound] can report an optimum riding
    // the probe frontier as unbounded.
    override val probeClampedLo: BooleanArray = BooleanArray(n),
    override val probeClampedHi: BooleanArray = BooleanArray(n),
    // LP-only real columns, absent from CP search.
    val colContinuous: BooleanArray = BooleanArray(n),
    // Present when real data is; the engine then solves it instead of the Long fields.
    override val doubleView: LpDoubleView? = null,
    val exactState: LpExactState? = null,
) : ExactSimplexModel {
    override val numVars: Int get() = n + m

    // The 128-bit integer certification declines on such a model.
    val hasContinuous: Boolean get() = exactState != null || doubleView != null

    // What one exact pass over the matrix multiplies by.
    val coefficientBits: Int by lazy {
        var widest = 1
        for (j in 0 until n) {
            forEachRationalColumn(j) { _, value ->
                widest = maxOf(widest, value.num.magnitudeBitLength(), value.den.magnitudeBitLength())
            }
        }
        widest
    }

    fun costD(j: Int): Double = doubleView?.cost?.get(j) ?: cost[j].toDouble()

    fun rhsD(i: Int): Double = doubleView?.rhs?.get(i) ?: rhs[i].toDouble()

    // Meaningful only where [hasFiniteUpper] is true.
    fun upperD(j: Int): Double = doubleView?.upper?.get(j) ?: upper[j].toDouble()

    fun hasFiniteUpper(j: Int): Boolean = exactState?.let { it.model.column(j).bounds.upper != null }
        ?: (doubleView?.hasUpper?.get(j) ?: hasUpper[j])

    fun hasFiniteLower(j: Int): Boolean = exactState == null || exactState.model.column(j).bounds.lower != null

    fun lowerD(j: Int): Double = exactState?.model?.column(j)?.bounds?.lower?.number?.approximation ?: 0.0

    fun fixed(j: Int): Boolean = exactState?.model?.column(j)?.bounds?.fixed
        ?: (hasFiniteLower(j) && hasFiniteUpper(j) && (doubleView?.upperIsZero(j) ?: (upper[j] == 0L)))

    fun objectiveD(value: Double): Double = exactState?.model?.objective?.let {
        val scale = it.scale.approximation
        val constant = it.externalConstant.approximation
        value / scale + constant
    } ?: value

    override fun loShiftD(j: Int): Double = doubleView?.loShift?.get(j) ?: loShift[j].toDouble()

    val objConstantD: Double get() = doubleView?.objConstant ?: objConstant.toDouble()

    // From [doubleView] when present, else the Long CSC widened.
    inline fun forEachInColumnD(j: Int, action: (row: Int, value: Double) -> Unit) {
        val dv = doubleView
        if (dv != null) {
            for (k in dv.colPtr[j] until dv.colPtr[j + 1]) action(dv.rowIdx[k], dv.colVal[k])
        } else {
            for (k in csc.colPtr[j] until csc.colPtr[j + 1]) action(csc.rowIdx[k], csc.colVal[k].toDouble())
        }
    }

    // Same structure with fresh structural bounds; recomputes only the bound-derived vectors, in O(nnz). Capture
    // replay uses this copy to import source authority; it does not mutate a persistent solver.
    fun rebind(lo: LongArray, hi: LongArray): LpModel {
        require(exactState == null) { "exact state bounds require the bound trail" }
        require(lo.size == n && hi.size == n) { "rebind expects $n bounds, got ${lo.size}/${hi.size}" }
        val newRhs = flippedRhs.copyOf()
        val newUpper = upper.copyOf()
        var newObjConstant = objConstant
        for (j in 0 until n) newObjConstant = subExact(newObjConstant, mulExact(cost[j], loShift[j]))
        for (j in 0 until n) {
            require(lo[j] <= hi[j]) { "empty domain [${lo[j]}, ${hi[j]}] for column $j" }
            forEachInColumn(j) { i, v -> newRhs[i] = subExact(newRhs[i], mulExact(v, lo[j])) }
            newUpper[j] = subExact(hi[j], lo[j])
            newObjConstant = addExact(newObjConstant, mulExact(cost[j], lo[j]))
        }
        val dv = doubleView?.let { view ->
            // The double view has no exact pre-shift RHS; a rounded recenter cannot preserve authority.
            require((0 until n).all { BigFraction.ofDouble(view.loShift[it]) == BigFraction.ofLong(lo[it]) }) {
                "real rebind requires unchanged exact origins"
            }
            val widths = view.upper.copyOf()
            for (j in 0 until n) {
                widths[j] = newUpper[j].toDouble()
                require(BigFraction.ofDouble(widths[j]) == BigFraction.ofLong(newUpper[j])) {
                    "real rebind width is not exactly representable"
                }
            }
            LpDoubleView(
                view.colPtr, view.rowIdx, view.colVal, view.rhs, view.cost, widths,
                view.hasUpper, view.objConstant, view.loShift, view.inexactCoefficients,
                lazy { view.shifts.value.withExactUppers() },
            )
        }
        return LpModel(
            n = n, m = m, csc = csc, rhs = newRhs, cost = cost,
            upper = newUpper, hasUpper = hasUpper, loShift = lo.copyOf(),
            objConstant = newObjConstant, sense = sense, tag = tag,
            rowGlobal = rowGlobal, rowStrict = rowStrict, rowPremises = rowPremises, flippedRhs = flippedRhs,
            probeClampedLo = probeClampedLo, probeClampedHi = probeClampedHi,
            colContinuous = colContinuous, doubleView = dv,
        )
    }

    // For a split variable x = x⁺ − x⁻, [negCol] names the negative part and takes −unitCost. Objective arrays are
    // copied so retained models and their proof snapshots keep the objective they were built with.
    fun withSingleColumnObjective(col: Int, unitCost: Long, negCol: Int = -1): LpModel {
        require(exactState == null) { "exact state objectives require the bound trail" }
        val cost = LongArray(numVars)
        cost[col] = unitCost
        if (negCol >= 0) cost[negCol] = -unitCost
        var constant = mulExact(unitCost, loShift[col])
        if (negCol >= 0) constant = subExact(constant, mulExact(unitCost, loShift[negCol]))
        val dv = doubleView?.copyObjective(unitCost.roundsInBinary64())
        if (dv != null) {
            dv.cost.fill(0.0)
            dv.cost[col] = unitCost.toDouble()
            if (negCol >= 0) dv.cost[negCol] = -unitCost.toDouble()
            dv.objConstant = dv.cost[col] * dv.loShift[col] +
                (if (negCol >= 0) dv.cost[negCol] * dv.loShift[negCol] else 0.0)
        }
        return LpModel(
            n = n, m = m, csc = csc, rhs = rhs, cost = cost,
            upper = upper, hasUpper = hasUpper, loShift = loShift,
            objConstant = constant, sense = sense, tag = tag,
            rowGlobal = rowGlobal, rowStrict = rowStrict, rowPremises = rowPremises, flippedRhs = flippedRhs,
            probeClampedLo = probeClampedLo, probeClampedHi = probeClampedHi,
            colContinuous = colContinuous,
            doubleView = dv,
        )
    }

    // Tests whether a direction aᵢᵀx is bounded. Replaces every structural and logical cost without modifying
    // retained models.
    fun withRowObjective(cols: IntArray, coeffs: LongArray): LpModel {
        require(exactState == null) { "exact state objectives require the bound trail" }
        require(cols.size == coeffs.size && cols.all { it in 0 until n })
        val cost = LongArray(numVars)
        var constant = 0L
        for (k in cols.indices) {
            cost[cols[k]] = coeffs[k]
            constant = addExact(constant, mulExact(coeffs[k], loShift[cols[k]]))
        }
        val dv = doubleView?.copyObjective(coeffs.any { it.roundsInBinary64() })
        if (dv != null) {
            dv.cost.fill(0.0)
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
            rowGlobal = rowGlobal, rowStrict = rowStrict, rowPremises = rowPremises, flippedRhs = flippedRhs,
            probeClampedLo = probeClampedLo, probeClampedHi = probeClampedHi,
            colContinuous = colContinuous,
            doubleView = dv,
        )
    }

    fun slackCol(i: Int): Int = n + i

    // Rows ascending; [j] must be structural, since slack columns are implicit.
    inline fun forEachInColumn(j: Int, action: (row: Int, value: Long) -> Unit) {
        for (k in csc.colPtr[j] until csc.colPtr[j + 1]) action(csc.rowIdx[k], csc.colVal[k])
    }

    override fun forEachExactColumn(j: Int, action: (row: Int, value: Long) -> Unit) {
        for (k in csc.colPtr[j] until csc.colPtr[j + 1]) action(csc.rowIdx[k], csc.colVal[k])
    }
}

// Row indices ascend within a column; slack columns are implicit.
internal class Csc(val colPtr: IntArray, val rowIdx: IntArray, val colVal: LongArray)

// Built when any real column or row is present, after the same normalizations as the Long core.
internal class LpDoubleView(
    override val colPtr: IntArray,
    override val rowIdx: IntArray,
    override val colVal: DoubleArray,
    override val rhs: DoubleArray,
    val cost: DoubleArray,
    override val upper: DoubleArray,
    override val hasUpper: BooleanArray,
    // Mutable because [LpModel.withSingleColumnObjective] replaces it along with a copied [cost].
    var objConstant: Double,
    val loShift: DoubleArray,
    // [colVal], [cost] or [loShift] rounded on conversion or when summing repeated entries. The engines and exact
    // readers share these arrays, so no exact certificate may be taken over such a view.
    val inexactCoefficients: Boolean = false,
    // The float engines solve the rounded arrays; every exact reader goes through [exactRhs] and [exactUpper].
    val shifts: Lazy<LpExactShifts> = lazyOf(LpExactShifts.NONE),
) : ExactSimplexDoubleView {
    fun exactRhs(i: Int): BigFraction = shifts.value.rhs(i) ?: exactDouble(rhs[i])

    fun exactUpper(j: Int): BigFraction = shifts.value.upper(j) ?: exactDouble(upper[j])

    // Builds no fraction for a nonzero bound.
    fun upperIsZero(j: Int): Boolean = upper[j] == 0.0 && (j >= loShift.size || (shifts.value.upper(j)?.isZero ?: true))

    // Rounded up when [outward], else down; null past Long.
    fun integerUpper(j: Int, outward: Boolean): Long? {
        shifts.value.upper(j)?.let { exact ->
            return if (outward) exact.ceilLong() else exact.negated().ceilLong()?.let { -it }
        }
        val rounded = if (outward) ceil(upper[j]) else floor(upper[j])
        val inLong = rounded >= Long.MIN_VALUE.toDouble() && rounded < Long.MAX_VALUE.toDouble()
        return if (inLong) rounded.toLong() else null
    }

    // [objConstant] is its binary64 rounding.
    fun exactObjConstant(): BigFraction {
        val arithmetic = ExactDoubleArithmetic()
        var sum = 0.0
        for (j in loShift.indices) sum = arithmetic.sum(sum, arithmetic.product(cost[j], loShift[j]))
        if (!arithmetic.rounded) return exactDouble(sum)
        var exact = BigFraction.ZERO
        for (j in loShift.indices) {
            if (cost[j] != 0.0 && loShift[j] != 0.0) exact += exactDouble(cost[j]) * exactDouble(loShift[j])
        }
        return exact
    }
}

// Exact values wherever binary64 rounded a shifted right-hand side or upper bound; unlisted entries are exact as
// stored. Shifting by a distant lower bound, the [LP_UNBOUNDED_PROBE] stand-in included, rounds.
internal class LpExactShifts(
    val rows: IntArray,
    val rhs: List<BigFraction>,
    val columns: IntArray,
    val upper: List<BigFraction>,
) {
    init {
        require(rows.size == rhs.size && columns.size == upper.size) { "exact shift lists differ in size" }
        require((1 until rows.size).all { rows[it - 1] < rows[it] }) { "exact shift rows must ascend" }
        require((1 until columns.size).all { columns[it - 1] < columns[it] }) { "exact shift columns must ascend" }
    }

    // Null when the stored value is exact.
    fun rhs(i: Int): BigFraction? = rows.binarySearchInt(i).let { if (it >= 0) rhs[it] else null }

    fun upper(j: Int): BigFraction? = columns.binarySearchInt(j).let { if (it >= 0) upper[it] else null }

    // Renumbered by position.
    fun restrict(takenRows: IntArray, takenColumns: IntArray): LpExactShifts {
        if (this === NONE) return NONE
        val subRows = IntArrayList()
        val subRhs = ArrayList<BigFraction>()
        for (r in takenRows.indices) {
            rhs(takenRows[r])?.let {
                subRows.add(r)
                subRhs.add(it)
            }
        }
        val subColumns = IntArrayList()
        val subUpper = ArrayList<BigFraction>()
        for (c in takenColumns.indices) {
            upper(takenColumns[c])?.let {
                subColumns.add(c)
                subUpper.add(it)
            }
        }
        return LpExactShifts(subRows.toIntArray(), subRhs, subColumns.toIntArray(), subUpper)
    }

    fun withExactUppers(): LpExactShifts =
        if (columns.isEmpty()) this else LpExactShifts(rows, rhs, emptyIntArray(), emptyList())

    companion object {
        val NONE: LpExactShifts = LpExactShifts(emptyIntArray(), emptyList(), emptyIntArray(), emptyList())
    }
}

// Owns the normalizations described on [LpModel]. The lower shift needs a finite lower bound, so an open side is
// realized by the [LP_UNBOUNDED_PROBE] stand-in ([addFreeVar], [addRealVar]).
internal class LpBuilder {
    private val lo = LongArrayList()
    private val hi = LongArrayList()
    private val cost = LongArrayList()
    private val tags = IntArrayList()

    // Columns whose bound is the [LP_UNBOUNDED_PROBE] stand-in; surfaced as probeClampedLo/Hi.
    private val clampedLoCols = IntHashSet()
    private val clampedHiCols = IntHashSet()

    // Genuinely [lo, +∞), so they carry no upper.
    private val openAboveCols = IntHashSet()

    // Empty for the pure-integer core, which never materializes a double view.
    private val continuousCols = IntHashSet()
    private val contLo = MutableIntDoubleMap()
    private val contHi = MutableIntDoubleMap()
    private val contCost = MutableIntDoubleMap()
    private var anyRealRow = false

    // A real-coefficient row carries its doubles in [valsD]/[rhsD] and leaves [vals]/[rhs] as unused zeros.
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
    private var rowEntries = 0L

    val constructionSize: Long get() = varCount.toLong() + rowCount + rowEntries

    val varCount: Int get() = lo.size

    val rowCount: Int get() = rows.size

    fun sourceBounds(column: Int): ExactLpBounds {
        require(column in 0 until varCount)
        fun number(upper: Boolean): ExactLpNumber = if (column in continuousCols) {
            ExactLpNumber.ofIeee(if (upper) contHi.getOrDefault(column, 0.0) else contLo.getOrDefault(column, 0.0))
        } else {
            ExactLpNumber.of(if (upper) hi[column] else lo[column])
        }
        return ExactLpBounds(
            if (column in clampedLoCols) null else ExactLpSide(number(false)),
            if (column in clampedHiCols || column in openAboveCols) null else ExactLpSide(number(true)),
        )
    }

    // [tag] is an opaque caller identifier carried through to [LpModel.tag].
    fun addVar(lower: Long, upper: Long, cost: Long = 0L, tag: Int = -1): Int {
        require(lower <= upper) { "empty domain [$lower, $upper]" }
        lo.add(lower)
        hi.add(upper)
        this.cost.add(cost)
        tags.add(tag)
        return lo.size - 1
    }

    // A null side is open, realized by the flagged [LP_UNBOUNDED_PROBE] stand-in so [safeVariableBound] can reject an
    // optimum that only reaches the probe frontier.
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

    // No upper bound at all, unlike [addFreeVar]'s stand-in: a refutation that never reads an upper bound refutes the
    // open model, while one resting on the probe only rules out points inside a box the model never had. Bound
    // extraction then cannot see an optimum ride to the frontier, so prefer [addFreeVar] unless refutation over the
    // open model is the point.
    fun addOpenAboveVar(lower: Long, cost: Long = 0L, tag: Int = -1): Int {
        // The stand-in only has to keep the box non-empty for the float ride — the column is open above,
        // so nothing downstream reads this upper (certification declines on `hasUpper = false`). A model
        // whose own lower bound already exceeds the probe would otherwise build an empty domain.
        val j = addVar(lower, maxOf(lower, LP_UNBOUNDED_PROBE), cost, tag)
        openAboveCols.add(j)
        return j
    }

    // Absent from CP search; a null side is open, like [addFreeVar]. The Long slot stays a placeholder zero and the
    // model is solved through the double view.
    fun addRealVar(lower: Double?, upper: Double?, cost: Double = 0.0, tag: Int = -1): Int {
        val j = lo.size
        lo.add(0L)
        hi.add(0L)
        this.cost.add(0L)
        tags.add(tag)
        continuousCols.add(j)
        contLo.put(j, lower ?: -LP_UNBOUNDED_PROBE.toDouble())
        contHi.put(j, upper ?: LP_UNBOUNDED_PROBE.toDouble())
        contCost.put(j, cost)
        if (lower == null) clampedLoCols.add(j)
        if (upper == null) clampedHiCols.add(j)
        return j
    }

    // Forces the model onto the double view.
    fun addRealRow(
        cols: IntArray,
        vals: DoubleArray,
        rel: Relation,
        rhs: Double,
        strict: Boolean = false,
        premiseLits: IntArray = emptyIntArray(),
        validity: LpRowPremises? = null,
    ) {
        require(cols.size == vals.size) { "cols/vals length mismatch: ${cols.size} vs ${vals.size}" }
        anyRealRow = true
        rows.add(
            RawRow(
                cols.copyOf(),
                LongArray(cols.size),
                rel,
                0L,
                global = premiseLits.isEmpty() && validity == null,
                premises = if (validity != null) {
                    LpRowPremises(validity.vars, validity.isUpper, validity.thresholds, validity.boolLits + premiseLits)
                } else if (premiseLits.isEmpty()) {
                    null
                } else {
                    LpRowPremises(emptyIntArray(), BooleanArray(0), emptyLongArray(), premiseLits.copyOf())
                },
                valsD = vals.copyOf(),
                rhsD = rhs,
                strict = strict,
            ),
        )
        rowEntries += cols.size
    }

    // Repeated columns are summed; the arrays are copied. Pass [global] false for rows built from branch-tightened
    // information, with [premises] naming the live bounds that justify them when expressible.
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
        rowEntries += cols.size
    }

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
        rowEntries += cols.size
    }

    // Maximization negates the objective; the reported objective re-applies the sign.
    fun build(sense: Sense): LpModel {
        val n = lo.size
        val m = rows.size
        val rhs = LongArray(m)
        // Retained so [LpModel.rebind] can re-derive rhs.
        val flippedRhs = LongArray(m)
        val loShift = LongArray(n) { lo[it] }

        for ((i, row) in rows.withIndex()) {
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

        val doubleView = if (!continuousCols.isEmpty() || anyRealRow) buildDoubleView(n, m, signedSense) else null

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

    // Integer columns and rows contribute their Long values widened to double.
    private fun buildDoubleView(n: Int, m: Int, signedSense: Long): LpDoubleView {
        val loD = DoubleArray(n) { contLo.getOrDefault(it, lo[it].toDouble()) }
        // The open-side stand-in rounds too, but it is no source bound: the view shifts by its rounding throughout.
        var roundedSource = (0 until n).any {
            it !in continuousCols && it !in clampedLoCols && lo[it].roundsInBinary64()
        }
        val rhsD = DoubleArray(m)
        val arithmetic = ExactDoubleArithmetic()
        val roundedRows = IntArrayList()
        for ((i, row) in rows.withIndex()) {
            val flip = row.rel == Relation.GE
            val rawRhs = row.rhsD ?: row.rhs.toDouble()
            var b = if (flip) -rawRhs else rawRhs
            arithmetic.reset()
            for (k in row.cols.indices) {
                val j = row.cols[k]
                val value = row.valsD?.get(k) ?: row.vals[k].toDouble()
                if (row.valsD == null && row.vals[k].roundsInBinary64()) roundedSource = true
                val coeff = if (flip) -value else value
                b = arithmetic.sum(b, -arithmetic.product(coeff, loD[j]))
            }
            rhsD[i] = b
            if (arithmetic.rounded || (row.rhsD == null && row.rhs.roundsInBinary64())) roundedRows.add(i)
        }
        val colRowBuckets = Array(n) { IntArrayList() }
        val colValBuckets = Array(n) { ArrayList<Double>() }
        arithmetic.reset()
        for ((i, row) in rows.withIndex()) {
            val flip = row.rel == Relation.GE
            val summed = MutableIntDoubleMap(row.cols.size)
            for (k in row.cols.indices) {
                val j = row.cols[k]
                val value = row.valsD?.get(k) ?: row.vals[k].toDouble()
                val coeff = if (flip) -value else value
                summed.put(j, arithmetic.sum(summed.getOrDefault(j, 0.0), coeff))
            }
            // Ascending column order keeps the CSC deterministic; i ascends ⇒ rows ascend within a column.
            val summedCols = IntArrayList()
            summed.forEach { j, _ -> summedCols.add(j) }
            for (j in summedCols.toSortedIntArray()) {
                val v = summed.getOrDefault(j, 0.0)
                if (v != 0.0) {
                    colRowBuckets[j].add(i)
                    colValBuckets[j].add(v)
                }
            }
        }
        val summedRounded = arithmetic.rounded
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
        val roundedColumns = IntArrayList()
        for (j in 0 until n) {
            costD[j] = signed * contCost.getOrDefault(j, this.cost[j].toDouble())
            if (j !in continuousCols && this.cost[j].roundsInBinary64()) roundedSource = true
            hasUpperD[j] = j !in openAboveCols
            arithmetic.reset()
            upperD[j] = arithmetic.sum(contHi.getOrDefault(j, hi[j].toDouble()), -loD[j])
            val roundedHi = j !in continuousCols && j !in clampedHiCols && hi[j].roundsInBinary64()
            // An open side's upper is a stand-in nothing certifies against, so its rounding does not matter.
            if ((arithmetic.rounded || roundedHi) && hasUpperD[j]) roundedColumns.add(j)
            objConstantD += costD[j] * loD[j]
        }
        for (i in 0 until m) {
            hasUpperD[n + i] = rows[i].rel == Relation.EQ
        }
        return LpDoubleView(
            colPtr, rowIdx, colVal, rhsD, costD, upperD, hasUpperD, objConstantD, loD, roundedSource || summedRounded,
            exactShifts(roundedRows.toIntArray(), roundedColumns.toIntArray(), loD),
        )
    }

    // Recomputed from the unshifted inputs when an exact reader first asks. Every shift is by the view's own [loD],
    // which is the source's lower bound wherever the view certifies.
    private fun exactShifts(roundedRows: IntArray, roundedColumns: IntArray, loD: DoubleArray): Lazy<LpExactShifts> {
        if (roundedRows.isEmpty() && roundedColumns.isEmpty()) return lazyOf(LpExactShifts.NONE)
        // The unsummed entries keep a row exact even where summing its repeated columns would round.
        val sources = Array(roundedRows.size) { rows[roundedRows[it]] }
        val rawUppers = List(roundedColumns.size) {
            val j = roundedColumns[it]
            when {
                j in continuousCols -> exactDouble(contHi.getOrDefault(j, 0.0))

                // The stand-in for +∞ keeps the value the view solves with; no source bound stands behind it.
                j in clampedHiCols -> exactDouble(hi[j].toDouble())

                else -> BigFraction.ofLong(hi[j])
            }
        }
        return lazy {
            val rhs = sources.map { row ->
                val flip = row.rel == Relation.GE
                val rawRhs = row.rhsD?.let(::exactDouble) ?: BigFraction.ofLong(row.rhs)
                var b = if (flip) rawRhs.negated() else rawRhs
                for (k in row.cols.indices) {
                    val value = row.valsD?.let { exactDouble(it[k]) } ?: BigFraction.ofLong(row.vals[k])
                    val coeff = if (flip) value.negated() else value
                    b -= coeff * exactDouble(loD[row.cols[k]])
                }
                b
            }
            val upper = List(roundedColumns.size) { rawUppers[it] - exactDouble(loD[roundedColumns[it]]) }
            LpExactShifts(roundedRows, rhs, roundedColumns, upper)
        }
    }

    // >= rows negate to <= and repeated columns within a row are summed.
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

private fun LpDoubleView.copyObjective(roundedCost: Boolean): LpDoubleView = LpDoubleView(
    colPtr, rowIdx, colVal, rhs, cost.copyOf(), upper, hasUpper, objConstant, loShift,
    inexactCoefficients || roundedCost, shifts,
)

private const val TWO_POW_63: Double = 9.223372036854775808E18

// The saturating Double.toLong would hide 2^63 − 1 rounding up.
private fun Long.roundsInBinary64(): Boolean {
    val d = toDouble()
    return d >= TWO_POW_63 || d.toLong() != this
}
