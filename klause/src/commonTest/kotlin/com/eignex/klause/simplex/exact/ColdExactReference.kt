@file:Suppress("MatchingDeclarationName")

package com.eignex.klause.simplex.exact

import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BIG_TWO
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.EmptyIntArray
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.bigIntOf

internal enum class RationalFeasibility { FEASIBLE, INFEASIBLE, UNKNOWN }

internal class RationalOutcome(
    val feasibility: RationalFeasibility,
    val rows: IntArray? = null,
    val conflict: BigRationalConflict? = null,
)

internal class BigRationalOutcome(
    val feasibility: RationalFeasibility,
    val witness: List<BigFraction>? = null,
    val tableau: List<BigRationalTableauRow>? = null,
    val conflict: BigRationalConflict? = null,
)

internal class BigRationalOptimizationOutcome(
    val feasibility: RationalFeasibility,
    val infimum: BigFraction? = null,
    val unbounded: Boolean = false,
)

internal class BigRationalTableauRow(
    val basic: Int,
    val rhs: BigFraction,
    val columns: IntArray,
    val coefficients: List<BigFraction>,
)

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
        if (enter < 0) {
            val refutation = state.refutation(row) ?: return BigRationalOutcome(RationalFeasibility.UNKNOWN)
            return BigRationalOutcome(
                refutation.feasibility,
                conflict = refutation.conflict,
            )
        }
        state.pivot(row, enter)
        pivots++
    }
}

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
    // A strict row `a·x < b` becomes `a·x ≤ b − δ`; only the rhs carries a delta part.
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

    val atUpper = BooleanArray(model.numVars)

    val basicA = MutableList(model.m) { ops.zero }

    private val upperCols = IntArrayList(4)

    var needIncrease = false
        private set

    var targetUpper = false
        private set

    init {
        for (i in 0 until model.m) inBasisRow[basis[i]] = i
    }

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

    fun refutation(row: Int): RationalOutcome? {
        val leaving = basis[row]
        for (bound in blockingBounds(row)) {
            if (bound.column >= model.n) continue
            val clamped = if (bound.upper) model.probeClampedHi else model.probeClampedLo
            if (clamped[bound.column]) return RationalOutcome(RationalFeasibility.UNKNOWN)
        }
        val certRows = ArrayList<Int>()
        if (leaving >= model.n) certRows.add(leaving - model.n)
        for (k in 0 until tab.rowSize(row)) {
            val j = tab.colAt(row, k)
            if (j < model.n || j == leaving || ops.isZero(tab.valAt(row, k))) continue
            certRows.add(j - model.n)
        }
        if (ops.overflowed()) return null
        val rows = certRows.toIntArray()
        val conflict = bigConflict(this, row, rows)
        if (ops.overflowed()) return null
        return RationalOutcome(RationalFeasibility.INFEASIBLE, rows = rows, conflict = conflict)
    }

    fun blockingBounds(row: Int): List<ExactSimplexBound> = buildList {
        add(ExactSimplexBound(basis[row], targetUpper))
        for (k in 0 until tab.rowSize(row)) {
            val column = tab.colAt(row, k)
            if (inBasisRow[column] < 0 && !ops.isZero(tab.valAt(row, k))) {
                add(ExactSimplexBound(column, atUpper[column]))
            }
        }
    }

    fun pivot(row: Int, enter: Int) {
        val leave = basis[row]
        val inv = ops.reciprocal(tab.get(row, enter))
        tab.scaleRow(row, inv)
        rhsA[row] = ops.times(rhsA[row], inv)
        rhsD[row] = ops.times(rhsD[row], inv)
        // Basic columns are stored as zero, so the leaving column's `e_row` is written back here.
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
        atUpper[leave] = targetUpper
        atUpper[enter] = false
        removeUpperCol(enter)
        if (targetUpper && uppers[leave] != null) addUpperCol(leave)
    }

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

    fun reserveRow(i: Int, capacity: Int) {
        if (cols[i].size >= capacity) return
        cols[i] = IntArray(capacity)
        vals[i] = arrayOfNulls(capacity)
    }

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

private fun <F> exactWitnessFraction(value: F): BigFraction = when (value) {
    is BigFraction -> value

    is Frac128 -> BigFraction.of(
        (bigIntOf(value.nHi) shl 64) + bigIntOf(value.nLo.toULong()),
        (bigIntOf(value.dHi) shl 64) + bigIntOf(value.dLo.toULong()),
    )

    else -> error("unsupported exact witness arithmetic")
}

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

private fun <F> bigConflict(state: SimplexState<F>, row: Int, rows: IntArray): BigRationalConflict {
    val ops = state.ops
    val sign = if (state.targetUpper) ops.minusOne else ops.one
    val multipliers = rows.map { source ->
        val slack = state.model.n + source
        exactWitnessFraction(ops.times(sign, if (slack == state.basis[row]) ops.one else state.tab.get(row, slack)))
    }
    return BigRationalConflict(rows, multipliers, state.blockingBounds(row))
}

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
    return delta * BigFraction.of(BIG_ONE, BIG_TWO)
}

internal fun defaultRationalPivotCap(model: ExactSimplexModel): Int = 200 + 20 * (model.m + model.n)
