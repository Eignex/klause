package com.eignex.klause.factor.global

import com.eignex.klause.config.DEFAULT_DOMAIN_WALK_CAP
import com.eignex.klause.factor.OptPresence
import com.eignex.klause.factor.arithmetic.internals.collectHoleAndBoundAntecedents
import com.eignex.klause.factor.circuit.internals.cpGateShouldSkip
import com.eignex.klause.factor.global.internals.hallReason
import com.eignex.klause.factor.global.internals.reginTarjanScc
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.values
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.propagation.boundLiteral
import com.eignex.klause.util.EmptyIntArray
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet
import com.eignex.klause.util.LongArrayList
import com.eignex.klause.util.LongHashSet
import com.eignex.klause.util.MutableLongIntMap

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

    // The reason of the failure the last [propagate] hit, read by [conflictReason] before the engine backtracks.
    private var failure: IntArray? = null

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        failure ?: OptPresence.withPresencePremises(presents, state, collectHoleAndBoundAntecedents(state, intVars))

    private class Lits {
        private val seen = IntHashSet()
        private val out = IntArrayList()

        fun add(lit: Int) {
            if (lit != Lit.NONE && seen.add(lit)) out.add(lit)
        }

        fun addAll(lits: IntArray?) {
            lits?.forEach { add(it) }
        }

        fun toArray(): IntArray = out.toIntArray()
    }

    private fun bound(state: PropagationState, v: Int, lower: Boolean, need: Long): Int =
        state.boundLiteral(v, lower, need, state.undo.size, state.currentLevel)

    private fun reasonOrNull(state: PropagationState, lits: Lits): IntArray? =
        if (state.currentLevel == 0) null else lits.toArray()

    // Fail with [ant] plus [v]'s own domain, the bound a tightening of [v] ran into.
    private fun failOn(state: PropagationState, ant: IntArray?, v: Int): Boolean {
        failure = Lits().apply {
            addAll(ant)
            addAll(collectHoleAndBoundAntecedents(state, intArrayOf(v)))
        }.toArray()
        return false
    }

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        // The optional-presence variant keeps the order-insensitive greedy bounds: a presence flip
        // changes the count without an int-domain event, so the stronger domain-driven filtering
        // (which assumes every counted variable is present) does not apply cleanly.
        if (presents.isNotEmpty()) return propagateGreedy(state)

        failure = null
        if (state.cpGateShouldSkip(factorId)) return true

        // atLeast / eq: the distinct count cannot exceed the maximum number of variables that can be
        // assigned pairwise-distinct values — a maximum bipartite var↦value matching. Tighter than
        // |union of domains|, which ignores that there are only `xs.size` variables. When the count is
        // pinned to that maximum, a maximum matching is mandatory, so matching-support pruning applies.
        if (mode != NValue.Mode.AtMost) {
            // The matching build enumerates each variable's domain. On a domain too large to walk fall
            // back to the trivial sound upper bound (distinct values ≤ number of variables) and skip the
            // GAC value pruning, which needs the matching.
            if (xs.all { state.intDomains[it].spanOrNull(DEFAULT_DOMAIN_WALK_CAP) != null }) {
                val matching = buildMatching(state)
                // No matching is larger: the variables alternating paths reach from unmatched ones are
                // confined to the values they reach (König's cover), a Hall set.
                val cover = if (state.currentLevel == 0) {
                    null
                } else {
                    hallReason(state, kingCoverVars(matching), EmptyIntArray)
                }
                if (matching.size < state.intDomains[n].max &&
                    !state.tightenIntMax(n, matching.size.toLong(), cover)
                ) {
                    return failOn(state, cover, n)
                }
                if (state.intDomains[n].min == matching.size.toLong()) {
                    if (!atLeastGacPrune(state, matching, cover)) return false
                }
            } else if (xs.size < state.intDomains[n].max && !state.tightenIntMax(n, xs.size.toLong(), IntArray(0))) {
                return failOn(state, IntArray(0), n)
            }
        }
        // atMost / eq: O(n+d) bound-consistency — the count is at least the size of a
        // maximal set of pairwise-disjoint value windows, and when that lower bound meets `n`'s upper
        // bound every variable is forced into the window of its kernel representative.
        if (mode != NValue.Mode.AtLeast) {
            if (!kernelBoundConsistency(state)) return false
        }
        return true
    }

    // The variables reachable over alternating paths (unmatched edges var→value, matched value→var) from the
    // unmatched variables.
    private fun kingCoverVars(m: Matching): IntArray {
        val nv = xs.size
        val seenVar = BooleanArray(nv)
        val seenVal = BooleanArray(m.valToVar.size)
        val stack = IntArrayList()
        for (i in 0 until nv) {
            if (m.varToVal[i] == -1) {
                seenVar[i] = true
                stack.add(i)
            }
        }
        while (!stack.isEmpty()) {
            val i = stack[stack.size - 1]
            stack.removeAt(stack.size - 1)
            val row = m.adj[i]
            for (k in 0 until row.size) {
                val v = row[k]
                if (seenVal[v]) continue
                seenVal[v] = true
                val j = m.valToVar[v]
                if (j >= 0 && !seenVar[j]) {
                    seenVar[j] = true
                    stack.add(j)
                }
            }
        }
        return (0 until nv).filter { seenVar[it] }.map { xs[it] }.toIntArray()
    }

    /** Order-insensitive greedy bounds, the fallback for the optional-presence variant. */
    private fun propagateGreedy(state: PropagationState): Boolean {
        // The union / disjoint-window scans below enumerate each variable's domain. On a domain too large
        // to walk use a bounds-only bound instead: the distinct count is at most the number of possibly-
        // present variables (sound). No cheap sound lower bound here, so leave the minimum untightened
        // (sound, just weaker) — the AtMost mode, which only tightens the minimum, is a no-op.
        val nonAbsent = xs.indices.filter { !definitelyAbsentNvFn(it, state) }
        if (nonAbsent.any { state.intDomains[xs[it]].spanOrNull(DEFAULT_DOMAIN_WALK_CAP) == null }) {
            val boundsAnt = OptPresence.withPresencePremises(presents, state, collectHoleAndBoundAntecedents(state, xs))
            return when (mode) {
                NValue.Mode.Eq, NValue.Mode.AtLeast -> state.tightenIntMax(n, nonAbsent.size.toLong(), boundsAnt)
                NValue.Mode.AtMost -> true
            }
        }
        val unionValues = LongHashSet()
        for (i in xs.indices) {
            if (definitelyAbsentNvFn(i, state)) continue
            state.intDomains[xs[i]].values.forEach { unionValues.add(it) }
        }
        val maxDistinct = unionValues.size
        val present = IntArrayList(xs.size)
        for (i in xs.indices) if (definitelyPresentNvFn(i, state)) present.add(xs[i])
        present.sortByIntKey { state.intDomains[it].values.size }
        val covered = LongHashSet()
        var minDistinct = 0
        for (idx in 0 until present.size) {
            val d = state.intDomains[present[idx]]
            var disjoint = true
            d.values.forEach { if (covered.contains(it)) disjoint = false }
            if (disjoint) {
                minDistinct++
                d.values.forEach { covered.add(it) }
            }
        }
        val ant = OptPresence.withPresencePremises(presents, state, collectHoleAndBoundAntecedents(state, xs))
        when (mode) {
            NValue.Mode.Eq -> {
                if (!state.tightenIntMin(n, minDistinct.toLong(), ant)) return false
                if (!state.tightenIntMax(n, maxDistinct.toLong(), ant)) return false
            }

            NValue.Mode.AtLeast -> {
                if (!state.tightenIntMax(n, maxDistinct.toLong(), ant)) return false
            }

            NValue.Mode.AtMost -> {
                if (!state.tightenIntMin(n, minDistinct.toLong(), ant)) return false
            }
        }
        return true
    }

    /** A maximum bipartite matching between `xs` and their domain values, grown one augmenting path
     *  per variable. */
    private class Matching(
        val varToVal: IntArray,
        val valToVar: IntArray,
        val values: LongArray,
        val adj: Array<IntArrayList>,
        val size: Int,
    )

    private fun buildMatching(state: PropagationState): Matching {
        val valueId = MutableLongIntMap()
        val values = LongArrayList()
        val adj = Array(xs.size) { i ->
            val ids = IntArrayList()
            state.intDomains[xs[i]].values.forEach { v ->
                var id = valueId.getOrDefault(v, -1)
                if (id < 0) {
                    id = values.size
                    values.add(v)
                    valueId.put(v, id)
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
        return Matching(varToVal, valToVar, values.toLongArray(), adj, matched)
    }

    /**
     * Matching-support value pruning for atLeast/eq once the count is pinned to the maximum matching size: a
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
        // Every maximum matching is used, as the count's lower bound demands as many values as one covers; an
        // edge left in no maximum matching stays out because nothing reachable from it leads back, which only
        // the domains of what it reaches can change.
        val bySource = HashMap<Int, IntArray?>()
        fun reasonFrom(node: Int): IntArray? = bySource.getOrPut(scc[node]) {
            if (state.currentLevel == 0) return@getOrPut null
            Lits().apply {
                addAll(ant)
                add(bound(state, n, true, m.size.toLong()))
                val reached = BooleanArray(total)
                val stack = IntArrayList()
                reached[node] = true
                stack.add(node)
                while (!stack.isEmpty()) {
                    val u = stack[stack.size - 1]
                    stack.removeAt(stack.size - 1)
                    val a = adj[u]
                    for (k in 0 until a.size) {
                        if (!reached[a[k]]) {
                            reached[a[k]] = true
                            stack.add(a[k])
                        }
                    }
                }
                for (j in 0 until nv) if (reached[j]) addAll(collectHoleAndBoundAntecedents(state, intArrayOf(xs[j])))
            }.toArray()
        }
        for (i in 0 until nv) {
            val vIds = m.adj[i].toIntArray()
            for (vId in vIds) {
                if (scc[i] == scc[nv + vId]) continue
                val value = m.values[vId]
                val why = reasonFrom(if (m.varToVal[i] == vId) i else nv + vId)
                if (m.varToVal[i] == vId) {
                    if (!state.setInt(xs[i], value, why)) return failOn(state, why, xs[i])
                } else {
                    if (!state.excludeIntValue(xs[i], value, why)) return failOn(state, why, xs[i])
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
     * Bound-consistency for atMost (Beldiceanu, "Filtering Algorithms for the NValue Constraint")
     * over kernels of disjoint bound windows: a kernel is a maximal set
     * of pairwise-disjoint `[lb, ub]` windows; its size lower-bounds the distinct count, and when it
     * equals `n`'s upper bound each variable is squeezed into the window of its kernel representative.
     * Bounds-only, so interior holes are ignored (sound — the interval over-approximates the domain).
     * Each `while` iteration takes one snapshot of the bounds and runs both a lower-bound pass and an
     * upper-bound pass off it, looping until neither pass narrows anything.
     */
    private fun kernelBoundConsistency(state: PropagationState): Boolean {
        val nv = xs.size
        val minVal = LongArray(nv)
        val maxVal = LongArray(nv)
        val order = IntArray(nv)
        var loop = true
        while (loop) {
            loop = false
            for (i in 0 until nv) {
                minVal[i] = state.intDomains[xs[i]].min
                maxVal[i] = state.intDomains[xs[i]].max
            }
            val rLb = kernelPass(state, nv, minVal, maxVal, order, lowerPass = true)
            if (rLb < 0) return false
            if (rLb > 0) loop = true
            val rUb = kernelPass(state, nv, minVal, maxVal, order, lowerPass = false)
            if (rUb < 0) return false
            if (rUb > 0) loop = true
        }
        return true
    }

    /** One kernel pass. Returns -1 on conflict, 1 if it narrowed a domain, 0 otherwise. */
    @Suppress("LongParameterList", "ReturnCount")
    private fun kernelPass(
        state: PropagationState,
        nv: Int,
        minVal: LongArray,
        maxVal: LongArray,
        order: IntArray,
        lowerPass: Boolean,
    ): Int {
        // Scan variables in value order (lower bound ascending, or upper bound descending), ties by
        // index descending, so the kernel grouping is deterministic across fires. A window closes
        // whenever the next variable cannot overlap the running one. The
        // sort key `primary` is `minVal` (or `-maxVal` to sort descending) and ties break by index
        // descending; sorting an index permutation by the full `Long` key (rather than packing key and
        // index into one word) keeps wide bounds intact.
        val primary = LongArray(nv) { if (lowerPass) minVal[it] else -maxVal[it] }
        val sorted = (0 until nv).sortedWith(compareBy<Int> { primary[it] }.thenByDescending { it })
        for (idx in 0 until nv) order[idx] = sorted[idx]
        val kerRep = BooleanArray(nv)
        // Per group: the member whose bound closes its window (its least upper bound in the lower pass), and
        // the window's far edge. These members' ranges are pairwise disjoint.
        val closer = IntArrayList()
        val edge = LongArrayList()
        var min = Long.MIN_VALUE
        var max = Long.MIN_VALUE
        var nbKer = 0
        for (idx in 0 until nv) {
            val node = order[idx]
            if (min == Long.MIN_VALUE) {
                min = minVal[node]
                max = maxVal[node]
                nbKer++
                closer.add(node)
            } else if (overlaps(lowerPass, minVal[node], maxVal[node], min, max)) {
                min = maxOf(min, minVal[node])
                max = minOf(max, maxVal[node])
                val current = closer[closer.size - 1]
                val tighter = if (lowerPass) maxVal[node] < maxVal[current] else minVal[node] > minVal[current]
                if (tighter) closer[closer.size - 1] = node
            } else {
                edge.add(if (lowerPass) max else min)
                min = minVal[node]
                max = maxVal[node]
                kerRep[node] = true
                nbKer++
                closer.add(node)
            }
        }
        // The kernel: each group's closing member stays on its side of the edge between it and the next.
        val kernel = Lits()
        for (g in 0 until closer.size) {
            val x = xs[closer[g]]
            if (lowerPass) {
                if (g < edge.size) kernel.add(bound(state, x, false, edge[g]))
                if (g > 0) kernel.add(bound(state, x, true, edge[g - 1] + 1))
            } else {
                if (g < edge.size) kernel.add(bound(state, x, true, edge[g]))
                if (g > 0) kernel.add(bound(state, x, false, edge[g - 1] - 1))
            }
        }
        val kernelLits = kernel.toArray()
        var status = 0
        if (state.intDomains[n].min < nbKer) {
            val ant = if (state.currentLevel == 0) null else kernelLits
            if (!state.tightenIntMin(n, nbKer.toLong(), ant)) return if (failOn(state, ant, n)) 0 else -1
            status = 1
        }
        // When the kernel count is forced to equal n's max, no variable may stray outside its
        // window's value range, so squeeze each group.
        if (state.intDomains[n].max == nbKer.toLong()) {
            // As many groups as values allowed: each variable takes its group's closing member's value.
            val base = Lits().apply {
                addAll(kernelLits)
                add(bound(state, n, false, nbKer.toLong()))
            }.toArray()
            val stamp = IntArrayList()
            var group = 0
            for (idx in 0 until nv) {
                val node = order[idx]
                if (kerRep[node]) {
                    val frontier = if (lowerPass) minVal[node] else maxVal[node]
                    val s = squeeze(state, base, stamp, frontier, lowerPass, if (group > 0) edge[group - 1] else null)
                    if (s < 0) return -1
                    if (s > 0) status = 1
                    stamp.clear()
                    group++
                }
                stamp.add(node)
            }
            val lastFrontier = if (lowerPass) Long.MAX_VALUE else Long.MIN_VALUE
            val s = squeeze(state, base, stamp, lastFrontier, lowerPass, if (group > 0) edge[group - 1] else null)
            if (s < 0) return -1
            if (s > 0) status = 1
        }
        return status
    }

    private fun overlaps(lowerPass: Boolean, nodeMin: Long, nodeMax: Long, min: Long, max: Long): Boolean =
        if (lowerPass) nodeMin <= max else nodeMax >= min

    /**
     * The variables in [stamp] form one kernel group; any member that cannot reach the next group's
     * [frontier] value is clamped to the group's tightest shared bound. Returns -1 on conflict, 1 if
     * it narrowed a domain, 0 otherwise.
     */
    @Suppress("LongParameterList")
    private fun squeeze(
        state: PropagationState,
        base: IntArray,
        stamp: IntArrayList,
        frontier: Long,
        lowerPass: Boolean,
        previousEdge: Long?,
    ): Int {
        var status = 0
        // A member that cannot reach the next window, nor the previous one, shares its group's value: with the
        // member that set the squeeze, both confined to the group the same way.
        fun confined(vid: Int, lits: Lits) {
            if (lowerPass) {
                lits.add(bound(state, vid, false, frontier - 1))
                if (previousEdge != null) lits.add(bound(state, vid, true, previousEdge + 1))
            } else {
                lits.add(bound(state, vid, true, frontier + 1))
                if (previousEdge != null) lits.add(bound(state, vid, false, previousEdge - 1))
            }
        }
        if (lowerPass) {
            var newMin = Long.MIN_VALUE
            var setter = -1
            for (i in 0 until stamp.size) {
                val vid = xs[stamp[i]]
                if (state.intDomains[vid].max < frontier && state.intDomains[vid].min > newMin) {
                    newMin = state.intDomains[vid].min
                    setter = vid
                }
            }
            if (setter < 0) return 0
            for (i in 0 until stamp.size) {
                val vid = xs[stamp[i]]
                if (state.intDomains[vid].max < frontier && state.intDomains[vid].min < newMin) {
                    val ant = if (state.currentLevel == 0) {
                        null
                    } else {
                        Lits().apply {
                            addAll(base)
                            confined(vid, this)
                            confined(setter, this)
                            add(bound(state, setter, true, newMin))
                        }.toArray()
                    }
                    if (!state.tightenIntMin(vid, newMin, ant)) return if (failOn(state, ant, vid)) 0 else -1
                    status = 1
                }
            }
        } else {
            var newMax = Long.MAX_VALUE
            var setter = -1
            for (i in 0 until stamp.size) {
                val vid = xs[stamp[i]]
                if (state.intDomains[vid].min > frontier && state.intDomains[vid].max < newMax) {
                    newMax = state.intDomains[vid].max
                    setter = vid
                }
            }
            if (setter < 0) return 0
            for (i in 0 until stamp.size) {
                val vid = xs[stamp[i]]
                if (state.intDomains[vid].min > frontier && state.intDomains[vid].max > newMax) {
                    val ant = if (state.currentLevel == 0) {
                        null
                    } else {
                        Lits().apply {
                            addAll(base)
                            confined(vid, this)
                            confined(setter, this)
                            add(bound(state, setter, false, newMax))
                        }.toArray()
                    }
                    if (!state.tightenIntMax(vid, newMax, ant)) return if (failOn(state, ant, vid)) 0 else -1
                    status = 1
                }
            }
        }
        return status
    }
}
