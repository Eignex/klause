package com.eignex.klause.factor.table.internals

import com.eignex.klause.ir.Lit
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.domainAt
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet

/**
 * Reasons for [com.eignex.klause.factor.table.Mdd] deductions as cuts of the layered diagram over the bounds at a
 * past undo-log position. The diagram reads only each symbol's bounds, so an edge on symbol `s` from layer `i` is
 * live while `s` lies within `seq(i)`'s bounds, and a cut edge cites the bound its symbol fell past.
 *
 * A symbol leaves `seq(i)` when each of its edges starts at a state the root cannot reach or ends at one that
 * cannot reach acceptance; unreachability is explained by the state's edges on that side, each either cut or
 * leading to a state unreachable in turn, and each state is explained once per reason.
 *
 * A cost bound `cost >= L` holds because every live accepting path weighs at least `L`. A lighter path, if one
 * existed, would use some cut edge, and every edge of a path lighter than `L` lies on a path of the uncut diagram
 * lighter than `L`; so citing each cut edge on such a path suffices. The upper bound is the mirror image.
 */
internal class MddExplainer(
    private val seq: IntArray,
    private val numStatesPerLayer: IntArray,
    private val transitions: LongArray,
    private val initial: Int,
    private val accepting: IntArray,
    private val cost: Int,
    private val index: () -> MddTransitionIndex,
) {
    private val n = seq.size

    /** Reason for the symbols position [pos] lost as of [atTrail]. */
    fun prune(state: PropagationState, pos: Int, atTrail: Int): IntArray {
        val cut = Cut(state, atTrail)
        val idx = index()
        val (lo, hi) = cut.bounds(pos)
        val supported = HashSet<Long>()
        cut.forEachRecord(pos) { src, sym, dst, _ ->
            if (sym in lo..hi && cut.fwd[pos][src] && cut.bwd[pos + 1][dst]) supported.add(sym)
        }
        val head = idx.fwdHead[pos]
        val ptr = idx.fwdPtr[pos]
        for (src in 0 until numStatesPerLayer[pos]) {
            for (k in head[src] until head[src + 1]) {
                val p = ptr[k]
                val sym = transitions[p + 1]
                val dst = transitions[p + 2].toInt()
                if (sym !in lo..hi || sym in supported || dst !in 0 until numStatesPerLayer[pos + 1]) continue
                if (!cut.fwd[pos][src]) cut.unreachable(pos, src) else cut.dead(pos + 1, dst)
            }
        }
        return cut.literals()
    }

    /** Reason for no accepted word remaining as of [atTrail]: no accepting state is reachable. */
    fun conflict(state: PropagationState, atTrail: Int): IntArray {
        val cut = Cut(state, atTrail)
        if (accepting.any { it in 0 until numStatesPerLayer[n] && cut.fwd[n][it] }) {
            // Only the cost can then be at odds with the diagram.
            check(cost >= 0) { "an mdd without cost failed with an accepted word" }
            return costConflict(state, cut)
        }
        for (q in accepting) if (q in 0 until numStatesPerLayer[n]) cut.unreachable(n, q)
        return cut.literals()
    }

    /** Reason for the cost's lower ([lower]) or upper bound the diagram derived as of [atTrail]. */
    fun costBound(state: PropagationState, lower: Boolean, atTrail: Int): IntArray {
        val cut = Cut(state, atTrail)
        cut.cutLighter(lower, cut.livePathWeight(lower))
        return cut.literals()
    }

    // The diagram still accepts a word, so the conflict is its weight against the cost's bounds: every live path
    // weighs more than the cost's upper bound allows, or less than its lower bound.
    private fun costConflict(state: PropagationState, cut: Cut): IntArray {
        val d = state.domainAt(cost, cut.atTrail)
        val root = state.rootDomains[cost]
        if (cut.livePathWeight(lower = true) > d.max) {
            if (d.max < root.max) cut.add(Lit.make(state.atomVarLe(cost, d.max), false))
            cut.cutLighter(lower = true, bound = d.max + 1)
        } else {
            if (d.min > root.min) cut.add(Lit.make(state.atomVarGe(cost, d.min), false))
            cut.cutLighter(lower = false, bound = d.min - 1)
        }
        return cut.literals()
    }

    private inner class Cut(private val state: PropagationState, val atTrail: Int) {
        private val idx = index()
        private val lo = LongArray(n)
        private val hi = LongArray(n)
        val fwd = Array(n + 1) { BooleanArray(numStatesPerLayer[it]) }
        val bwd = Array(n + 1) { BooleanArray(numStatesPerLayer[it]) }
        private val explainedFwd = Array(n + 1) { BooleanArray(numStatesPerLayer[it]) }
        private val explainedBwd = Array(n + 1) { BooleanArray(numStatesPerLayer[it]) }
        private val seen = IntHashSet()
        private val out = IntArrayList()

        init {
            for (i in 0 until n) {
                val d = state.domainAt(seq[i], atTrail)
                lo[i] = d.min
                hi[i] = d.max
            }
            if (initial in 0 until numStatesPerLayer[0]) fwd[0][initial] = true
            for (i in 0 until n) {
                forEachRecord(i) { src, sym, dst, _ -> if (fwd[i][src] && sym in lo[i]..hi[i]) fwd[i + 1][dst] = true }
            }
            for (q in accepting) if (q in 0 until numStatesPerLayer[n]) bwd[n][q] = true
            for (i in n - 1 downTo 0) {
                forEachRecord(i) { src, sym, dst, _ -> if (bwd[i + 1][dst] && sym in lo[i]..hi[i]) bwd[i][src] = true }
            }
        }

        fun bounds(pos: Int): Pair<Long, Long> = lo[pos] to hi[pos]

        inline fun forEachRecord(layer: Int, action: (src: Int, sym: Long, dst: Int, weight: Long) -> Unit) {
            val head = idx.fwdHead[layer]
            val ptr = idx.fwdPtr[layer]
            val numN = numStatesPerLayer[layer + 1]
            for (src in 0 until numStatesPerLayer[layer]) {
                for (k in head[src] until head[src + 1]) {
                    val p = ptr[k]
                    val dst = transitions[p + 2].toInt()
                    if (dst in 0 until numN) action(src, transitions[p + 1], dst, if (cost >= 0) transitions[p + 3] else 0L)
                }
            }
        }

        /** Explain why [q] at [layer] is unreachable from the root: each incoming edge is cut or unreachable. */
        fun unreachable(layer: Int, q: Int) {
            val stack = IntArrayList()
            push(stack, explainedFwd, layer, q)
            while (stack.size > 0) {
                val i = stack[stack.size - 2]
                val t = stack[stack.size - 1]
                stack.truncateTo(stack.size - 2)
                if (i == 0) continue // only the root begins reachable
                val head = idx.bwdHead[i - 1]
                val srcs = idx.bwdSrc[i - 1]
                val syms = idx.bwdSym[i - 1]
                for (k in head[t] until head[t + 1]) {
                    val p = srcs[k]
                    if (p !in 0 until numStatesPerLayer[i - 1]) continue
                    if (!fwd[i - 1][p]) push(stack, explainedFwd, i - 1, p) else cite(i - 1, syms[k])
                }
            }
        }

        /** Explain why [q] at [layer] cannot reach acceptance: each outgoing edge is cut or leads nowhere. */
        fun dead(layer: Int, q: Int) {
            val stack = IntArrayList()
            push(stack, explainedBwd, layer, q)
            while (stack.size > 0) {
                val i = stack[stack.size - 2]
                val s = stack[stack.size - 1]
                stack.truncateTo(stack.size - 2)
                if (i == n) continue // a non-accepting final state accepts nothing
                val head = idx.fwdHead[i]
                val ptr = idx.fwdPtr[i]
                for (k in head[s] until head[s + 1]) {
                    val p = ptr[k]
                    val dst = transitions[p + 2].toInt()
                    if (dst !in 0 until numStatesPerLayer[i + 1]) continue
                    if (!bwd[i + 1][dst]) push(stack, explainedBwd, i + 1, dst) else cite(i, transitions[p + 1])
                }
            }
        }

        /** The lightest ([lower]) or heaviest live accepting path's weight. */
        fun livePathWeight(lower: Boolean): Long {
            val best = extremeFromRoot(lower, live = true)
            var w = if (lower) INF else -INF
            for (q in accepting) {
                if (q !in 0 until numStatesPerLayer[n]) continue
                w = if (lower) minOf(w, best[n][q]) else maxOf(w, best[n][q])
            }
            return w
        }

        /** Cite each cut edge on an uncut path lighter than [bound] ([lower]), or heavier than it otherwise. */
        fun cutLighter(lower: Boolean, bound: Long) {
            val from = extremeFromRoot(lower, live = false)
            val to = extremeToAccept(lower)
            val none = if (lower) INF else -INF
            for (i in 0 until n) {
                forEachRecord(i) { src, sym, dst, w ->
                    if (sym in lo[i]..hi[i]) return@forEachRecord
                    val a = from[i][src]
                    val b = to[i + 1][dst]
                    if (a == none || b == none) return@forEachRecord
                    val through = a + w + b
                    if (if (lower) through < bound else through > bound) cite(i, sym)
                }
            }
        }

        // Lightest (heaviest) weight from the root to each state, over live edges or the whole diagram.
        private fun extremeFromRoot(lower: Boolean, live: Boolean): Array<LongArray> {
            val none = if (lower) INF else -INF
            val best = Array(n + 1) { LongArray(numStatesPerLayer[it]) { none } }
            if (initial in 0 until numStatesPerLayer[0]) best[0][initial] = 0L
            for (i in 0 until n) {
                forEachRecord(i) { src, sym, dst, w ->
                    if (best[i][src] == none || live && sym !in lo[i]..hi[i]) return@forEachRecord
                    val c = best[i][src] + w
                    if (if (lower) c < best[i + 1][dst] else c > best[i + 1][dst]) best[i + 1][dst] = c
                }
            }
            return best
        }

        // Lightest (heaviest) weight from each state to acceptance over the whole diagram.
        private fun extremeToAccept(lower: Boolean): Array<LongArray> {
            val none = if (lower) INF else -INF
            val best = Array(n + 1) { LongArray(numStatesPerLayer[it]) { none } }
            for (q in accepting) if (q in 0 until numStatesPerLayer[n]) best[n][q] = 0L
            for (i in n - 1 downTo 0) {
                forEachRecord(i) { src, _, dst, w ->
                    if (best[i + 1][dst] == none) return@forEachRecord
                    val c = best[i + 1][dst] + w
                    if (if (lower) c < best[i][src] else c > best[i][src]) best[i][src] = c
                }
            }
            return best
        }

        private fun push(stack: IntArrayList, explained: Array<BooleanArray>, layer: Int, q: Int) {
            if (explained[layer][q]) return
            explained[layer][q] = true
            stack.add(layer)
            stack.add(q)
        }

        // A cut edge's symbol lay past position [pos]'s bound; the diagram never reads holes.
        private fun cite(pos: Int, sym: Long) {
            val v = seq[pos]
            val root = state.rootDomains[v]
            when {
                sym < lo[pos] -> if (sym >= root.min) add(Lit.make(state.atomVarGe(v, lo[pos]), false))
                sym > hi[pos] -> if (sym <= root.max) add(Lit.make(state.atomVarLe(v, hi[pos]), false))
            }
        }

        fun add(lit: Int) {
            if (seen.add(lit)) out.add(lit)
        }

        fun literals(): IntArray = out.toIntArray()
    }

    private companion object {
        const val INF = Long.MAX_VALUE / 4
    }
}
