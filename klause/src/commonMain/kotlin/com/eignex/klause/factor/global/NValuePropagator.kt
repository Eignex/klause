package com.eignex.klause.factor.global

import com.eignex.klause.factor.arithmetic.internals.collectHoleAndBoundAntecedents
import com.eignex.klause.factor.circuit.internals.cpGateShouldSkip
import com.eignex.klause.factor.global.internals.reginTarjanScc
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet

/** CP propagation logic for `nvalue`. */
internal class NValuePropagator(
    val boolVars: IntArray,
    val intVars: IntArray,
    private val n: Int,
    private val xs: IntArray,
    private val mode: NValue.Mode,
    private val presents: IntArray,
    private val initialIntEventWatchesVal: IntArray?,
    private val consumesIntEventDeltaVal: Boolean,
    private val definitelyAbsentNvFn: (Int, PropagationState) -> Boolean,
    private val definitelyPresentNvFn: (Int, PropagationState) -> Boolean,
) : Propagator {

    override val initialIntEventWatches: IntArray? get() = initialIntEventWatchesVal

    override val consumesIntEventDelta: Boolean get() = consumesIntEventDeltaVal

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        collectHoleAndBoundAntecedents(state, intVars)

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        // The optional-presence variant keeps the order-insensitive greedy bounds: a presence flip
        // changes the count without an int-domain event, so the stronger domain-driven filtering
        // (which assumes every counted variable is present) does not apply cleanly.
        if (presents.isNotEmpty()) return propagateGreedy(state)

        if (state.cpGateShouldSkip(factorId)) return true

        val ant = collectHoleAndBoundAntecedents(state, intVars)
        // atLeast / eq: the distinct count cannot exceed the maximum number of variables that can be
        // assigned pairwise-distinct values — a maximum bipartite var↦value matching. Tighter than
        // |union of domains|, which ignores that there are only `xs.size` variables. When the count is
        // pinned to that maximum, a maximum matching is mandatory, so Régin value-pruning applies.
        if (mode != NValue.Mode.AtMost) {
            val matching = buildMatching(state)
            if (!state.tightenIntMax(n, matching.size, ant)) return false
            if (state.intDomains[n].min == matching.size) {
                if (!atLeastGacPrune(state, matching, ant)) return false
            }
        }
        // atMost / eq: Beldiceanu's O(n+d) bound-consistency — the count is at least the size of a
        // maximal set of pairwise-disjoint value windows, and when that lower bound meets `n`'s upper
        // bound every variable is forced into the window of its kernel representative.
        if (mode != NValue.Mode.AtLeast) {
            if (!kernelBoundConsistency(state, ant)) return false
        }
        return true
    }

    /** The original order-insensitive greedy bounds, retained for the optional-presence variant. */
    private fun propagateGreedy(state: PropagationState): Boolean {
        val unionValues = IntHashSet()
        for (i in xs.indices) {
            if (definitelyAbsentNvFn(i, state)) continue
            state.intDomains[xs[i]].forEach { unionValues.add(it) }
        }
        val maxDistinct = unionValues.size
        val present = IntArrayList(xs.size)
        for (i in xs.indices) if (definitelyPresentNvFn(i, state)) present.add(xs[i])
        present.sortByIntKey { state.intDomains[it].size }
        val covered = IntHashSet()
        var minDistinct = 0
        for (idx in 0 until present.size) {
            val d = state.intDomains[present[idx]]
            var disjoint = true
            d.forEach { if (covered.contains(it)) disjoint = false }
            if (disjoint) {
                minDistinct++
                d.forEach { covered.add(it) }
            }
        }
        val ant = collectHoleAndBoundAntecedents(state, xs)
        when (mode) {
            NValue.Mode.Eq -> {
                if (!state.tightenIntMin(n, minDistinct, ant)) return false
                if (!state.tightenIntMax(n, maxDistinct, ant)) return false
            }

            NValue.Mode.AtLeast -> {
                if (!state.tightenIntMax(n, maxDistinct, ant)) return false
            }

            NValue.Mode.AtMost -> {
                if (!state.tightenIntMin(n, minDistinct, ant)) return false
            }
        }
        return true
    }

    /** A maximum bipartite matching between `xs` and their domain values (Kuhn's algorithm). */
    private class Matching(
        val varToVal: IntArray,
        val valToVar: IntArray,
        val values: IntArray,
        val adj: Array<IntArrayList>,
        val size: Int,
    )

    private fun buildMatching(state: PropagationState): Matching {
        val valueId = HashMap<Int, Int>()
        val values = IntArrayList()
        val adj = Array(xs.size) { i ->
            val ids = IntArrayList()
            state.intDomains[xs[i]].forEach { v ->
                val id = valueId.getOrPut(v) {
                    values.add(v)
                    values.size - 1
                }
                ids.add(id)
            }
            ids
        }
        val nVals = values.size
        val valToVar = IntArray(nVals) { -1 }
        val seen = BooleanArray(nVals)
        var matched = 0
        for (i in xs.indices) {
            seen.fill(false)
            if (augment(i, adj, valToVar, seen)) matched++
        }
        val varToVal = IntArray(xs.size) { -1 }
        for (v in 0 until nVals) if (valToVar[v] >= 0) varToVal[valToVar[v]] = v
        return Matching(varToVal, valToVar, values.toIntArray(), adj, matched)
    }

    /**
     * Régin value-pruning for atLeast/eq once the count is pinned to the maximum matching size: a
     * maximum matching is then mandatory, so any var-value pair that lies in no maximum matching has
     * no support and is removed, while a matched pair that crosses a strong component (in every
     * maximum matching) is forced. Orientation: matched edges value→var, the rest var→value, with a
     * source/sink wiring free vars/values so alternating paths from exposed vertices join one SCC.
     */
    @Suppress("ReturnCount", "NestedBlockDepth")
    private fun atLeastGacPrune(state: PropagationState, m: Matching, ant: IntArray?): Boolean {
        val nv = xs.size
        val nVals = m.valToVar.size
        val total = nv + nVals + 2
        val src = nv + nVals
        val sink = nv + nVals + 1
        val adj = Array(total) { IntArrayList() }
        for (i in 0 until nv) {
            val row = m.adj[i]
            for (k in 0 until row.size) {
                val vId = row[k]
                val vNode = nv + vId
                if (m.varToVal[i] == vId) adj[vNode].add(i) else adj[i].add(vNode)
            }
            if (m.varToVal[i] == -1) adj[src].add(i) else adj[i].add(src)
        }
        for (vId in 0 until nVals) {
            val vNode = nv + vId
            if (m.valToVar[vId] == -1) adj[vNode].add(sink) else adj[sink].add(vNode)
        }
        val scc = reginTarjanScc(adj, total)
        for (i in 0 until nv) {
            val vIds = m.adj[i].toIntArray()
            for (vId in vIds) {
                if (scc[i] == scc[nv + vId]) continue
                val value = m.values[vId]
                if (m.varToVal[i] == vId) {
                    if (!state.setInt(xs[i], value, ant)) return false
                } else {
                    if (!state.excludeIntValue(xs[i], value, ant)) return false
                }
            }
        }
        return true
    }

    private fun augment(i: Int, adj: Array<IntArrayList>, matchValToVar: IntArray, seen: BooleanArray): Boolean {
        val row = adj[i]
        for (k in 0 until row.size) {
            val v = row[k]
            if (seen[v]) continue
            seen[v] = true
            if (matchValToVar[v] == -1 || augment(matchValToVar[v], adj, matchValToVar, seen)) {
                matchValToVar[v] = i
                return true
            }
        }
        return false
    }

    /**
     * Bound-consistency for atMost (Beldiceanu, "Filtering Algorithms for the NValue Constraint").
     * Mirrors choco's `PropAtMostNValues_BC`: a kernel is a maximal set of pairwise-disjoint
     * `[lb, ub]` windows; its size lower-bounds the distinct count, and when it equals `n`'s upper
     * bound each variable is squeezed into the window of its kernel representative. Bounds-only, so
     * interior holes are ignored (sound — the interval over-approximates the domain). Each `while`
     * iteration takes one snapshot of the bounds and runs both a lower-bound pass and an upper-bound
     * pass off it (as in choco), looping until neither pass narrows anything.
     */
    private fun kernelBoundConsistency(state: PropagationState, ant: IntArray?): Boolean {
        val nv = xs.size
        val minVal = IntArray(nv)
        val maxVal = IntArray(nv)
        val order = IntArray(nv)
        var loop = true
        while (loop) {
            loop = false
            for (i in 0 until nv) {
                minVal[i] = state.intDomains[xs[i]].min
                maxVal[i] = state.intDomains[xs[i]].max
            }
            val rLb = kernelPass(state, ant, nv, minVal, maxVal, order, lowerPass = true)
            if (rLb < 0) return false
            if (rLb > 0) loop = true
            val rUb = kernelPass(state, ant, nv, minVal, maxVal, order, lowerPass = false)
            if (rUb < 0) return false
            if (rUb > 0) loop = true
        }
        return true
    }

    /** One kernel pass. Returns -1 on conflict, 1 if it narrowed a domain, 0 otherwise. */
    @Suppress("LongParameterList", "ReturnCount")
    private fun kernelPass(
        state: PropagationState,
        ant: IntArray?,
        nv: Int,
        minVal: IntArray,
        maxVal: IntArray,
        order: IntArray,
        lowerPass: Boolean,
    ): Int {
        // Scan variables in value order (lower bound ascending, or upper bound descending), ties by
        // index descending — matching choco's bucket-and-reverse traversal so the kernel grouping is
        // identical. A window closes whenever the next variable cannot overlap the running one. The
        // order is produced by sorting packed `(primary << 32) | tie` longs to avoid boxed Integers:
        // `primary` is `minVal` (or `-maxVal` to sort descending) and `tie = nv-1-i` makes equal
        // primaries break by index descending.
        val packed = LongArray(nv)
        for (i in 0 until nv) {
            val primary = (if (lowerPass) minVal[i] else -maxVal[i]).toLong()
            packed[i] = (primary shl 32) or ((nv - 1 - i).toLong() and 0xFFFFFFFFL)
        }
        packed.sort()
        for (idx in 0 until nv) order[idx] = nv - 1 - (packed[idx] and 0xFFFFFFFFL).toInt()
        val kerRep = BooleanArray(nv)
        var min = Int.MIN_VALUE
        var max = Int.MIN_VALUE
        var nbKer = 0
        for (idx in 0 until nv) {
            val node = order[idx]
            if (min == Int.MIN_VALUE) {
                min = minVal[node]
                max = maxVal[node]
                nbKer++
            } else if (overlaps(lowerPass, minVal[node], maxVal[node], min, max)) {
                min = maxOf(min, minVal[node])
                max = minOf(max, maxVal[node])
            } else {
                min = minVal[node]
                max = maxVal[node]
                kerRep[node] = true
                nbKer++
            }
        }
        var status = 0
        if (state.intDomains[n].min < nbKer) {
            if (!state.tightenIntMin(n, nbKer, ant)) return -1
            status = 1
        }
        // When the kernel count is forced to equal n's max, no variable may stray outside its
        // window's value range, so squeeze each group.
        if (nbKer == state.intDomains[n].max) {
            val stamp = IntArrayList()
            for (idx in 0 until nv) {
                val node = order[idx]
                if (kerRep[node]) {
                    val s = squeeze(state, ant, stamp, if (lowerPass) minVal[node] else maxVal[node], lowerPass)
                    if (s < 0) return -1
                    if (s > 0) status = 1
                    stamp.clear()
                }
                stamp.add(node)
            }
            val s = squeeze(state, ant, stamp, if (lowerPass) Int.MAX_VALUE else Int.MIN_VALUE, lowerPass)
            if (s < 0) return -1
            if (s > 0) status = 1
        }
        return status
    }

    private fun overlaps(lowerPass: Boolean, nodeMin: Int, nodeMax: Int, min: Int, max: Int): Boolean =
        if (lowerPass) nodeMin <= max else nodeMax >= min

    /**
     * The variables in [stamp] form one kernel group; any member that cannot reach the next group's
     * [frontier] value is clamped to the group's tightest shared bound. Returns -1 on conflict, 1 if
     * it narrowed a domain, 0 otherwise.
     */
    private fun squeeze(
        state: PropagationState,
        ant: IntArray?,
        stamp: IntArrayList,
        frontier: Int,
        lowerPass: Boolean,
    ): Int {
        var status = 0
        if (lowerPass) {
            var newMin = Int.MIN_VALUE
            for (i in 0 until stamp.size) {
                val vid = xs[stamp[i]]
                if (state.intDomains[vid].max < frontier) newMin = maxOf(newMin, state.intDomains[vid].min)
            }
            if (newMin == Int.MIN_VALUE) return 0
            for (i in 0 until stamp.size) {
                val vid = xs[stamp[i]]
                if (state.intDomains[vid].max < frontier && state.intDomains[vid].min < newMin) {
                    if (!state.tightenIntMin(vid, newMin, ant)) return -1
                    status = 1
                }
            }
        } else {
            var newMax = Int.MAX_VALUE
            for (i in 0 until stamp.size) {
                val vid = xs[stamp[i]]
                if (state.intDomains[vid].min > frontier) newMax = minOf(newMax, state.intDomains[vid].max)
            }
            if (newMax == Int.MAX_VALUE) return 0
            for (i in 0 until stamp.size) {
                val vid = xs[stamp[i]]
                if (state.intDomains[vid].min > frontier && state.intDomains[vid].max > newMax) {
                    if (!state.tightenIntMax(vid, newMax, ant)) return -1
                    status = 1
                }
            }
        }
        return status
    }
}
