package com.eignex.klause.solver.factor

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.solver.propagation.PropagationState
import com.eignex.klause.util.IntArrayList

/**
 * Global Cardinality Constraint (GCC). Covers the four MiniZinc variants in one factor:
 *
 *  - `global_cardinality(xs, cover, counts)` — `counts[k] = #{i : xs[i] = cover[k]}`. Use
 *    [countVars] (`size = cover.size`) and [closed] = `false`.
 *  - `global_cardinality_low_up(xs, cover, lo, up)` — `lo[k] ≤ #{i : xs[i] = cover[k]} ≤ up[k]`.
 *    Use [countLow] / [countHigh] (constant arrays) and [countVars] = `null`.
 *  - `_closed` variants additionally require every `xs[i] ∈ cover` — i.e. no value outside
 *    the cover set may appear. Pass [closed] = `true`.
 *
 * Exactly one of ([countVars], [countLow]+[countHigh]) is non-null — the constructor
 * validates.
 *
 * Propagation: count-bound tightening (definite/possible matchers per cover value) plus
 * Régin-style max-flow GAC. The flow has lower bounds on `cover_k → sink` (matching the
 * cover lo/hi or current `countVars[k]` domain), is reduced to standard max-flow via the
 * super-source/super-sink trick, solved by Edmonds-Karp, then the residual graph is
 * SCC'd. Any `xᵢ → cover_k` edge with zero flow whose endpoints sit in different SCCs
 * cannot extend to a feasible solution and is pruned from `dom(xᵢ)`.
 */
class GlobalCardinality(
    /** Variable ids the constraint ranges over. */
    val xs: IntArray,
    /** Values whose occurrence counts are bounded. */
    val cover: IntArray,
    val countVars: IntArray? = null,
    val countLow: IntArray? = null,
    val countHigh: IntArray? = null,
    val closed: Boolean = false,
    /** Per-xs presence literals; empty for the non-opt fast path. Absent positions
     *  contribute nothing to any cover-value count and don't trip the closed check. */
    val presents: IntArray = EmptyIntArray,
) : Factor {

    init {
        require(xs.isNotEmpty()) { "gcc: empty xs" }
        require(cover.isNotEmpty()) { "gcc: empty cover" }
        if (countVars != null) {
            require(countVars.size == cover.size) { "gcc: countVars size mismatch" }
            require(countLow == null && countHigh == null) { "gcc: pass either countVars OR countLow+countHigh" }
        } else {
            require(countLow != null && countHigh != null) { "gcc: missing countLow/countHigh" }
            require(countLow.size == cover.size && countHigh.size == cover.size) { "gcc: lo/hi size mismatch" }
        }
        require(presents.isEmpty() || presents.size == xs.size) {
            "gcc: presents must be empty or match xs arity"
        }
    }

    override val boolVars: IntArray = OptPresence.presenceVarIds(presents)

    private fun present(state: LocalSearchState, idx: Int): Boolean =
        OptPresence.isPresentInAssignment(presents, idx, state)
    override val intVars: IntArray = run {
        val cv = countVars
        if (cv != null) xs + cv else xs
    }

    private val coverIndexByValue: HashMap<Int, Int> = run {
        val m = HashMap<Int, Int>(cover.size * 2)
        for (i in cover.indices) m[cover[i]] = i
        m
    }

    /** Per-cover-index count under the current assignment. */
    private class State(val counts: IntArray)

    override fun initialize(state: LocalSearchState, factorId: Int) {
        val counts = IntArray(cover.size)
        for (i in xs.indices) {
            if (!present(state, i)) continue
            val value = state.assignment.intValue(xs[i])
            val idx = coverIndexByValue[value] ?: continue // out-of-cover; counts unaffected
            counts[idx]++
        }
        state.refPayload[factorId] = State(counts)
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean {
        val s = state.refPayload[factorId] as State
        return rawDegree(state, s.counts, ovVar = -1, ovVal = 0) > 0L
    }

    /** Graded violation: per-cover count error (`|count − countVar|`, or the bound shortfall
     *  `max(0, lo − count) + max(0, count − hi)`) summed over the cover, plus — for the closed
     *  variant — one unit per present `xs[i]` whose value falls outside the cover. Compressed so
     *  a wide cover can't dominate the global cost. */
    override fun violationDegree(state: LocalSearchState, factorId: Int): Int {
        val s = state.refPayload[factorId] as State
        return compressViolation(rawDegree(state, s.counts, ovVar = -1, ovVal = 0), state.violationSoftCap)
    }

    /** Per-cover count-error term over [simCounts]; `(ovVar, ovVal)` overrides one count-var's
     *  value when reading the target. */
    private fun countsDegree(state: LocalSearchState, simCounts: IntArray, ovVar: Int, ovVal: Int): Long {
        var deg = 0L
        for (k in cover.indices) {
            if (countVars != null) {
                val expected = if (countVars[k] == ovVar) ovVal else state.assignment.intValue(countVars[k])
                val d = expected.toLong() - simCounts[k]
                deg += if (d < 0) -d else d
            } else {
                val cnt = simCounts[k]
                val lo = requireNotNull(countLow)[k]
                val hi = requireNotNull(countHigh)[k]
                if (cnt < lo) {
                    deg += (lo - cnt).toLong()
                } else if (cnt > hi) {
                    deg += (cnt - hi).toLong()
                }
            }
        }
        return deg
    }

    /** Closed-variant term: present `xs[i]` whose value is outside the cover. `(ovVar, ovVal)`
     *  overrides one xs value; `flipVar >= 0` inverts the presence of positions controlled by
     *  that presence-var (used to evaluate a candidate bool flip). */
    private fun closedDegree(state: LocalSearchState, ovVar: Int, ovVal: Int, flipVar: Int = -1): Long {
        if (!closed) return 0L
        var deg = 0L
        for (i in xs.indices) {
            val controlled = flipVar >= 0 && presents.isNotEmpty() && Lit.variable(presents[i]) == flipVar
            val p = if (controlled) !present(state, i) else present(state, i)
            if (!p) continue
            val v = if (xs[i] == ovVar) ovVal else state.assignment.intValue(xs[i])
            if (v !in coverIndexByValue) deg++
        }
        return deg
    }

    private fun rawDegree(state: LocalSearchState, simCounts: IntArray, ovVar: Int, ovVal: Int): Long =
        countsDegree(state, simCounts, ovVar, ovVal) + closedDegree(state, ovVar, ovVal)

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int {
        val s = state.refPayload[factorId] as State
        val before = rawDegree(state, s.counts, ovVar = -1, ovVal = 0)
        val sim = s.counts.copyOf()
        var occurrencesInXs = 0
        for (i in xs.indices) if (xs[i] == intVar && present(state, i)) occurrencesInXs++
        if (occurrencesInXs > 0) {
            val old = state.assignment.intValue(intVar)
            coverIndexByValue[old]?.let { sim[it] -= occurrencesInXs }
            coverIndexByValue[newValue]?.let { sim[it] += occurrencesInXs }
        }
        val after = rawDegree(state, sim, ovVar = intVar, ovVal = newValue)
        return compressViolation(after, state.violationSoftCap) - compressViolation(before, state.violationSoftCap)
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int {
        val s = state.refPayload[factorId] as State
        val cur = state.assignment.intValue(intVar)
        if (cur == oldValue) return 0
        var occurrencesInXs = 0
        for (i in xs.indices) if (xs[i] == intVar && present(state, i)) occurrencesInXs++
        // Pre-update degree: reconstruct the prior counts by inverting this move, and read the
        // prior value (oldValue) for the count-var / closed overrides.
        val simInv = s.counts.copyOf()
        if (occurrencesInXs > 0) {
            coverIndexByValue[cur]?.let { simInv[it] -= occurrencesInXs }
            coverIndexByValue[oldValue]?.let { simInv[it] += occurrencesInXs }
        }
        val beforeDeg = rawDegree(state, simInv, ovVar = intVar, ovVal = oldValue)
        if (occurrencesInXs > 0) {
            coverIndexByValue[oldValue]?.let { s.counts[it] -= occurrencesInXs }
            coverIndexByValue[cur]?.let { s.counts[it] += occurrencesInXs }
        }
        val afterDeg = rawDegree(state, s.counts, ovVar = -1, ovVal = 0)
        return compressViolation(afterDeg, state.violationSoftCap) -
            compressViolation(beforeDeg, state.violationSoftCap)
    }

    override fun deltaIfBoolFlipped(state: LocalSearchState, factorId: Int, boolVar: Int): Int {
        if (presents.isEmpty()) return 0
        val s = state.refPayload[factorId] as State
        val before = rawDegree(state, s.counts, ovVar = -1, ovVal = 0)
        val sim = s.counts.copyOf()
        for (i in presents.indices) {
            if (Lit.variable(presents[i]) != boolVar) continue
            val wasP = present(state, i)
            val coverIdx = coverIndexByValue[state.assignment.intValue(xs[i])] ?: continue
            sim[coverIdx] += if (wasP) -1 else +1
        }
        // Counts term uses the simulated counts; closed term re-evaluates with the flip applied.
        val after = countsDegree(state, sim, ovVar = -1, ovVal = 0) +
            closedDegree(state, ovVar = -1, ovVal = 0, flipVar = boolVar)
        return compressViolation(after, state.violationSoftCap) - compressViolation(before, state.violationSoftCap)
    }

    override fun applyBoolFlip(state: LocalSearchState, factorId: Int, boolVar: Int): Int {
        if (presents.isEmpty()) return 0
        val s = state.refPayload[factorId] as State
        // Flip is already applied to the assignment; reconstruct the pre-flip degree by inverting
        // the presence of boolVar's positions for the closed term, against the un-mutated counts.
        val beforeDeg = countsDegree(state, s.counts, ovVar = -1, ovVal = 0) +
            closedDegree(state, ovVar = -1, ovVal = 0, flipVar = boolVar)
        for (i in presents.indices) {
            if (Lit.variable(presents[i]) != boolVar) continue
            val nowP = present(state, i)
            val coverIdx = coverIndexByValue[state.assignment.intValue(xs[i])] ?: continue
            s.counts[coverIdx] += if (nowP) +1 else -1
        }
        val afterDeg = rawDegree(state, s.counts, ovVar = -1, ovVal = 0)
        return compressViolation(afterDeg, state.violationSoftCap) -
            compressViolation(beforeDeg, state.violationSoftCap)
    }

    /** Vars currently pinned (singleton) to [value], among [scope]. */
    private fun pinnedTo(state: PropagationState, scope: IntArray, value: Int): IntArrayList {
        val out = IntArrayList()
        for (x in scope) {
            val d = state.intDomains[x]
            if (d.min == d.max && d.min == value) out.add(x)
        }
        return out
    }

    /** Clause-form literals for every pinned presence: the conclusion rests on which xs are
     *  in and which are out, so each pinned presence literal joins the premise set as its
     *  currently-false form. Empty for the non-opt fast path. */
    private fun presencePremiseLits(state: PropagationState): IntArray {
        if (presents.isEmpty()) return EmptyIntArray
        val out = IntArrayList()
        for (i in presents.indices) {
            when {
                OptPresence.isDefinitelyPresent(presents, i, state) -> out.add(Lit.negate(presents[i]))
                OptPresence.isDefinitelyAbsent(presents, i, state) -> out.add(presents[i])
            }
        }
        return out.toIntArray()
    }

    private fun withPresencePremises(state: PropagationState, base: IntArray?): IntArray? {
        val pres = presencePremiseLits(state)
        if (pres.isEmpty()) return base
        if (base == null) return pres
        val out = IntArray(base.size + pres.size)
        base.copyInto(out)
        pres.copyInto(out, base.size)
        return out
    }

    /** Hole-aware conflict reason, sharpened to the pigeonhole subset captured by [propagate];
     *  falls back to all vars when no subset was recorded. Presence pins join the premises:
     *  without them a conflict derived among the present xs reads as if it held for every
     *  assignment, and the learned clause prunes feasible solutions that simply mark some
     *  of those xs absent. */
    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? = withPresencePremises(
        state,
        collectHoleAndBoundAntecedents(state, (state.refPayload[factorId] as? IntArray) ?: intVars),
    )

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        state.refPayload[factorId] = null // stale-guard; set at each pigeonhole failure point below.
        // ---- 1. Count tightening + closure --------------------------------------------
        // Opt-aware: filter to definitely-present xs for the flow analysis. Definitely-
        // absent xs contribute nothing; unpinned-presence xs may still go absent, so we
        // can't require them to take a cover value — skip them too for soundness.
        val origIdx: IntArray = if (presents.isEmpty()) {
            IntArray(xs.size) { it }
        } else {
            val acc = IntArrayList()
            for (i in xs.indices) if (OptPresence.isDefinitelyPresent(presents, i, state)) acc.add(i)
            IntArray(acc.size) { acc[it] }
        }
        val effectiveXs: IntArray = if (presents.isEmpty()) {
            xs
        } else {
            IntArray(origIdx.size) { xs[origIdx[it]] }
        }
        val n = effectiveXs.size
        val m = cover.size
        val coverSet = coverIndexByValue.keys
        // Maybe-present xs: presence undecided. They contribute no definite count and take
        // no pruning, but they remain potential takers of any cover value, so every
        // upper-bound argument must include them.
        val maybeXs: IntArray = if (presents.isEmpty()) {
            EmptyIntArray
        } else {
            val acc = IntArrayList()
            for (i in xs.indices) {
                if (!OptPresence.isDefinitelyPresent(presents, i, state) &&
                    !OptPresence.isDefinitelyAbsent(presents, i, state)
                ) {
                    acc.add(xs[i])
                }
            }
            acc.toIntArray()
        }
        // LCG antecedents: the union of every involved var's int trail plus the presence
        // pins. Count vars join the set — every flow-shaped conclusion rests on their
        // current bounds (the demand side), and composing emits nothing for a count var
        // still at its root domain. Same coarse approach as AllDifferent — analyzer
        // minimization shrinks redundant pieces.
        val gccAntecedents = withPresencePremises(
            state,
            state.composeIntVarAtomAntecedents(effectiveXs + maybeXs + (countVars ?: EmptyIntArray)),
        )
        if (closed) {
            for (x in effectiveXs) {
                val d = state.intDomains[x]
                val toRemove = IntArrayList()
                d.forEach { if (it !in coverSet) toRemove.add(it) }
                for (k in 0 until toRemove.size) {
                    if (!state.excludeIntValue(
                            x,
                            toRemove[k],
                            gccAntecedents,
                        )
                    ) {
                        return false
                    }
                }
            }
        }
        val definite = IntArray(m)
        val possible = IntArray(m)
        for (k in cover.indices) {
            val target = cover[k]
            for (x in effectiveXs) {
                val d = state.intDomains[x]
                if (d.min == d.max && d.min == target) definite[k]++
                if (target in d) possible[k]++
            }
            // A maybe-present x can still become present and take the value: it counts
            // toward the possible takers (never toward the definite ones).
            for (x in maybeXs) {
                if (target in state.intDomains[x]) possible[k]++
            }
            if (countVars != null) {
                // More vars pinned to cover[k] than countVars[k]'s max: the pinned vars plus
                // the count var alone prove it (a pigeonhole over cover[k]).
                if (!state.tightenIntMin(countVars[k], definite[k], gccAntecedents)) {
                    state.refPayload[factorId] = pinnedTo(
                        state,
                        effectiveXs,
                        target,
                    ).also { it.add(countVars[k]) }.toIntArray()
                    return false
                }
                if (!state.tightenIntMax(countVars[k], possible[k], gccAntecedents)) return false
            } else {
                if (requireNotNull(countLow)[k] > possible[k]) return false
                // More vars pinned to cover[k] than countHigh[k] allows: cite only those pins.
                if (requireNotNull(countHigh)[k] < definite[k]) {
                    state.refPayload[factorId] = pinnedTo(state, effectiveXs, target).toIntArray()
                    return false
                }
            }
        }

        // Resolve effective [lo_k, hi_k] for cover values (post count-tightening).
        val lo = IntArray(m)
        val hi = IntArray(m)
        for (k in 0 until m) {
            if (countVars != null) {
                val cd = state.intDomains[countVars[k]]
                lo[k] = cd.min
                hi[k] = cd.max
            } else {
                lo[k] = requireNotNull(countLow)[k]
                hi[k] = requireNotNull(countHigh)[k]
            }
        }

        // The flow model below assumes the present set is exact: a maybe-present x is
        // potential supply for any lower bound and potential demand on any upper bound,
        // and neither role fits a fixed-node flow soundly. Defer GAC until every
        // presence is decided; the count tightening above already ran.
        if (maybeXs.isNotEmpty()) return true

        // Detect whether any xᵢ has an out-of-cover value reachable (drives "other" arc).
        val hasOtherVar = BooleanArray(n)
        val anyOther = !closed && run {
            var any = false
            for (i in 0 until n) {
                val d = state.intDomains[effectiveXs[i]]
                var found = false
                d.forEach { if (!found && it !in coverSet) found = true }
                hasOtherVar[i] = found
                if (found) any = true
            }
            any
        }

        // ---- 2. Régin GAC via max-flow -----------------------------------------------
        // Node layout: 0 = source, 1 = sink, 2..2+n-1 = var nodes, 2+n..2+n+m-1 = cover nodes,
        // (optional) 2+n+m = other node. Plus superSource, superSink appended for lower-bound reduction.
        val source = 0
        val sink = 1
        val varNode = IntArray(n) { 2 + it }
        val covNode = IntArray(m) { 2 + n + it }
        val otherNode = if (anyOther) 2 + n + m else -1
        val baseNodes = 2 + n + m + (if (anyOther) 1 else 0)
        val superSource = baseNodes
        val superSink = baseNodes + 1
        val totalNodes = baseNodes + 2

        // Edge list: parallel arrays (to, cap, rev). `headForward[i]` = first forward
        // edge index in `edgeTo` for node i; we just keep flat lists per node.
        val flow = FlowBuilder(totalNodes)

        // source → x_i with bounds [1, 1]: reduces to cap 0, excess[source] -= 1, excess[x_i] += 1.
        // Encoded by accumulating into `excess` and adding zero-cap edge.
        val excess = IntArray(totalNodes)
        // Track xᵢ → cover_k edge indices so we can read out flow + check residual later.
        val xToCovEdgeIdx = Array(n) { IntArray(m) { -1 } }
        val xToOtherEdgeIdx = IntArray(n) { -1 }

        for (i in 0 until n) {
            // source → x_i lower bound 1, upper 1.
            excess[source] -= 1
            excess[varNode[i]] += 1
            // No residual capacity on this edge (l == h).
            flow.addEdge(source, varNode[i], 0)
        }

        for (i in 0 until n) {
            val d = state.intDomains[effectiveXs[i]]
            for (k in 0 until m) {
                if (cover[k] in d) {
                    val eIdx = flow.addEdge(varNode[i], covNode[k], 1) // [0, 1]
                    xToCovEdgeIdx[i][k] = eIdx
                }
            }
            if (otherNode != -1 && hasOtherVar[i]) {
                xToOtherEdgeIdx[i] = flow.addEdge(varNode[i], otherNode, 1) // [0, 1]
            }
        }

        for (k in 0 until m) {
            if (lo[k] > hi[k]) return false
            // cover_k → sink with bounds [lo_k, hi_k]
            excess[covNode[k]] -= lo[k]
            excess[sink] += lo[k]
            flow.addEdge(covNode[k], sink, hi[k] - lo[k])
        }
        if (otherNode != -1) {
            // other → sink with bounds [0, n]; no excess shift.
            flow.addEdge(otherNode, sink, n)
        }
        // sink → source back-edge to convert s-t feasibility into a circulation: bounds [n, n].
        excess[sink] -= n
        excess[source] += n
        flow.addEdge(sink, source, 0)

        // superSource / superSink excess-balancing edges.
        var requiredSSFlow = 0
        for (v in 0 until baseNodes) {
            when {
                excess[v] > 0 -> {
                    flow.addEdge(superSource, v, excess[v])
                    requiredSSFlow += excess[v]
                }

                excess[v] < 0 -> flow.addEdge(v, superSink, -excess[v])
            }
        }

        // Edmonds-Karp max-flow from superSource to superSink. If the saturation of all ss-out edges
        // is less than requiredSSFlow → infeasible.
        val obtained = flow.maxFlow(superSource, superSink)
        if (obtained < requiredSSFlow) {
            // Flow-deficiency conflict: the min cut's source side (nodes still reachable from
            // superSource in the residual) carries the unroutable demand. Cite only the vars
            // (and any count vars) on that side — a generalized-Hall subset — rather than all.
            // Vars off the source side route fine; their domains play no part in the deficit.
            val reach = flow.residualReachable(superSource)
            val resp = IntArrayList()
            for (i in 0 until n) if (reach[varNode[i]]) resp.add(effectiveXs[i])
            // Every count var joins the citation: the deficit is supply (the vars above)
            // against demand, and the demand is the count vars' search-derived bounds —
            // the demand-side cover nodes are generally NOT residual-reachable, so
            // filtering by the cut would drop exactly the premises that matter. A count
            // var still at its root domain contributes no literal.
            if (countVars != null) for (k in 0 until m) resp.add(countVars[k])
            if (resp.size > 0) state.refPayload[factorId] = resp.toIntArray()
            return false
        }

        // ---- 3. SCC on residual graph (excluding superSource, superSink) -----------------------------
        val sccId = IntArray(baseNodes) { -1 }
        flow.computeSccResidual(baseNodes, sccId)

        // ---- 4. Prune zero-flow xᵢ→cover_k edges across SCC boundaries ---------------
        for (i in 0 until n) {
            for (k in 0 until m) {
                val eIdx = xToCovEdgeIdx[i][k]
                if (eIdx < 0) continue
                if (flow.flowOf(eIdx) > 0) continue // active in current flow; alive.
                if (sccId[varNode[i]] == sccId[covNode[k]]) continue // may carry flow elsewhere.
                if (!state.excludeIntValue(effectiveXs[i], cover[k], gccAntecedents)) return false
            }
            // If the var→other arc exists but cannot carry flow in any feasible flow,
            // every non-cover value in dom(xᵢ) is dead — prune them all.
            val oIdx = xToOtherEdgeIdx[i]
            if (oIdx >= 0 && flow.flowOf(oIdx) == 0 && sccId[varNode[i]] != sccId[otherNode]) {
                val d = state.intDomains[effectiveXs[i]]
                val toRemove = IntArrayList()
                d.forEach { if (it !in coverSet) toRemove.add(it) }
                for (k in 0 until toRemove.size) {
                    if (!state.excludeIntValue(
                            effectiveXs[i],
                            toRemove[k],
                            gccAntecedents,
                        )
                    ) {
                        return false
                    }
                }
            }
        }
        return true
    }

    /**
     * Minimal Edmonds-Karp max-flow over an integer-capacity graph stored as parallel
     * arrays. Edges come in forward/reverse pairs; even indices are forward edges (with
     * the original capacity), odd indices are residual reverses (initially zero). Flow
     * pushed on edge `e` shows up as `originalCap - cap[e]` for forward edges.
     */
    private class FlowBuilder(val numNodes: Int) {
        private val adj: Array<IntArrayList> = Array(numNodes) { IntArrayList() }
        private val edgeTo = IntArrayList()
        private val cap = IntArrayList()
        private val originalCap = IntArrayList()

        fun addEdge(u: Int, v: Int, c: Int): Int {
            val eIdx = edgeTo.size
            edgeTo.add(v)
            cap.add(c)
            originalCap.add(c)
            adj[u].add(eIdx)
            edgeTo.add(u)
            cap.add(0)
            originalCap.add(0)
            adj[v].add(eIdx + 1)
            return eIdx
        }

        fun flowOf(eIdx: Int): Int = originalCap[eIdx] - cap[eIdx]

        /** Nodes reachable from [source] over residual arcs (positive remaining capacity) —
         *  the source side of the min cut after a (failed) max-flow. */
        fun residualReachable(source: Int): BooleanArray {
            val seen = BooleanArray(numNodes)
            val queue = IntArray(numNodes)
            var qHead = 0
            var qTail = 0
            seen[source] = true
            queue[qTail++] = source
            while (qHead < qTail) {
                val u = queue[qHead++]
                val neigh = adj[u]
                for (i in 0 until neigh.size) {
                    val eIdx = neigh[i]
                    if (cap[eIdx] <= 0) continue
                    val v = edgeTo[eIdx]
                    if (!seen[v]) {
                        seen[v] = true
                        queue[qTail++] = v
                    }
                }
            }
            return seen
        }

        fun maxFlow(source: Int, sink: Int): Int {
            var total = 0
            val parentEdge = IntArray(numNodes)
            val queue = IntArray(numNodes)
            while (true) {
                parentEdge.fill(-1)
                parentEdge[source] = -2
                var qHead = 0
                var qTail = 0
                queue[qTail++] = source
                var found = false
                while (qHead < qTail && !found) {
                    val u = queue[qHead++]
                    val neigh = adj[u]
                    for (k in 0 until neigh.size) {
                        val eIdx = neigh[k]
                        val v = edgeTo[eIdx]
                        if (parentEdge[v] != -1 || cap[eIdx] <= 0) continue
                        parentEdge[v] = eIdx
                        if (v == sink) {
                            found = true
                            break
                        }
                        queue[qTail++] = v
                    }
                }
                if (!found) break
                // Find bottleneck along the path.
                var bottleneck = Int.MAX_VALUE
                var cur = sink
                while (cur != source) {
                    val eIdx = parentEdge[cur]
                    if (cap[eIdx] < bottleneck) bottleneck = cap[eIdx]
                    cur = edgeTo[eIdx xor 1]
                }
                cur = sink
                while (cur != source) {
                    val eIdx = parentEdge[cur]
                    cap[eIdx] = cap[eIdx] - bottleneck
                    cap[eIdx xor 1] = cap[eIdx xor 1] + bottleneck
                    cur = edgeTo[eIdx xor 1]
                }
                total += bottleneck
            }
            return total
        }

        /** Tarjan SCC over the residual subgraph induced by nodes `[0, limit)`. Edges with
         *  positive remaining capacity (forward residuals + already-used reverses) define the
         *  directed graph. Materialises that residual adjacency (a static snapshot once the flow
         *  is fixed) and delegates to the shared [reginTarjanScc] (#99). SCC ids are only ever
         *  compared for equality, so component numbering is irrelevant. */
        fun computeSccResidual(limit: Int, sccId: IntArray) {
            val nodeAdj = Array(limit) { IntArrayList() }
            for (v in 0 until limit) {
                val neigh = adj[v]
                for (k in 0 until neigh.size) {
                    val eIdx = neigh[k]
                    val w = edgeTo[eIdx]
                    if (w < limit && cap[eIdx] > 0) nodeAdj[v].add(w)
                }
            }
            val res = reginTarjanScc(nodeAdj, limit)
            for (i in 0 until limit) sccId[i] = res[i]
        }
    }

    /** Repair moves: for each cover index `k` whose count violates the constraint, push it
     *  toward the target band. With [countVars], also snap each `countVars[k]` to the current
     *  count. For [closed]=true, drag any out-of-cover `xs[i]` into the cover. */
    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        if (!isViolated(state, factorId)) return
        val s = state.refPayload[factorId] as State
        // 1. Snap countVars to current count when they disagree.
        if (countVars != null) {
            for (k in cover.indices) {
                val cv = countVars[k]
                val cur = state.assignment.intValue(cv)
                if (cur != s.counts[k] && s.counts[k] in state.problem.intDomains[cv]) {
                    sink.addChannelingIntSet(state, cv, s.counts[k])
                }
            }
        }
        // 2. For each violating cover index, propose moves to push count toward target.
        for (k in cover.indices) {
            val coverVal = cover[k]
            val cnt = s.counts[k]
            val target: Int
            val needIncrease: Boolean
            if (countVars != null) {
                target = state.assignment.intValue(countVars[k])
                if (cnt == target) continue
                needIncrease = cnt < target
            } else {
                if (cnt in requireNotNull(countLow)[k]..requireNotNull(countHigh)[k]) continue
                needIncrease = cnt < countLow[k]
                target = if (needIncrease) countLow[k] else countHigh[k]
            }
            if (needIncrease) {
                // Pick xs[i] currently NOT at coverVal whose domain contains it; switch.
                for (i in xs.indices) {
                    if (!present(state, i)) continue
                    val cur = state.assignment.intValue(xs[i])
                    if (cur != coverVal && coverVal in state.problem.intDomains[xs[i]]) {
                        sink.addChannelingIntSet(state, xs[i], coverVal)
                    }
                }
            } else {
                // Pick xs[i] currently AT coverVal; move it to a non-coverVal in its domain.
                for (i in xs.indices) {
                    if (!present(state, i)) continue
                    val cur = state.assignment.intValue(xs[i])
                    if (cur != coverVal) continue
                    val d = state.problem.intDomains[xs[i]]
                    var pick: Int? = null
                    d.forEach { if (pick == null && it != coverVal) pick = it }
                    if (pick != null) sink.addChannelingIntSet(state, xs[i], pick)
                }
            }
        }
        // 3. Closed mode: drag out-of-cover xs[i] into the cover.
        if (closed) {
            for (i in xs.indices) {
                if (!present(state, i)) continue
                val cur = state.assignment.intValue(xs[i])
                if (cur in coverIndexByValue) continue
                val d = state.problem.intDomains[xs[i]]
                for (cv in cover) {
                    if (cv in d && cv != cur) {
                        sink.addChannelingIntSet(state, xs[i], cv)
                        break
                    }
                }
            }
        }
    }
}
