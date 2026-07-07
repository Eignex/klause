package com.eignex.klause.factor.global

import com.eignex.klause.factor.arithmetic.internals.collectHoleAndBoundAntecedents
import com.eignex.klause.factor.global.internals.GccPropCache
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.propagation.RevIntArray
import com.eignex.klause.solver.Lit
import com.eignex.klause.util.EmptyIntArray
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntIntMap
import com.eignex.klause.util.LongArrayList

/** CP propagation logic for `global_cardinality`. */
internal class GlobalCardinalityPropagator(
    val boolVars: IntArray,
    val intVars: IntArray,
    private val xs: IntArray,
    private val cover: IntArray,
    private val countVars: IntArray?,
    private val countLow: IntArray?,
    private val countHigh: IntArray?,
    private val closed: Boolean,
    private val presents: IntArray,
    private val coverIndexByValue: IntIntMap,
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
                d.forEach { if (!coverIndexByValue.contains(it.toInt())) toRemove.add(it) }
                for (k in 0 until toRemove.size) {
                    if (!state.excludeIntValue(x, toRemove[k], gccAntecedents)) return false
                }
            }
        }
        val cvArr = countVars
        val definite = IntArray(m)
        val possible = IntArray(m)
        for (k in cover.indices) {
            val target = cover[k].toLong()
            for (x in effectiveXs) {
                val d = state.intDomains[x]
                if (d.min == d.max && d.min == target) definite[k]++
                if (target in d) possible[k]++
            }
            for (x in maybeXs) {
                if (target in state.intDomains[x]) possible[k]++
            }
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
        val hasOtherVar = BooleanArray(n)
        val anyOther = !closed && run {
            var any = false
            for (i in 0 until n) {
                val d = state.intDomains[effectiveXs[i]]
                var found = false
                d.forEach { if (!found && !coverIndexByValue.contains(it.toInt())) found = true }
                hasOtherVar[i] = found
                if (found) any = true
            }
            any
        }
        val source = 0
        val sink = 1
        val varNode = IntArray(n) { 2 + it }
        val covNode = IntArray(m) { 2 + n + it }
        val otherNode = if (anyOther) 2 + n + m else -1
        val baseNodes = 2 + n + m + (if (anyOther) 1 else 0)
        val superSource = baseNodes
        val superSink = baseNodes + 1
        val totalNodes = baseNodes + 2
        val flow = cache.flow.also { it.reset(totalNodes) }
        val excess = IntArray(totalNodes)
        val xToCovEdgeIdx = Array(n) { IntArray(m) { -1 } }
        val xToOtherEdgeIdx = IntArray(n) { -1 }
        for (i in 0 until n) {
            excess[source] -= 1
            excess[varNode[i]] += 1
            flow.addEdge(source, varNode[i], 0)
        }
        for (i in 0 until n) {
            val d = state.intDomains[effectiveXs[i]]
            for (k in 0 until m) {
                if (cover[k].toLong() in d) {
                    val eIdx = flow.addEdge(varNode[i], covNode[k], 1)
                    xToCovEdgeIdx[i][k] = eIdx
                }
            }
            if (otherNode != -1 && hasOtherVar[i]) {
                xToOtherEdgeIdx[i] = flow.addEdge(varNode[i], otherNode, 1)
            }
        }
        for (k in 0 until m) {
            if (lo[k] > hi[k]) return false
            excess[covNode[k]] -= lo[k]
            excess[sink] += lo[k]
            flow.addEdge(covNode[k], sink, hi[k] - lo[k])
        }
        if (otherNode != -1) {
            flow.addEdge(otherNode, sink, n)
        }
        excess[sink] -= n
        excess[source] += n
        flow.addEdge(sink, source, 0)
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
        if (presents.isEmpty()) {
            val assign = cache.flowAssign ?: RevIntArray(state, n, -1).also { cache.flowAssign = it }
            for (i in 0 until n) {
                val k = assign[i]
                if (k in 0 until m && xToCovEdgeIdx[i][k] >= 0) {
                    flow.augmentThroughEdge(superSource, superSink, varNode[i], covNode[k])
                }
            }
        }
        val obtained = flow.maxFlow(superSource, superSink)
        if (obtained < requiredSSFlow) {
            val reach = flow.residualReachable(superSource)
            val resp = IntArrayList()
            for (i in 0 until n) if (reach[varNode[i]]) resp.add(effectiveXs[i])
            if (cvArr != null) for (k in 0 until m) resp.add(cvArr[k])
            if (resp.size > 0) cache.conflictVars = resp.toIntArray()
            return false
        }
        cache.flowAssign?.let { assign ->
            for (i in 0 until n) {
                var chosen = -1
                for (k in 0 until m) {
                    val e = xToCovEdgeIdx[i][k]
                    if (e >= 0 && flow.flowOf(e) > 0) {
                        chosen = k
                        break
                    }
                }
                assign[i] = chosen
            }
        }
        val sccId = IntArray(baseNodes) { -1 }
        flow.computeSccResidual(baseNodes, sccId)
        for (i in 0 until n) {
            for (k in 0 until m) {
                val eIdx = xToCovEdgeIdx[i][k]
                if (eIdx < 0) continue
                if (flow.flowOf(eIdx) > 0) continue
                if (sccId[varNode[i]] == sccId[covNode[k]]) continue
                if (!state.excludeIntValue(effectiveXs[i], cover[k].toLong(), gccAntecedents)) return false
            }
            val oIdx = xToOtherEdgeIdx[i]
            if (oIdx >= 0 && flow.flowOf(oIdx) == 0 && sccId[varNode[i]] != sccId[otherNode]) {
                val d = state.intDomains[effectiveXs[i]]
                val toRemove = LongArrayList()
                d.forEach { if (!coverIndexByValue.contains(it.toInt())) toRemove.add(it) }
                for (k in 0 until toRemove.size) {
                    if (!state.excludeIntValue(effectiveXs[i], toRemove[k], gccAntecedents)) return false
                }
            }
        }
        if (presents.isEmpty()) for (i in intVars.indices) cache.cachedDoms[i] = state.intDomains[intVars[i]]
        return true
    }
}
