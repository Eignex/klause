package com.eignex.klause.factor.global

import com.eignex.klause.config.DEFAULT_DOMAIN_WALK_CAP
import com.eignex.klause.factor.arithmetic.internals.collectHoleAndBoundAntecedents
import com.eignex.klause.factor.global.internals.GccAssignmentReplay
import com.eignex.klause.factor.global.internals.GccIncrementalState
import com.eignex.klause.factor.global.internals.GccPropCache
import com.eignex.klause.factor.table.internals.allEventWatches
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.values
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.propagation.RevIntArray
import com.eignex.klause.propagation.boundLiteral
import com.eignex.klause.propagation.exclusionLiteral
import com.eignex.klause.propagation.restrictIntToSurvivors
import com.eignex.util.arrays.emptyIntArray
import com.eignex.util.collections.IntArrayList
import com.eignex.util.collections.IntHashSet
import com.eignex.util.collections.LongArrayList
import com.eignex.util.collections.MutableIntIntMap
import com.eignex.util.collections.MutableLongIntMap

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

    override val expensiveBake: Boolean get() = true

    // Without presence literals a fire re-blocks only the var→cover edges of the changed variables. The
    // optional path keeps occurrence-list wakeup because presence bools are not int events.
    override val initialIntEventWatches: IntArray? =
        if (presents.isEmpty()) allEventWatches(intVars) else null

    override val consumesIntEventDelta: Boolean = presents.isEmpty()

    private val xsIndexOf: MutableIntIntMap = MutableIntIntMap(xs.size)

    // A repeated var id maps to several indices, which the delta-driven block scan would miss.
    private val xsHasDups: Boolean = run {
        var dup = false
        for (i in xs.indices) {
            if (xsIndexOf.containsKey(xs[i])) dup = true else xsIndexOf.put(xs[i], i)
        }
        dup
    }

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        state.propagatorFailures[this] ?: withPresencePremises(state, collectHoleAndBoundAntecedents(state, intVars))

    // Literals false now: the clause form of a reason over the conditions it is given.
    private inner class Reason(private val state: PropagationState) {
        private val seen = IntHashSet()
        private val out = IntArrayList()

        fun add(lit: Int) {
            if (lit != Lit.NONE && seen.add(lit)) out.add(lit)
        }

        fun pinned(x: Int, value: Long) = add(Lit.make(state.atomVarEq(x, value), false))

        fun lacks(x: Int, value: Long) = add(
            if (state.undoLogging) {
                state.exclusionLiteral(x, value, state.undo.size)
            } else if (value in state.rootDomains[x]) {
                Lit.make(state.atomVarEq(x, value), true)
            } else {
                Lit.NONE
            },
        )

        // Every value outside the cover had left x's domain.
        fun lacksOthers(x: Int) {
            collectHoleAndBoundAntecedents(state, intArrayOf(x))?.forEach { add(it) }
        }

        fun countBounds(k: Int) {
            val cv = countVars ?: return
            val d = state.intDomains[cv[k]]
            add(state.boundLiteral(cv[k], true, d.min, state.undo.size, state.currentLevel))
            add(state.boundLiteral(cv[k], false, d.max, state.undo.size, state.currentLevel))
        }

        fun presence() {
            presencePremiseLits(state).forEach { add(it) }
        }

        fun build(): IntArray? = if (state.currentLevel == 0) null else out.toIntArray()
    }

    // Count of cover[k] at least the variables pinned to it.
    private fun pinnedReason(state: PropagationState, scope: IntArray, k: Int): Reason = Reason(state).apply {
        for (x in scope) {
            val d = state.intDomains[x]
            if (d.min == d.max && d.min == cover[k]) pinned(x, cover[k])
        }
        presence()
    }

    // Count of cover[k] at most the variables that still hold it.
    private fun possibleReason(state: PropagationState, scope: IntArray, k: Int): Reason = Reason(state).apply {
        for (x in scope) if (cover[k] !in state.intDomains[x]) lacks(x, cover[k])
        presence()
    }

    // Only an edge leaving a reached variable could reopen a path, so the reason is every var→value edge the
    // reached variables lack, plus the count bounds the capacities read.
    private fun cutReason(
        state: PropagationState,
        xs: IntArray,
        reach: BooleanArray,
        otherNode: Int,
        xToOther: IntArray,
    ): Reason = Reason(state).apply {
        val n = xs.size
        for (i in 0 until n) {
            if (!reach[2 + i]) continue
            val d = state.intDomains[xs[i]]
            for (k in cover.indices) if (cover[k] !in d) lacks(xs[i], cover[k])
            if (otherNode >= 0 && xToOther[i] < 0) lacksOthers(xs[i])
        }
        for (k in cover.indices) countBounds(k)
        presence()
    }

    private fun presencePremiseLits(state: PropagationState): IntArray {
        if (presents.isEmpty()) return emptyIntArray()
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

    // A first fire or a widened domain forces a full recount; otherwise only shrunk domains are patched.
    private fun maintainCounts(state: PropagationState, inc: GccIncrementalState) {
        val n = inc.n
        // The incremental delta enumerates domains value by value, so a domain past the walk cap forces
        // the span-safe full rebuild.
        val anyWide = (0 until n).any { state.intDomains[inc.xs[it]].spanOrNull(DEFAULT_DOMAIN_WALK_CAP) == null }
        var rebuild = inc.valid.value == 0 || anyWide
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
                cur.values.forEach { v -> if (v !in prev) widened = true }
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
                if (d.spanOrNull(DEFAULT_DOMAIN_WALK_CAP) != null) {
                    d.values.forEach { v ->
                        val k = coverIndexByValue.getOrDefault(v, -1)
                        if (k >= 0) inc.possible[k] = inc.possible[k] + 1
                    }
                } else {
                    for (k in cover.indices) if (cover[k] in d) inc.possible[k] = inc.possible[k] + 1
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
                prev.values.forEach { v ->
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
        val dirty = if (presents.isEmpty()) state.drainIntEventDirtyVars(factorId) else emptyIntArray()
        if (presents.isEmpty() && intVars.isNotEmpty() && cache.cachedDoms[0] != null && dirty.isEmpty()) {
            return true
        }
        state.propagatorFailures.remove(this)
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
            emptyIntArray()
        } else {
            val acc = IntArrayList()
            for (i in xs.indices) {
                if (!definitelyPresentGccFn(i, state) && !definitelyAbsentGccFn(i, state)) {
                    acc.add(xs[i])
                }
            }
            acc.toIntArray()
        }
        val counted = effectiveXs + maybeXs
        if (closed) {
            for ((j, x) in effectiveXs.withIndex()) {
                val d = state.intDomains[x]
                if (d.spanOrNull(DEFAULT_DOMAIN_WALK_CAP) != null) {
                    val toRemove = LongArrayList()
                    d.values.forEach { if (!coverIndexByValue.containsKey(it)) toRemove.add(it) }
                    // A closed cardinality admits no value outside the cover to a variable that takes part.
                    val ant = Reason(state).apply {
                        if (presents.isNotEmpty()) add(Lit.negate(presents[origIdx[j]]))
                    }.build()
                    for (k in 0 until toRemove.size) {
                        if (!state.excludeIntValue(x, toRemove[k], ant)) {
                            state.propagatorFailures[this] = Reason(state).apply { lacksOthers(x) }.build()
                            return false
                        }
                    }
                } else if (!state.restrictIntToSurvivors(x, cover)) {
                    // A domain too large to walk: restrict to the (small) cover set directly rather than
                    // walking the span to collect non-cover values. Removing every non-cover value is an
                    // unconditional consequence of a closed GCC (no premises), so the antecedent-free
                    // restrict is sound.
                    return false
                }
            }
        }
        val cvArr = countVars
        // `definite` counts vars pinned to cover(k), `possible` vars whose domain holds it. Only the
        // no-presence path maintains them incrementally on the trail.
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
                val cv = cvArr[k]
                if (definite[k] > state.intDomains[cv].min &&
                    !state.tightenIntMin(cv, definite[k].toLong(), pinnedReason(state, effectiveXs, k).build())
                ) {
                    state.propagatorFailures[this] =
                        pinnedReason(state, effectiveXs, k).apply { countBounds(k) }.build()
                    return false
                }
                if (possible[k] < state.intDomains[cv].max &&
                    !state.tightenIntMax(cv, possible[k].toLong(), possibleReason(state, counted, k).build())
                ) {
                    state.propagatorFailures[this] = possibleReason(state, counted, k).apply { countBounds(k) }.build()
                    return false
                }
            } else {
                if (requireNotNull(countLow)[k] > possible[k]) {
                    state.propagatorFailures[this] = possibleReason(state, counted, k).build()
                    return false
                }
                if (requireNotNull(countHigh)[k] < definite[k]) {
                    state.propagatorFailures[this] = pinnedReason(state, effectiveXs, k).build()
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
        val firstValue = 2 + n
        val flow = cache.flow
        // The fixed-bound, no-presence path keeps a persistent network built for the root domains, with the
        // flow on the trail. Removing a flow-free edge keeps the flow maximum; a removed flow-carrying edge is
        // rerouted in place, or re-solved on the same structure when no alternate path exists.
        val persistentEligible = presents.isEmpty() && countVars == null
        val xToCovEdgeIdx: IntArray
        val xToOtherEdgeIdx: IntArray
        val otherNode: Int
        val baseNodes: Int
        val superSource: Int
        val superSink: Int
        val requiredSSFlow: Int
        var needFlowSolve = true
        // A reuse fire that blocks no edge leaves the flow identical to the previous fire, whose SCC prune
        // already reached fixpoint.
        var residualChanged = true
        if (persistentEligible && cache.structBuilt && flow.frozen && cache.sN == n && cache.sM == m) {
            xToCovEdgeIdx = cache.pXToCov
            xToOtherEdgeIdx = cache.xToOther(n)
            otherNode = -1
            baseNodes = cache.sBaseNodes
            superSource = cache.sSuperSource
            superSink = cache.sSuperSink
            requiredSSFlow = cache.sRequiredSSFlow
            var needResolve = false
            var touchedFlow = false

            // Only a variable whose domain shrank can have a newly-absent edge, so scanning the dirty
            // variables is complete.
            fun blockVar(i: Int) {
                val d = state.intDomains[effectiveXs[i]]
                for (k in 0 until m) {
                    val e = xToCovEdgeIdx[i * m + k]
                    if (e < 0 || cover[k] in d) continue
                    touchedFlow = true
                    if (flow.flowOf(e) > 0) {
                        if (flow.recoverEdge(e, 2 + i, 2 + n + k)) flow.blockEdge(e) else needResolve = true
                    } else {
                        flow.blockEdge(e)
                    }
                }
            }
            if (xsHasDups) {
                for (i in 0 until n) blockVar(i)
            } else {
                for (dv in dirty) {
                    val i = xsIndexOf.getOrDefault(dv, -1)
                    if (i >= 0) blockVar(i)
                }
            }
            residualChanged = touchedFlow
            if (needResolve) {
                flow.resetFlow()
                for (i in 0 until n) {
                    val d = state.intDomains[effectiveXs[i]]
                    for (k in 0 until m) {
                        val e = xToCovEdgeIdx[i * m + k]
                        if (e >= 0 && cover[k] !in d) flow.blockEdge(e)
                    }
                }
            } else {
                needFlowSolve = false
            }
        } else {
            val hasOtherVar = BooleanArray(n)
            val anyOther = !closed && run {
                var any = false
                for (i in 0 until n) {
                    val d = state.intDomains[effectiveXs[i]]
                    // A domain past the walk cap is over-approximated as holding a value outside the cover.
                    val found = if (d.spanOrNull(DEFAULT_DOMAIN_WALK_CAP) == null) {
                        true
                    } else {
                        var f = false
                        d.values.forEach { if (!f && !coverIndexByValue.containsKey(it)) f = true }
                        f
                    }
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
            val replay = if (presents.isEmpty()) {
                (cache.assignmentReplay ?: GccAssignmentReplay(n, m).also { cache.assignmentReplay = it })
                    .also { it.reset() }
            } else {
                null
            }
            val excess = cache.excess(baseNodes + 2)
            // Persist only the first build, which sees the root domains and so spans every later state.
            // `anyOther` is not monotone under backtracking, so a later build could miss edges a restored
            // domain needs.
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
                val aboveMinimum = flow.addEdge(firstValue + k, sink, hi[k] - lo[k])
                replay?.aboveMinimum?.set(k, aboveMinimum)
            }
            if (otherNode != -1) flow.addEdge(otherNode, sink, n)
            excess[sink] -= n
            excess[source] += n
            flow.addEdge(sink, source, 0)
            var req = 0
            for (v in 0 until baseNodes) {
                when {
                    excess[v] > 0 -> {
                        val supply = flow.addEdge(superSource, v, excess[v])
                        if (v in 2 until firstValue) replay?.supply?.set(v - 2, supply)
                        req += excess[v]
                    }

                    excess[v] < 0 -> {
                        val drain = flow.addEdge(v, superSink, -excess[v])
                        if (v == sink) replay?.remainingTotal = drain
                        if (v in firstValue until firstValue + m) replay?.minimum?.set(v - firstValue, drain)
                    }
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
            cache.assignmentReplay?.let { replay ->
                val assign = cache.flowAssign ?: RevIntArray(state, n, -1).also { cache.flowAssign = it }
                for (i in 0 until n) {
                    val k = assign[i]
                    if (k !in 0 until m) continue
                    val choice = xToCovEdgeIdx[i * m + k]
                    if (choice >= 0) flow.seedAssignment(replay, i, k, choice)
                }
            }
            val obtained = flow.maxFlow(superSource, superSink)
            if (obtained < requiredSSFlow) {
                val reach = flow.residualReachable(superSource)
                state.propagatorFailures[this] =
                    cutReason(state, effectiveXs, reach, otherNode, xToOtherEdgeIdx).build()
                return false
            }
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
        if (!residualChanged) {
            if (presents.isEmpty()) for (i in intVars.indices) cache.cachedDoms[i] = state.intDomains[intVars[i]]
            return true
        }
        val sccId = cache.sccId(baseNodes)
        flow.computeSccResidual(baseNodes, sccId)
        // A prune of x(i) = cover(k) holds because nothing the value's node reaches leads back to x(i); the cut
        // around what it reaches is shared by every value of its component.
        val cutBySource = HashMap<Int, IntArray?>()
        fun cutFrom(node: Int): IntArray? = cutBySource.getOrPut(sccId[node]) {
            cutReason(state, effectiveXs, flow.residualReachable(node), otherNode, xToOtherEdgeIdx).build()
        }
        for (i in 0 until n) {
            for (k in 0 until m) {
                val eIdx = xToCovEdgeIdx[i * m + k]
                if (eIdx < 0) continue
                if (flow.flowOf(eIdx) > 0) continue
                if (sccId[2 + i] == sccId[2 + n + k]) continue
                val ant = cutFrom(2 + n + k)
                if (!state.excludeIntValue(effectiveXs[i], cover[k], ant)) {
                    state.propagatorFailures[this] = Reason(state).apply {
                        ant?.forEach { add(it) }
                        lacksOthers(effectiveXs[i])
                    }.build()
                    return false
                }
            }
            val oIdx = xToOtherEdgeIdx[i]
            if (oIdx >= 0 && flow.flowOf(oIdx) == 0 && sccId[2 + i] != sccId[otherNode]) {
                val d = state.intDomains[effectiveXs[i]]
                if (d.spanOrNull(DEFAULT_DOMAIN_WALK_CAP) != null) {
                    val toRemove = LongArrayList()
                    d.values.forEach { if (!coverIndexByValue.containsKey(it)) toRemove.add(it) }
                    val ant = cutFrom(otherNode)
                    for (k in 0 until toRemove.size) {
                        if (!state.excludeIntValue(effectiveXs[i], toRemove[k], ant)) {
                            state.propagatorFailures[this] = Reason(state).apply {
                                ant?.forEach { add(it) }
                                lacksOthers(effectiveXs[i])
                            }.build()
                            return false
                        }
                    }
                }
                // A domain too large to walk is skipped; the removal runs once it narrows below the cap.
            }
        }
        if (presents.isEmpty()) for (i in intVars.indices) cache.cachedDoms[i] = state.intDomains[intVars[i]]
        return true
    }
}
