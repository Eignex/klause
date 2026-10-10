package com.eignex.klause.factor.scheduling

import com.eignex.klause.factor.arithmetic.internals.collectLinearTightenAntecedents
import com.eignex.klause.ir.Lit
import com.eignex.klause.propagation.IntEvent
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.propagation.boundLiteral
import com.eignex.klause.propagation.domainAt
import com.eignex.klause.propagation.lazyReason
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet
import com.eignex.klause.util.LongArrayList

/**
 * CP propagator for [Diffn]. Constructed by the propagation projection and holds pairwise
 * compulsory-parts / disjunctive propagation for the constant-size case, plus a
 * sound-only infeasibility check for the variable-size case.
 */
internal class DiffnPropagator(
    val intVars: IntArray,
    private val xs: IntArray,
    private val ys: IntArray,
    private val widths: LongArray,
    private val heights: LongArray,
    private val widthVars: IntArray?,
    private val heightVars: IntArray?,
    private val nonStrict: Boolean,
    private val n: Int,
    private val varSize: Boolean,
) : Propagator {

    override val expensiveBake: Boolean get() = true

    override val initialIntEventWatches: IntArray = IntEvent.boundEventWatches(intVars)

    override fun explain(state: PropagationState, factorId: Int, payload: IntArray, atTrail: Int, atLevel: Int) =
        Region(state, atTrail, atLevel, payload[0] == 0).apply {
            val bound = (payload[3].toLong() shl 32) or (payload[4].toLong() and 0xFFFFFFFFL)
            moved(payload[1], lower = payload[2] == 1, bound = bound)
        }.literals()

    /**
     * Rectangle origins and compulsory parts as of [atTrail] along one axis ([xAxis] primary), with the literals a
     * reason over them collects.
     */
    private inner class Region(val state: PropagationState, val atTrail: Int, val atLevel: Int, xAxis: Boolean) {
        private val pos = if (xAxis) xs else ys
        private val size = if (xAxis) widths else heights
        private val opos = if (xAxis) ys else xs
        private val osize = if (xAxis) heights else widths
        private val seen = IntHashSet()
        private val out = IntArrayList()

        private fun add(lit: Int) {
            if (lit != Lit.NONE && seen.add(lit)) out.add(lit)
        }

        private fun lo(v: Int) = state.domainAt(v, atTrail).min
        private fun hi(v: Int) = state.domainAt(v, atTrail).max
        private fun ge(v: Int, need: Long) = add(state.boundLiteral(v, true, need, atTrail, atLevel))
        private fun le(v: Int, need: Long) = add(state.boundLiteral(v, false, need, atTrail, atLevel))

        /**
         * Rectangle [i] can take no origin in primary columns [from]..[to]: every such placement, at every
         * orthogonal origin it has, meets the compulsory part of another rectangle. Cite those parts that meet
         * the region it would sweep, and [i]'s orthogonal range.
         */
        fun blocked(i: Int, from: Long, to: Long) {
            val pLo = from
            val pHi = to + size[i] - 1
            val oLo = lo(opos[i])
            val oHi = hi(opos[i]) + osize[i] - 1
            ge(opos[i], oLo)
            le(opos[i], hi(opos[i]))
            for (j in 0 until n) {
                if (j == i || size[j] <= 0 || osize[j] <= 0) continue
                val cpLo = hi(pos[j])
                val cpHi = lo(pos[j]) + size[j] - 1
                val coLo = hi(opos[j])
                val coHi = lo(opos[j]) + osize[j] - 1
                if (cpLo > cpHi || coLo > coHi) continue
                if (cpHi < pLo || cpLo > pHi || coHi < oLo || coLo > oHi) continue
                le(pos[j], cpLo)
                ge(pos[j], lo(pos[j]))
                le(opos[j], coLo)
                ge(opos[j], lo(opos[j]))
            }
        }

        /** [i]'s origin moved to [bound] ([lower]) past columns it could not take. */
        fun moved(i: Int, lower: Boolean, bound: Long) {
            if (lower) {
                ge(pos[i], lo(pos[i]))
                blocked(i, lo(pos[i]), bound - 1)
            } else {
                le(pos[i], hi(pos[i]))
                blocked(i, bound + 1, hi(pos[i]))
            }
        }

        /** [i] has no column left: every column of its range is blocked. */
        fun stuck(i: Int) {
            ge(pos[i], lo(pos[i]))
            le(pos[i], hi(pos[i]))
            blocked(i, lo(pos[i]), hi(pos[i]))
        }

        fun ownDomain(v: Int) {
            ge(v, lo(v))
            le(v, hi(v))
        }

        fun literals(): IntArray = out.toIntArray()
    }

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? {
        state.propagatorFailures[this]?.let { return it }
        // Sharp reason for the dominant constant-size conflict: a pair forced to overlap on both
        // axes. Those four origin variables' bounds alone imply the contradiction, so citing only
        // them is sound and far tighter than the whole scope. Any other failure (sweep dead-end,
        // variable-size) falls back to the sound whole-scope reason.
        if (!varSize) {
            for (i in 0 until n) {
                val wI = widths[i]
                val hI = heights[i]
                if (nonStrict && (wI == 0L || hI == 0L)) continue
                for (j in i + 1 until n) {
                    val wJ = widths[j]
                    val hJ = heights[j]
                    if (nonStrict && (wJ == 0L || hJ == 0L)) continue
                    val xMust = state.intDomains[xs[i]].max < state.intDomains[xs[j]].min + wJ &&
                        state.intDomains[xs[j]].max < state.intDomains[xs[i]].min + wI
                    val yMust = state.intDomains[ys[i]].max < state.intDomains[ys[j]].min + hJ &&
                        state.intDomains[ys[j]].max < state.intDomains[ys[i]].min + hI
                    if (xMust && yMust) {
                        return collectLinearTightenAntecedents(
                            state,
                            intArrayOf(xs[i], ys[i], xs[j], ys[j]),
                            excludeIdx = -1,
                            extraLit = 0,
                        )
                    }
                }
            }
        }
        return collectLinearTightenAntecedents(state, intVars, excludeIdx = -1, extraLit = 0)
    }

    /**
     * Pairwise compulsory-parts / disjunctive propagation (constant-size only). When any
     * dimension is variable the size-dependent bound reasoning does not hold, so we fall
     * back to the sound check: with the *minimum* possible sizes, if a pair must still overlap
     * on both axes the constraint is infeasible; otherwise no pruning. This keeps propagation
     * sound (never removes a feasible value) while LS does the heavy lifting on var-size diffn.
     */
    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        state.propagatorFailures.remove(this)
        if (varSize) return propagateVarSizeSoundOnly(state)
        // Sweep each axis: advance every rectangle's origin to the first column where some orthogonal
        // position escapes all other rectangles' compulsory parts. This subsumes pairwise reasoning
        // (a forced overlap gives both rectangles a compulsory part the sweep already sees) and also
        // catches multi-rectangle walls no single pair rules out.
        if (!sweepAxis(state, xs, widths, ys, heights, xAxis = true)) return false
        if (!sweepAxis(state, ys, heights, xs, widths, xAxis = false)) return false
        return true
    }

    /**
     * Sweep the primary axis ([pos] / [size]) for every rectangle, tightening its origin to the
     * first / last column admitting a collision-free orthogonal ([opos] / [osize]) position. A
     * column's feasibility only changes at the entry / exit of another rectangle's compulsory part,
     * so it is evaluated once per such breakpoint segment rather than per unit.
     */
    @Suppress("ReturnCount", "NestedBlockDepth", "LongParameterList")
    private fun sweepAxis(
        state: PropagationState,
        pos: IntArray,
        size: LongArray,
        opos: IntArray,
        osize: LongArray,
        xAxis: Boolean,
    ): Boolean {
        fun now() = Region(state, state.undo.size, state.currentLevel, xAxis)
        fun failStuck(i: Int): Boolean {
            state.propagatorFailures[this] = now().apply { stuck(i) }.literals()
            return false
        }

        // An origin move, recorded for [explain]; it rests on the compulsory parts that blocked the skipped columns.
        fun movedReason(i: Int, lower: Boolean, bound: Long): IntArray? {
            val payload =
                intArrayOf(if (xAxis) 0 else 1, i, if (lower) 1 else 0, (bound ushr 32).toInt(), bound.toInt())
            return when {
                state.currentLevel == 0 -> null
                state.undoLogging -> state.lazyReason(payload)
                else -> now().apply { moved(i, lower, bound) }.literals()
            }
        }
        fun failMove(i: Int, lower: Boolean, bound: Long): Boolean {
            state.propagatorFailures[this] = now().apply {
                moved(i, lower, bound)
                ownDomain(pos[i])
            }.literals()
            return false
        }
        for (i in 0 until n) {
            if (size[i] <= 0 || osize[i] <= 0) continue
            val pMin = state.intDomains[pos[i]].min
            val pMax = state.intDomains[pos[i]].max
            if (pMin == pMax) {
                if (!feasibleColumn(state, i, pMin, pos, size, opos, osize)) return failStuck(i)
                continue
            }
            // Segment starts: pMin plus every compulsory-part entry/exit breakpoint inside (pMin, pMax].
            val starts = LongArrayList()
            starts.add(pMin)
            for (j in 0 until n) {
                if (j == i || size[j] <= 0 || osize[j] <= 0) continue
                val pcLo = state.intDomains[pos[j]].max
                val pcHi = state.intDomains[pos[j]].min + size[j]
                if (pcLo >= pcHi) continue
                val enter = pcLo - size[i] + 1
                val exit = pcHi
                if (enter in (pMin + 1)..pMax) starts.add(enter)
                if (exit in (pMin + 1)..pMax) starts.add(exit)
            }
            starts.sort()
            // First feasible segment start → new lower bound.
            var newMin = Long.MIN_VALUE
            for (k in 0 until starts.size) {
                val s = starts[k]
                if (s > newMin && feasibleColumn(state, i, s, pos, size, opos, osize)) {
                    newMin = s
                    break
                }
            }
            if (newMin == Long.MIN_VALUE) return failStuck(i)
            if (newMin > pMin && !state.tightenIntMin(pos[i], newMin, movedReason(i, true, newMin))) {
                return failMove(i, true, newMin)
            }
            // Last feasible segment → new upper bound (segment end clamped to pMax).
            var newMax = Long.MAX_VALUE
            for (k in starts.size - 1 downTo 0) {
                val s = starts[k]
                if (s < newMin) break
                val segEnd = if (k + 1 < starts.size) starts[k + 1] - 1 else pMax
                val end = minOf(segEnd, pMax)
                if (end < newMin) continue
                if (feasibleColumn(state, i, s, pos, size, opos, osize)) {
                    newMax = end
                    break
                }
            }
            if (newMax == Long.MAX_VALUE) return failStuck(i)
            if (newMax < state.intDomains[pos[i]].max &&
                !state.tightenIntMax(pos[i], newMax, movedReason(i, false, newMax))
            ) {
                return failMove(i, false, newMax)
            }
        }
        return true
    }

    /** Whether rectangle [i]'s origin at primary column [x] leaves some orthogonal position clear of
     *  every other rectangle's compulsory part. */
    @Suppress("LongParameterList")
    private fun feasibleColumn(
        state: PropagationState,
        i: Int,
        x: Long,
        pos: IntArray,
        size: LongArray,
        opos: IntArray,
        osize: LongArray,
    ): Boolean {
        // Forbidden orthogonal-origin intervals contributed by rectangles whose compulsory primary
        // part overlaps [x, x+size[i]).
        val lows = LongArrayList()
        val highs = LongArrayList()
        for (j in 0 until n) {
            if (j == i || size[j] <= 0 || osize[j] <= 0) continue
            val pcLo = state.intDomains[pos[j]].max
            val pcHi = state.intDomains[pos[j]].min + size[j]
            if (pcLo >= pcHi) continue
            if (x >= pcHi || x + size[i] <= pcLo) continue // no primary overlap
            val ocLo = state.intDomains[opos[j]].max
            val ocHi = state.intDomains[opos[j]].min + osize[j]
            if (ocLo >= ocHi) continue // j has no compulsory orthogonal part
            lows.add(ocLo - osize[i] + 1)
            highs.add(ocHi - 1)
        }
        // Scan [oMin, oMax] for an origin not covered by any forbidden interval.
        val oMin = state.intDomains[opos[i]].min
        val oMax = state.intDomains[opos[i]].max
        val order = (0 until lows.size).sortedBy { lows[it] }
        var cursor = oMin
        for (idx in order) {
            val a = lows[idx]
            val b = highs[idx]
            if (b < cursor) continue
            if (a > cursor) return true // gap at cursor
            cursor = b + 1
            if (cursor > oMax) return false
        }
        return cursor <= oMax
    }

    /** Sound-only infeasibility check for the variable-size case: a pair is unconditionally
     *  infeasible iff it must overlap on both axes even at the *smallest* sizes each var allows. */
    private fun propagateVarSizeSoundOnly(state: PropagationState): Boolean {
        val wvars = widthVars
        val hvars = heightVars
        fun wMin(i: Int): Long = if (wvars == null) widths[i] else state.intDomains[wvars[i]].min
        fun hMin(i: Int): Long = if (hvars == null) heights[i] else state.intDomains[hvars[i]].min
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val wI = wMin(i)
                val hI = hMin(i)
                val wJ = wMin(j)
                val hJ = hMin(j)
                if (nonStrict && (wI == 0L || hI == 0L || wJ == 0L || hJ == 0L)) continue
                val xMust = state.intDomains[xs[i]].max < state.intDomains[xs[j]].min + wJ &&
                    state.intDomains[xs[j]].max < state.intDomains[xs[i]].min + wI
                val yMust = state.intDomains[ys[i]].max < state.intDomains[ys[j]].min + hJ &&
                    state.intDomains[ys[j]].max < state.intDomains[ys[i]].min + hI
                if (xMust && yMust) {
                    // The pair overlaps on both axes even at the least sizes they allow.
                    val r = IntArrayList()
                    for (v in intArrayOf(xs[i], ys[i], xs[j], ys[j])) {
                        collectLinearTightenAntecedents(state, intArrayOf(v), -1, 0)?.forEach { r.add(it) }
                    }
                    for (k in intArrayOf(i, j)) {
                        for (sv in listOfNotNull(wvars?.get(k), hvars?.get(k))) {
                            val least = state.intDomains[sv].min
                            val lit = state.boundLiteral(sv, true, least, state.undo.size, state.currentLevel)
                            if (lit != Lit.NONE) r.add(lit)
                        }
                    }
                    state.propagatorFailures[this] = r.toIntArray()
                    return false
                }
            }
        }
        return true
    }
}
