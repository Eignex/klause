package com.eignex.klause.lp.lattice

import com.eignex.util.numbers.BIG_ZERO
import com.eignex.util.numbers.BigInt
import com.eignex.util.numbers.ceilDiv
import com.eignex.util.numbers.compareTo
import com.eignex.util.numbers.div
import com.eignex.util.numbers.floorDiv
import com.eignex.util.numbers.minus
import com.eignex.util.numbers.plus
import com.eignex.util.numbers.times

// `null` where the sweep derives no bound, as for a column that pivots in no row.
internal class TriangularBounds(
    val lo: Array<BigInt?>,
    val hi: Array<BigInt?>,
)

// In a lower-triangular [h] each row's pivot is its last column, so sweeping top to bottom bounds each pivot from
// `h(i, p)·y_p ∈ [rowLo(i) − maxRest, rowHi(i) − minRest]` over the already-bounded earlier columns, rounding inward.
@Suppress("NestedBlockDepth", "CyclomaticComplexMethod")
internal fun triangularBounds(
    h: List<SparseIntRow>,
    cols: Int,
    rowLo: Array<BigInt?>,
    rowHi: Array<BigInt?>,
): TriangularBounds {
    val lo = arrayOfNulls<BigInt>(cols)
    val hi = arrayOfNulls<BigInt>(cols)
    for (i in h.indices) {
        val row = h[i]
        val pivot = row.trail
        if (pivot < 0 || pivot >= cols) continue
        if (lo[pivot] != null || hi[pivot] != null) continue // a later row must not overwrite the bound

        // The rest-interval of the columns before the pivot; either side goes null once a term is open.
        var restLo: BigInt? = BIG_ZERO
        var restHi: BigInt? = BIG_ZERO
        for (k in row.index.indices) {
            val j = row.index[k]
            if (j >= pivot) break
            val a = row.value[k]
            val termLo = if (a > BIG_ZERO) lo[j]?.times(a) else hi[j]?.times(a)
            val termHi = if (a > BIG_ZERO) hi[j]?.times(a) else lo[j]?.times(a)
            restLo = if (termLo == null || restLo == null) null else restLo + termLo
            restHi = if (termHi == null || restHi == null) null else restHi + termHi
        }

        val lowSide = rowLo[i]
        val highSide = rowHi[i]
        val prodLo = if (lowSide == null || restHi == null) null else lowSide - restHi
        val prodHi = if (highSide == null || restLo == null) null else highSide - restLo
        val c = row.value[row.index.size - 1]
        if (c > BIG_ZERO) {
            lo[pivot] = prodLo?.let { ceilDiv(it, c) }
            hi[pivot] = prodHi?.let { floorDiv(it, c) }
        } else {
            // Dividing by a negative coefficient exchanges the two sides.
            lo[pivot] = prodHi?.let { ceilDiv(it, c) }
            hi[pivot] = prodLo?.let { floorDiv(it, c) }
        }
    }
    return TriangularBounds(lo, hi)
}
