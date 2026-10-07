package com.eignex.klause.factor.global.internals

import com.eignex.klause.ir.Lit
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.boundLiteral
import com.eignex.klause.propagation.domainAt
import com.eignex.klause.propagation.lazyReason
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet
import com.eignex.klause.util.EmptyIntArray

/**
 * Bounds-consistency filtering for `all_different ::bounds`. This is the López-Ortiz / Quimper /
 * van Beek / Tremblay / Marchand "fast and simple" algorithm (CP-AI-OR 2003): two O(n log n) sweeps
 * over the variable bounds (the second on the negated bounds) that raise lower bounds and lower
 * upper bounds to the edges of Hall intervals, using union-find chains over the sorted endpoints.
 *
 * Returns the involved variables as a conflict reason when a Hall interval is over-full, or `null`
 * when filtering succeeds.
 */
internal fun boundsAllDifferentFilter(
    state: PropagationState,
    vars: IntArray,
    premises: IntArray = EmptyIntArray,
    tag: Int = 0,
): IntArray? {
    val n = vars.size
    if (n < 2) return null

    val lo = LongArray(n) { state.intDomains[vars[it]].min }
    val hi = LongArray(n) { state.intDomains[vars[it]].max }
    val newLo = lo.copyOf()
    val newHi = hi.copyOf()

    if (!computeBoundsAllDifferent(lo, hi, newLo, newHi)) return overfullInterval(lo, hi, vars) ?: vars

    for (i in 0 until n) {
        if (newLo[i] > lo[i] && !state.tightenIntMin(vars[i], newLo[i], boundsReason(state, tag, vars[i], true, newLo[i], premises))) {
            return vars
        }
        if (newHi[i] < hi[i] && !state.tightenIntMax(vars[i], newHi[i], boundsReason(state, tag, vars[i], false, newHi[i], premises))) {
            return vars
        }
    }
    return null
}

// The reason for [x]'s new bound, recorded for [explainBoundsHall] (via the filtering factor's explain) to build
// from the Hall interval it crossed; without the undo log nothing reads reasons and the presence premises stand in.
private fun boundsReason(
    state: PropagationState,
    tag: Int,
    x: Int,
    lower: Boolean,
    bound: Long,
    premises: IntArray,
): IntArray? = when {
    state.currentLevel == 0 -> null
    state.undoLogging -> state.lazyReason(
        intArrayOf(BOUNDS_HALL, tag, x, if (lower) 1 else 0, (bound ushr 32).toInt(), bound.toInt()) + premises,
    )
    else -> premises
}

/** Marks a lazy reason [boundsAllDifferentFilter] recorded; its second entry tags the variable array it filtered. */
internal const val BOUNDS_HALL = -10

/**
 * The reason for a bound [boundsAllDifferentFilter] moved, from its lazy [payload] over the variables [vars] (those
 * present at [atTrail], per [present]): a Hall interval the bound crossed, as it stood then. Every other variable
 * confined to the interval fills it, so the moved variable, which started inside it, lies past it.
 */
internal fun explainBoundsHall(
    state: PropagationState,
    vars: IntArray,
    present: (Int) -> Boolean,
    payload: IntArray,
    atTrail: Int,
    atLevel: Int,
): IntArray {
    val x = payload[2]
    val lower = payload[3] == 1
    val bound = (payload[4].toLong() shl 32) or (payload[5].toLong() and 0xFFFFFFFFL)
    val premises = payload.copyOfRange(6, payload.size)
    val others = vars.indices.filter { vars[it] != x && present(it) }.map { vars[it] }
    val lo = others.map { state.domainAt(it, atTrail).min }
    val hi = others.map { state.domainAt(it, atTrail).max }
    val own = state.domainAt(x, atTrail)
    // A lower bound moved to `bound` crossed an interval ending at bound - 1 that began at or below the old one;
    // an upper bound mirrors it. Take the narrowest interval whose confined variables fill it.
    val ends = if (lower) lo.filter { it <= own.min } else hi.filter { it >= own.max }
    val candidates = ends.distinct().sortedBy { if (lower) -it else it }
    for (edge in candidates) {
        val a = if (lower) edge else bound + 1
        val b = if (lower) bound - 1 else edge
        if (a > b) continue
        val inside = others.indices.filter { lo[it] >= a && hi[it] <= b }
        if (inside.size.toLong() < b - a + 1) continue
        val seen = IntHashSet()
        val out = IntArrayList()
        fun add(lit: Int) {
            if (lit != Lit.NONE && seen.add(lit)) out.add(lit)
        }
        for (k in inside) {
            add(state.boundLiteral(others[k], true, a, atTrail, atLevel))
            add(state.boundLiteral(others[k], false, b, atTrail, atLevel))
        }
        if (lower) add(state.boundLiteral(x, true, a, atTrail, atLevel)) else add(state.boundLiteral(x, false, b, atTrail, atLevel))
        premises.forEach { add(it) }
        return out.toIntArray()
    }
    // The bound came from a Hall interval when it was moved, so one is always found above; every variable's
    // bounds then still imply it.
    val out = IntArrayList()
    for (v in vars) {
        val d = state.domainAt(v, atTrail)
        val bl = state.boundLiteral(v, true, d.min, atTrail, atLevel)
        if (bl != Lit.NONE) out.add(bl)
        val bu = state.boundLiteral(v, false, d.max, atTrail, atLevel)
        if (bu != Lit.NONE) out.add(bu)
    }
    premises.forEach { out.add(it) }
    return out.toIntArray()
}

// An interval with more variables confined to it than values: the bounds conflict's Hall violators.
private fun overfullInterval(lo: LongArray, hi: LongArray, vars: IntArray): IntArray? {
    val n = vars.size
    for (a in lo.distinct()) {
        for (b in hi.distinct()) {
            if (b < a) continue
            val inside = (0 until n).filter { lo[it] >= a && hi[it] <= b }
            if (inside.size.toLong() > b - a + 1) return IntArray(inside.size) { vars[inside[it]] }
        }
    }
    return null
}

/**
 * Pure core of [boundsAllDifferentFilter]: given variable lower/upper bounds, fill [newLo]/[newHi]
 * with the bounds-consistent tightened bounds and return `true` if feasible, `false` if a Hall
 * interval is over-full. López-Ortiz / Quimper / van Beek / Tremblay / Marchand (CP-AI-OR 2003).
 */
internal fun computeBoundsAllDifferent(lo: LongArray, hi: LongArray, newLo: LongArray, newHi: LongArray): Boolean {
    val n = lo.size
    lo.copyInto(newLo)
    hi.copyInto(newHi)
    if (n < 2) return true

    if (!raiseMins(lo, hi, newLo)) return false
    val negLo = LongArray(n) { -hi[it] }
    val negHi = LongArray(n) { -lo[it] }
    val negNewLo = negLo.copyOf()
    if (!raiseMins(negLo, negHi, negNewLo)) return false
    for (i in 0 until n) newHi[i] = -negNewLo[i]
    return true
}

private fun raiseMins(lo: LongArray, hi: LongArray, outLo: LongArray): Boolean {
    val n = lo.size
    val minsorted = (0 until n).sortedBy { lo[it] }.toIntArray()
    val maxsorted = (0 until n).sortedBy { hi[it] }.toIntArray()

    // `bounds` holds the distinct interval endpoints (values, Long); `minrank`/`maxrank` index into
    // it, and `t`/`h` are union-find pointer chains over those indices — all small Ints.
    val bounds = LongArray(2 * n + 2)
    val minrank = IntArray(n)
    val maxrank = IntArray(n)
    var nb = 0
    var last = Long.MIN_VALUE
    var i = 0
    var j = 0
    while (i < n || j < n) {
        val nextMin = if (i < n) lo[minsorted[i]] else Long.MAX_VALUE
        val nextMax = if (j < n) hi[maxsorted[j]] + 1 else Long.MAX_VALUE
        val takeMin = nextMin <= nextMax
        val value = if (takeMin) nextMin else nextMax
        if (nb == 0 || value != last) {
            nb++
            bounds[nb] = value
            last = value
        }
        if (takeMin) {
            minrank[minsorted[i]] = nb
            i++
        } else {
            maxrank[maxsorted[j]] = nb
            j++
        }
    }
    bounds[0] = bounds[1] - 2
    bounds[nb + 1] = bounds[nb] + 2

    val t = IntArray(nb + 2)
    // Interval capacities (count of values between consecutive endpoints); Long, since a wide
    // union domain can hold more values than fit in an Int.
    val d = LongArray(nb + 2)
    val h = IntArray(nb + 2)
    for (k in 1..nb + 1) {
        t[k] = k - 1
        h[k] = k - 1
        d[k] = bounds[k] - bounds[k - 1]
    }
    for (idx in 0 until n) {
        val v = maxsorted[idx]
        val x = minrank[v]
        val y = maxrank[v]
        var z = pathmax(t, x + 1)
        val jj = t[z]
        if (--d[z] == 0L) {
            t[z] = z + 1
            z = pathmax(t, t[z])
            t[z] = jj
        }
        pathset(t, x + 1, z, z)
        if (d[z] < bounds[z] - bounds[y]) return false
        if (h[x] > x) {
            val w = pathmax(h, h[x])
            if (bounds[w] > outLo[v]) outLo[v] = bounds[w]
            pathset(h, x, w, w)
        }
        if (d[z] == bounds[z] - bounds[y]) {
            pathset(h, h[y], jj - 1, y)
            h[y] = jj - 1
        }
    }
    return true
}

private fun pathmax(t: IntArray, start: Int): Int {
    var i = start
    while (i < t.size && t[i] > i) i = t[i]
    return i
}

private fun pathset(t: IntArray, start: Int, end: Int, to: Int) {
    var i = start
    var k = i
    while (k != end) {
        k = t[i]
        t[i] = to
        i = k
    }
}
