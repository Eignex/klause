package com.eignex.klause.solver.factor.global.internals

import com.eignex.klause.solver.factor.arithmetic.internals.collectHoleAndBoundAntecedents
import com.eignex.klause.solver.propagation.PropagationState

/**
 * Bounds-consistency filtering for `all_different ::bounds`. This is the López-Ortiz / Quimper /
 * van Beek / Tremblay / Marchand "fast and simple" algorithm (CP-AI-OR 2003): two O(n log n)
 * sweeps over the variable bounds that raise lower bounds and lower upper bounds to the edges of
 * Hall intervals.
 *
 * Returns the involved variables as a conflict reason when a Hall interval is over-full, or `null`
 * when filtering succeeds.
 */
internal fun boundsAllDifferentFilter(state: PropagationState, vars: IntArray): IntArray? {
    val n = vars.size
    if (n < 2) return null

    val lo = IntArray(n) { state.intDomains[vars[it]].min }
    val hi = IntArray(n) { state.intDomains[vars[it]].max }
    val newLo = lo.copyOf()
    val newHi = hi.copyOf()

    if (!computeBoundsAllDifferent(lo, hi, newLo, newHi)) return vars

    var changed = false
    for (i in 0 until n) if (newLo[i] != lo[i] || newHi[i] != hi[i]) changed = true
    if (!changed) return null
    val ant = collectHoleAndBoundAntecedents(state, vars)
    for (i in 0 until n) {
        if (newLo[i] > lo[i] && !state.tightenIntMin(vars[i], newLo[i], ant)) return vars
        if (newHi[i] < hi[i] && !state.tightenIntMax(vars[i], newHi[i], ant)) return vars
    }
    return null
}

/**
 * Pure core of [boundsAllDifferentFilter]: given variable lower/upper bounds, fill [newLo]/[newHi]
 * with the bounds-consistent tightened bounds and return `true` if feasible, `false` if a Hall
 * interval is over-full. López-Ortiz / Quimper / van Beek / Tremblay / Marchand (CP-AI-OR 2003).
 */
internal fun computeBoundsAllDifferent(lo: IntArray, hi: IntArray, newLo: IntArray, newHi: IntArray): Boolean {
    val n = lo.size
    lo.copyInto(newLo)
    hi.copyInto(newHi)
    if (n < 2) return true

    if (!raiseMins(lo, hi, newLo)) return false
    val negLo = IntArray(n) { -hi[it] }
    val negHi = IntArray(n) { -lo[it] }
    val negNewLo = negLo.copyOf()
    if (!raiseMins(negLo, negHi, negNewLo)) return false
    for (i in 0 until n) newHi[i] = -negNewLo[i]
    return true
}

private fun raiseMins(lo: IntArray, hi: IntArray, outLo: IntArray): Boolean {
    val n = lo.size
    val minsorted = (0 until n).sortedBy { lo[it] }.toIntArray()
    val maxsorted = (0 until n).sortedBy { hi[it] }.toIntArray()

    val bounds = IntArray(2 * n + 2)
    val minrank = IntArray(n)
    val maxrank = IntArray(n)
    var nb = 0
    var last = Int.MIN_VALUE
    var i = 0
    var j = 0
    while (i < n || j < n) {
        val nextMin = if (i < n) lo[minsorted[i]] else Int.MAX_VALUE
        val nextMax = if (j < n) hi[maxsorted[j]] + 1 else Int.MAX_VALUE
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
    val d = IntArray(nb + 2)
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
        if (--d[z] == 0) {
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
