package com.eignex.klause.factor.global

import com.eignex.klause.factor.arithmetic.internals.collectHoleAndBoundAntecedents
import com.eignex.klause.factor.global.internals.GccIncrementalState
import com.eignex.klause.factor.global.internals.GccPropCache
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.propagation.RevIntArray
import com.eignex.klause.solver.Lit
import com.eignex.klause.util.EmptyIntArray
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.LongArrayList
import com.eignex.klause.util.MutableLongIntMap

/** CP propagation logic for `global_cardinality`. */
internal class GlobalCardinalityPropagator(
    val boolVars: IntArray,
    val intVars: IntArray,
    private val xs: IntArray,
    private val cover: LongArray,
    private val countVars: IntArray?,
    private val countLow: IntArray?,
    private val countHigh: IntArray?,
    private val closed: Boolean,
    private val presents: IntArray,
    private val coverIndexByValue: MutableLongIntMap,
    private val definitelyPresentGccFn: (Int, PropagationState) -> Boolean,
    private val definitelyAbsentGccFn: (Int, PropagationState) -> Boolean,
) : Propagator {

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? = withPresencePremises(
        state,
        collectHoleAndBoundAntecedents(state, (state.refPayload[factorId] as? GccPropCache)?.conflictVars ?: intVars),
    )

    private fun pinnedTo(state: PropagationState, scope: IntArray, value: Long): IntArrayList {
        val out = IntArrayList()
        for (x in scope) {
            val d = state.intDomains[x]
            if (d.min == d.max && d.min == value) out.add(x)
        }
        return out
    }

    private fun presencePremiseLits(state: PropagationState): IntArray {
        if (presents.isEmpty()) return EmptyIntArray
        val out = IntArrayList()
        for (i in presents.indices) {
            when {
                definitelyPresentGccFn(i, state) -> out.add(Lit.negate(presents[i]))
                definitelyAbsentGccFn(i, state) -> out.add(presents[i])
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

    /**
     * Bring [inc]'s reversible `definite`/`possible` counts up to the current domains. A first fire or a
     * backtrack that restored (widened) any domain forces a full recount; otherwise only the variables
     * whose domain shrank since the last fire are patched — each deleted cover value drops `possible`,
     * and a variable reaching a singleton on a cover value is counted into `definite` exactly once. The
     * per-var `domRef` is then re-based to the current domains for the next delta.
     */
    private fun maintainCounts(state: PropagationState, inc: GccIncrementalState) {
        val n = inc.n
        var rebuild = inc.valid.value == 0
        if (!rebuild) {
            for (i in 0 until n) {
                val prev = inc.domRef[i].value
                if (prev == null) {
                    rebuild = true
                    break
                }
                val cur = state.intDomains[inc.xs[i]]
                if (cur === prev) continue
                var widened = false
                cur.forEach { v -> if (v !in prev) widened = true }
                if (widened) {
                    rebuild = true
                    break
                }
            }
        }
        if (rebuild) {
            for (k in 0 until inc.m) {
                inc.definite[k] = 0
                inc.possible[k] = 0
            }
            for (i in 0 until n) {
                inc.pinnedCover[i] = -1
                val d = state.intDomains[inc.xs[i]]
                d.forEach { v ->
                    val k = coverIndexByValue.getOrDefault(v, -1)
                    if (k >= 0) inc.possible[k] = inc.possible[k] + 1
                }
                if (d.min == d.max) {
                    val k = coverIndexByValue.getOrDefault(d.min, -1)
                    inc.pinnedCover[i] = if (k >= 0) k else -2
                    if (k >= 0) inc.definite[k] = inc.definite[k] + 1
                }
            }
            inc.valid.set(1)
        } else {
            for (i in 0 until n) {
                val prev = requireNotNull(inc.domRef[i].value)
                val cur = state.intDomains[inc.xs[i]]
                if (cur === prev) continue
                prev.forEach { v ->
                    if (v !in cur) {
                        val k = coverIndexByValue.getOrDefault(v, -1)
                        if (k >= 0) inc.possible[k] = inc.possible[k] - 1
                    }
                }
                if (inc.pinnedCover[i] == -1 && cur.min == cur.max) {
                    val k = coverIndexByValue.getOrDefault(cur.min, -1)
                    inc.pinnedCover[i] = if (k >= 0) k else -2
                    if (k >= 0) inc.definite[k] = inc.definite[k] + 1
                }
            }
        }
        for (i in 0 until n) inc.domRef[i].set(state.intDomains[inc.xs[i]])
    }

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        val cache = (state.refPayload[factorId] as? GccPropCache) ?: run {
            val fresh = GccPropCache(arrayOfNulls(intVars.size))
            state.refPayload[factorId] = fresh
            fresh
        }
        if (presents.isEmpty() && intVars.isNotEmpty()) {
            var changed = false
            for (i in intVars.indices) {
                if (cache.cachedDoms[i] !== state.intDomains[intVars[i]]) {
                    changed = true
                    break
                }
            }
            if (!changed && cache.cachedDoms[0] != null) return true
        }
        cache.conflictVars = null
        val origIdx: IntArray = if (presents.isEmpty()) {
            IntArray(xs.size) { it }
        } else {
            val acc = IntArrayList()
            for (i in xs.indices) if (definitelyPresentGccFn(i, state)) acc.add(i)
            IntArray(acc.size) { acc[it] }
        }
        val effectiveXs: IntArray = if (presents.isEmpty()) {
            xs
        } else {
            IntArray(origIdx.size) { xs[origIdx[it]] }
        }
        val n = effectiveXs.size
        val m = cover.size
        val maybeXs: IntArray = if (presents.isEmpty()) {
            EmptyIntArray
        } else {
            val acc = IntArrayList()
            for (i in xs.indices) {
                if (!definitelyPresentGccFn(i, state) && !definitelyAbsentGccFn(i, state)) {
                    acc.add(xs[i])
                }
            }
            acc.toIntArray()
        }
        val gccAntecedents = withPresencePremises(
            state,
            state.composeIntVarAtomAntecedents(effectiveXs + maybeXs + (countVars ?: EmptyIntArray)),
        )
        if (closed) {
            for (x in effectiveXs) {
                val d = state.intDomains[x]
                val toRemove = LongArrayList()
                d.forEach { if (!coverIndexByValue.containsKey(it)) toRemove.add(it) }
                for (k in 0 until toRemove.size) {
                    if (!state.excludeIntValue(x, toRemove[k], gccAntecedents)) return false
                }
            }
        }
        val cvArr = countVars
        // Per-cover `definite` (vars pinned to cover[k]) and `possible` (vars whose domain holds it)
        // counts. On the plain (no-presence) path these are maintained incrementally on the undo trail —
        // a fire patches only the vars whose domain changed since the last fire; the optional-variable
        // path recounts fully (maybeXs is only non-empty there).
        val incCounts: GccIncrementalState? = if (presents.isEmpty()) {
            val cur = cache.inc?.takeIf { it.xs.contentEquals(effectiveXs) && it.m == m }
                ?: GccIncrementalState(state, effectiveXs, m).also { cache.inc = it }
            maintainCounts(state, cur)
            cur
        } else {
            null
        }
        val definite = IntArray(m)
        val possible = IntArray(m)
        if (incCounts == null) {
            for (k in cover.indices) {
                val target = cover[k]
                for (x in effectiveXs) {
                    val d = state.intDomains[x]
                    if (d.min == d.max && d.min == target) definite[k]++
                    if (target in d) possible[k]++
                }
                for (x in maybeXs) {
                    if (target in state.intDomains[x]) possible[k]++
                }
            }
        } else {
            for (k in 0 until m) {
                definite[k] = incCounts.definite[k]
                possible[k] = incCounts.possible[k]
            }
        }
        for (k in cover.indices) {
            val target = cover[k]
            if (cvArr != null) {
                if (!state.tightenIntMin(cvArr[k], definite[k].toLong(), gccAntecedents)) {
                    cache.conflictVars = pinnedTo(state, effectiveXs, target)
                        .also { it.add(cvArr[k]) }.toIntArray()
                    return false
                }
                if (!state.tightenIntMax(cvArr[k], possible[k].toLong(), gccAntecedents)) return false
            } else {
                if (requireNotNull(countLow)[k] > possible[k]) return false
                if (requireNotNull(countHigh)[k] < definite[k]) {
                    cache.conflictVars = pinnedTo(state, effectiveXs, target).toIntArray()
                    return false
                }
            }
        }
        val lo = IntArray(m)
        val hi = IntArray(m)
        for (k in 0 until m) {
            if (cvArr != null) {
                val cd = state.intDomains[cvArr[k]]
                lo[k] = cd.min.toInt()
                hi[k] = cd.max.toInt()
            } else {
                lo[k] = requireNotNull(countLow)[k]
                hi[k] = requireNotNull(countHigh)[k]
            }
        }
        if (maybeXs.isNotEmpty()) return true
        val source = 0
        val sink = 1
        // Node ids: variable i is `2 + i`, cover value k is `2 + n + k`.
        val flow = cache.flow
        // The fixed-bound, no-presence path keeps a persistent, reversible network (see [GccPropCache] /
        // [GccFlowBuilder]): the edge set is built once for the root (widest) domains and the flow lives on
        // the undo trail, so a fire only blocks the var→cover edges whose value left and — since removing a
        // flow-FREE edge cannot lower the (already maximum, still feasible) flow — needs no re-solve. A
        // removed flow-CARRYING edge (a broken assignment) re-solves the flow on the same structure.
        val persistentEligible = presents.isEmpty() && countVars == null
        val xToCovEdgeIdx: IntArray
        val xToOtherEdgeIdx: IntArray
        val otherNode: Int
        val baseNodes: Int
        val superSource: Int
        val superSink: Int
        val requiredSSFlow: Int
        var needFlowSolve = true // false on the fast reuse path (flow persists, unchanged)
        if (persistentEligible && cache.structBuilt && flow.frozen && cache.sN == n && cache.sM == m) {
            xToCovEdgeIdx = cache.pXToCov
            xToOtherEdgeIdx = cache.xToOther(n) // no-other case: all -1
            otherNode = -1
            baseNodes = cache.sBaseNodes
            superSource = cache.sSuperSource
            superSink = cache.sSuperSink
            requiredSSFlow = cache.sRequiredSSFlow
            // Incremental repair: block every newly-absent var→cover edge; a flow-free one just drops out
            // (the flow stays maximum), a flow-carrying one (a broken assignment) is recovered in place by
            // rerouting that variable's unit along an alternate path. Only if a reroute has no alternate do
            // we fall back to a full re-solve. Either way no O(n) warm-start replay on the common path.
            var needResolve = false
            for (i in 0 until n) {
                val d = state.intDomains[effectiveXs[i]]
                for (k in 0 until m) {
                    val e = xToCovEdgeIdx[i * m + k]
                    if (e < 0 || cover[k] in d) continue
                    if (flow.flowOf(e) > 0) {
                        if (flow.recoverEdge(e, 2 + i, 2 + n + k)) flow.blockEdge(e) else needResolve = true
                    } else {
                        flow.blockEdge(e)
                    }
                }
            }
            if (needResolve) {
                // A variable's unit could not be rerouted locally: clear the residual, block every absent
                // edge, and re-solve from the surviving assignment (replay + max flow) below.
                flow.resetFlow()
                for (i in 0 until n) {
                    val d = state.intDomains[effectiveXs[i]]
                    for (k in 0 until m) {
                        val e = xToCovEdgeIdx[i * m + k]
                        if (e >= 0 && cover[k] !in d) flow.blockEdge(e)
                    }
                }
            } else {
                // Fully recovered in place: the flow is still maximum and feasible.
                needFlowSolve = false
            }
        } else {
            val hasOtherVar = BooleanArray(n)
            val anyOther = !closed && run {
                var any = false
                for (i in 0 until n) {
                    val d = state.intDomains[effectiveXs[i]]
                    var found = false
                    d.forEach { if (!found && !coverIndexByValue.containsKey(it)) found = true }
                    hasOtherVar[i] = found
                    if (found) any = true
                }
                any
            }
            otherNode = if (anyOther) 2 + n + m else -1
            baseNodes = 2 + n + m + (if (anyOther) 1 else 0)
            superSource = baseNodes
            superSink = baseNodes + 1
            flow.reset(baseNodes + 2)
            val excess = cache.excess(baseNodes + 2)
            // Freeze a persistent structure only on the FIRST build: that fire sees the widest (root)
            // domains, so its edge set and no-other layout span every later state. `anyOther` is not
            // monotone (search can prune then a backtrack restore a non-cover value), so persisting on a
            // later build could omit edges/other-node the restored domain needs. A persistent structure
            // owns its edge-index map; the pooled buffer is reused per fire otherwise.
            val storePersistent = persistentEligible && !anyOther && !cache.builtOnce
            cache.builtOnce = true
            xToCovEdgeIdx = if (storePersistent) IntArray(n * m) { -1 } else cache.xToCov(n, m)
            xToOtherEdgeIdx = cache.xToOther(n)
            for (i in 0 until n) {
                excess[source] -= 1
                excess[2 + i] += 1
                flow.addEdge(source, 2 + i, 0)
            }
            for (i in 0 until n) {
                val d = state.intDomains[effectiveXs[i]]
                for (k in 0 until m) {
                    if (cover[k] in d) xToCovEdgeIdx[i * m + k] = flow.addEdge(2 + i, 2 + n + k, 1)
                }
                if (otherNode != -1 && hasOtherVar[i]) xToOtherEdgeIdx[i] = flow.addEdge(2 + i, otherNode, 1)
            }
            for (k in 0 until m) {
                if (lo[k] > hi[k]) return false
                excess[2 + n + k] -= lo[k]
                excess[sink] += lo[k]
                flow.addEdge(2 + n + k, sink, hi[k] - lo[k])
            }
            if (otherNode != -1) flow.addEdge(otherNode, sink, n)
            excess[sink] -= n
            excess[source] += n
            flow.addEdge(sink, source, 0)
            var req = 0
            for (v in 0 until baseNodes) {
                when {
                    excess[v] > 0 -> {
                        flow.addEdge(superSource, v, excess[v])
                        req += excess[v]
                    }

                    excess[v] < 0 -> flow.addEdge(v, superSink, -excess[v])
                }
            }
            requiredSSFlow = req
            if (storePersistent) {
                cache.structBuilt = true
                cache.sN = n
                cache.sM = m
                cache.sBaseNodes = baseNodes
                cache.sSuperSource = superSource
                cache.sSuperSink = superSink
                cache.sRequiredSSFlow = req
                cache.pXToCov = xToCovEdgeIdx
            }
        }
        if (needFlowSolve) {
            if (presents.isEmpty()) {
                val assign = cache.flowAssign ?: RevIntArray(state, n, -1).also { cache.flowAssign = it }
                for (i in 0 until n) {
                    val k = assign[i]
                    if (k in 0 until m && xToCovEdgeIdx[i * m + k] >= 0) {
                        flow.augmentThroughEdge(superSource, superSink, 2 + i, 2 + n + k)
                    }
                }
            }
            val obtained = flow.maxFlow(superSource, superSink)
            if (obtained < requiredSSFlow) {
                val reach = flow.residualReachable(superSource)
                val resp = IntArrayList()
                for (i in 0 until n) if (reach[2 + i]) resp.add(effectiveXs[i])
                if (cvArr != null) for (k in 0 until m) resp.add(cvArr[k])
                if (resp.size > 0) cache.conflictVars = resp.toIntArray()
                return false
            }
            // Persist the just-established flow so later fires reuse it without a rebuild or replay.
            if (persistentEligible && cache.structBuilt && !flow.frozen) flow.freeze(state)
            cache.flowAssign?.let { assign ->
                for (i in 0 until n) {
                    var chosen = -1
                    for (k in 0 until m) {
                        val e = xToCovEdgeIdx[i * m + k]
                        if (e >= 0 && flow.flowOf(e) > 0) {
                            chosen = k
                            break
                        }
                    }
                    assign[i] = chosen
                }
            }
        }
        val sccId = IntArray(baseNodes) { -1 }
        flow.computeSccResidual(baseNodes, sccId)
        for (i in 0 until n) {
            for (k in 0 until m) {
                val eIdx = xToCovEdgeIdx[i * m + k]
                if (eIdx < 0) continue
                if (flow.flowOf(eIdx) > 0) continue
                if (sccId[2 + i] == sccId[2 + n + k]) continue
                if (!state.excludeIntValue(effectiveXs[i], cover[k], gccAntecedents)) return false
            }
            val oIdx = xToOtherEdgeIdx[i]
            if (oIdx >= 0 && flow.flowOf(oIdx) == 0 && sccId[2 + i] != sccId[otherNode]) {
                val d = state.intDomains[effectiveXs[i]]
                val toRemove = LongArrayList()
                d.forEach { if (!coverIndexByValue.containsKey(it)) toRemove.add(it) }
                for (k in 0 until toRemove.size) {
                    if (!state.excludeIntValue(effectiveXs[i], toRemove[k], gccAntecedents)) return false
                }
            }
        }
        if (presents.isEmpty()) for (i in intVars.indices) cache.cachedDoms[i] = state.intDomains[intVars[i]]
        return true
    }
}
