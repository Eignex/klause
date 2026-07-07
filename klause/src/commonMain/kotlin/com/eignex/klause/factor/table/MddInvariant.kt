package com.eignex.klause.factor.table

import com.eignex.klause.factor.compressViolation
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.util.MutableIntObjectMap

/** LS invariant for [Mdd]. Constructed by [Mdd.asInvariant]. */
internal class MddInvariant(
    private val seq: IntArray,
    private val numStatesPerLayer: IntArray,
    private val layerStarts: IntArray,
    private val transitions: LongArray,
    private val initial: Int,
    private val accepting: IntArray,
    private val recordStride: Int,
    private val cost: Int,
) : Invariant {

    /** Positions in [seq] each variable occupies — usually one, so a single-symbol move recombines a
     *  single DP layer. A variable repeated across positions falls back to a full recompute. */
    private val positionsByVar: MutableIntObjectMap<IntArray> = run {
        val tmp = MutableIntObjectMap<MutableList<Int>>()
        for (i in seq.indices) tmp.getOrPut(seq[i]) { mutableListOf() }.add(i)
        val out = MutableIntObjectMap<IntArray>()
        tmp.forEach { v, cols -> out.put(v, cols.toIntArray()) }
        out
    }

    override fun initialize(state: LocalSearchState, factorId: Int) {
        val st = buildState(state)
        state.refPayload[factorId] = st
        state.factorDegree[factorId] = compressViolation(st.distance.toLong(), state.violationSoftCap)
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean =
        !mddPathExists(state, seq, layerStarts, transitions, recordStride, initial, accepting, -1, 0)

    override fun violationDegree(state: LocalSearchState, factorId: Int): Int {
        val st = state.refPayload[factorId] as? MddLsState ?: return fullDegree(state)
        return compressViolation(st.distance.toLong(), state.violationSoftCap)
    }

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Long): Int {
        val st = state.refPayload[factorId] as MddLsState
        val newDist = distanceWith(state, st, intVar, newValue)
        return compressViolation(newDist.toLong(), state.violationSoftCap) - state.factorDegree[factorId]
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Long): Int {
        if (state.assignment.intValue(intVar) == oldValue) return 0
        // Apply is once per accepted move; rebuild both layers (the order the old full recompute already
        // cost) so the per-candidate delta stays O(Q·Σ).
        val before = state.factorDegree[factorId]
        val rebuilt = buildState(state)
        state.refPayload[factorId] = rebuilt
        return compressViolation(rebuilt.distance.toLong(), state.violationSoftCap) - before
    }

    private fun fullDegree(state: LocalSearchState): Int = compressViolation(
        mddAcceptDistance(seq, numStatesPerLayer, layerStarts, transitions, recordStride, initial, accepting) {
            state.assignment.intValue(seq[it])
        }.toLong(),
        state.violationSoftCap,
    )

    private fun buildState(state: LocalSearchState): MddLsState {
        val getSym = { i: Int -> state.assignment.intValue(seq[i]) }
        val forward = mddForwardLayers(
            seq.size,
            numStatesPerLayer,
            layerStarts,
            transitions,
            recordStride,
            initial,
            getSym,
        )
        val backward =
            mddBackwardLayers(seq.size, numStatesPerLayer, layerStarts, transitions, recordStride, accepting, getSym)
        return MddLsState(forward, backward, acceptingDistance(forward))
    }

    /** Accept distance with [intVar] set to [newValue]. A single-position variable recombines its DP
     *  layer in O(Q·Σ); a repeated variable falls back to a full recompute. */
    private fun distanceWith(state: LocalSearchState, st: MddLsState, intVar: Int, newValue: Long): Int {
        val positions = positionsByVar[intVar]
        if (positions == null || positions.size != 1) {
            return mddAcceptDistance(
                seq,
                numStatesPerLayer,
                layerStarts,
                transitions,
                recordStride,
                initial,
                accepting,
            ) {
                if (seq[it] == intVar) newValue else state.assignment.intValue(seq[it])
            }
        }
        val p = positions[0]
        val inf = seq.size + 1
        var best = inf
        var rec = layerStarts[p]
        val end = layerStarts[p + 1]
        while (rec < end) {
            val from = transitions[rec].toInt()
            val sym = transitions[rec + 1]
            val to = transitions[rec + 2].toInt()
            val fq = st.forward[p][from]
            val bq = st.backward[p + 1][to]
            if (fq < inf && bq < inf) {
                val cand = fq + (if (sym == newValue) 0 else 1) + bq
                if (cand < best) best = cand
            }
            rec += recordStride
        }
        return best
    }

    private fun acceptingDistance(forward: Array<IntArray>): Int {
        val n = seq.size
        var best = n + 1
        for (s in accepting) if (s < forward[n].size && forward[n][s] < best) best = forward[n][s]
        return best
    }

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        if (!isViolated(state, factorId) || seq.isEmpty()) return
        val path = IntArray(seq.size + 1)
        path[0] = initial
        for (i in 0 until seq.size) {
            val symbol = state.assignment.intValue(seq[i])
            val start = layerStarts[i]
            val end = layerStarts[i + 1]
            var matchedDst = -1
            var p = start
            while (p < end) {
                if (transitions[p].toInt() == path[i] && transitions[p + 1] == symbol) {
                    matchedDst = transitions[p + 2].toInt()
                    break
                }
                p += recordStride
            }
            if (matchedDst < 0) {
                val d = state.problem.intDomains[seq[i]]
                var q = start
                while (q < end) {
                    if (transitions[q].toInt() == path[i]) {
                        val altSym = transitions[q + 1]
                        if (altSym != symbol && altSym in d) {
                            sink.addChannelingIntSet(state, seq[i], altSym)
                        }
                    }
                    q += recordStride
                }
                return
            }
            path[i + 1] = matchedDst
        }
        val last = seq.size - 1
        val qPrev = path[last]
        val curLast = state.assignment.intValue(seq[last])
        val d = state.problem.intDomains[seq[last]]
        val start = layerStarts[last]
        val end = layerStarts[last + 1]
        var p = start
        while (p < end) {
            if (transitions[p].toInt() == qPrev) {
                val sym = transitions[p + 1]
                val dst = transitions[p + 2].toInt()
                if (sym != curLast && sym in d && accepting.any { it == dst }) {
                    sink.addChannelingIntSet(state, seq[last], sym)
                }
            }
            p += recordStride
        }
    }

    override fun proposeExtendedRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        if (seq.isEmpty() || !isViolated(state, factorId)) return
        // DP-optimal repair: a minimum-change in-domain accepting layer path. Each differing position
        // is a move that strictly reduces the accept distance.
        val target = mddRepairPath(
            state,
            seq,
            numStatesPerLayer,
            layerStarts,
            transitions,
            recordStride,
            initial,
            accepting,
        ) ?: return
        for (i in seq.indices) {
            if (target[i] != state.assignment.intValue(seq[i])) {
                sink.addChannelingIntSet(state, seq[i], target[i])
            }
        }
    }

    override val providesImplicitNeighbourhood: Boolean get() = true

    override fun proposeStructuredMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        val n = seq.size
        val path = IntArray(n + 1)
        path[0] = initial
        for (i in 0 until n) {
            val nxt = mddStep(layerStarts, transitions, recordStride, path[i], state.assignment.intValue(seq[i]), i)
            if (nxt < 0) return
            path[i + 1] = nxt
        }
        if (accepting.none { it == path[n] }) return
        var emitted = 0
        var attempts = 0
        while (emitted < MDD_STRUCTURED_MOVE_CAP && attempts < MDD_STRUCTURED_MOVE_CAP * MDD_MOVE_ATTEMPT_STRIDE) {
            attempts++
            val i = state.rng.nextInt(n)
            val cur = state.assignment.intValue(seq[i])
            val from = path[i]
            val to = path[i + 1]
            val curWeight = mddRecordWeight(layerStarts, transitions, recordStride, from, cur, i)
            val d = state.problem.intDomains[seq[i]]
            var pick = -1L
            var seen = 0
            var p = layerStarts[i]
            val end = layerStarts[i + 1]
            while (p < end) {
                val sym = transitions[p + 1]
                val sameCost = recordStride < 4 || transitions[p + 3].toInt() == curWeight
                if (transitions[p].toInt() == from && transitions[p + 2].toInt() == to && sameCost &&
                    sym != cur && sym in d
                ) {
                    seen++
                    if (state.rng.nextInt(seen) == 0) pick = sym
                }
                p += recordStride
            }
            if (pick == -1L) continue
            sink.addChannelingIntSet(state, seq[i], pick)
            emitted++
        }
    }

    override fun seedFeasible(state: LocalSearchState, factorId: Int): Boolean {
        val n = seq.size
        val fwd = Array(n + 1) { BooleanArray(numStatesPerLayer[it]) }
        if (initial < fwd[0].size) fwd[0][initial] = true
        for (i in 0 until n) {
            var p = layerStarts[i]
            val end = layerStarts[i + 1]
            while (p < end) {
                val from = transitions[p].toInt()
                val sym = transitions[p + 1]
                val to = transitions[p + 2].toInt()
                if (from < fwd[i].size && fwd[i][from] && mddSymbolAllowed(state, seq, i, sym)) fwd[i + 1][to] = true
                p += recordStride
            }
        }
        var target = -1
        for (s in accepting) {
            if (s < fwd[n].size && fwd[n][s]) {
                target = s
                break
            }
        }
        if (target == -1) return false
        val chosen = LongArray(n)
        var totalWeight = 0L
        var t = target
        for (i in n - 1 downTo 0) {
            var fs = -1L
            var ff = -1
            var fw = 0
            var p = layerStarts[i]
            val end = layerStarts[i + 1]
            while (p < end) {
                val from = transitions[p].toInt()
                val sym = transitions[p + 1]
                if (transitions[p + 2].toInt() == t && from < fwd[i].size && fwd[i][from] && mddSymbolAllowed(
                        state,
                        seq,
                        i,
                        sym,
                    )
                ) {
                    fs = sym
                    ff = from
                    fw = if (recordStride == 4) transitions[p + 3].toInt() else 0
                    break
                }
                p += recordStride
            }
            if (fs == -1L) return false
            chosen[i] = fs
            totalWeight += fw
            t = ff
        }
        if (cost >= 0) {
            if (state.assumptions.isFrozenInt(cost)) {
                if (state.assignment.intValue(cost) != totalWeight) return false
            } else {
                if (totalWeight !in state.problem.intDomains[cost]) return false
            }
        }
        for (i in 0 until n) {
            if (!state.assumptions.isFrozenInt(seq[i])) state.assignment.setInt(seq[i], chosen[i])
        }
        if (cost >= 0 && !state.assumptions.isFrozenInt(cost)) state.assignment.setInt(cost, totalWeight)
        return true
    }
}

private const val MDD_STRUCTURED_MOVE_CAP: Int = 4
private const val MDD_MOVE_ATTEMPT_STRIDE: Int = 6

/** Walk the layered DAG along the current assignment (with one optional override). */
internal fun mddPathExists(
    state: LocalSearchState,
    seq: IntArray,
    layerStarts: IntArray,
    transitions: LongArray,
    recordStride: Int,
    initial: Int,
    accepting: IntArray,
    intVar: Int,
    override: Int,
): Boolean {
    var current = initial
    for (i in 0 until seq.size) {
        val symbol: Long = if (seq[i] == intVar) override.toLong() else state.assignment.intValue(seq[i])
        val start = layerStarts[i]
        val end = layerStarts[i + 1]
        var next = -1
        var p = start
        while (p < end) {
            if (transitions[p].toInt() == current && transitions[p + 1] == symbol) {
                next = transitions[p + 2].toInt()
                break
            }
            p += recordStride
        }
        if (next < 0) return false
        current = next
    }
    for (s in accepting) if (s == current) return true
    return false
}

/** Destination state of the transition from [from] on [symbol] at layer [i], or -1 if none. */
internal fun mddStep(
    layerStarts: IntArray,
    transitions: LongArray,
    recordStride: Int,
    from: Int,
    symbol: Long,
    i: Int,
): Int {
    var p = layerStarts[i]
    val end = layerStarts[i + 1]
    while (p < end) {
        if (transitions[p].toInt() == from && transitions[p + 1] == symbol) return transitions[p + 2].toInt()
        p += recordStride
    }
    return -1
}

/** Weight of the transition from [from] on [symbol] at layer [i] (0 for a plain MDD). */
internal fun mddRecordWeight(
    layerStarts: IntArray,
    transitions: LongArray,
    recordStride: Int,
    from: Int,
    symbol: Long,
    i: Int,
): Int {
    if (recordStride < 4) return 0
    var p = layerStarts[i]
    val end = layerStarts[i + 1]
    while (p < end) {
        if (transitions[p].toInt() == from && transitions[p + 1] == symbol) return transitions[p + 3].toInt()
        p += recordStride
    }
    return 0
}

/** Symbol [s] is usable at layer [i]. */
internal fun mddSymbolAllowed(state: LocalSearchState, seq: IntArray, i: Int, s: Long): Boolean {
    val v = seq[i]
    return if (state.assumptions.isFrozenInt(v)) {
        state.assignment.intValue(v) == s
    } else {
        s in state.problem.intDomains[v]
    }
}

/** Min symbol changes for a layer-by-layer path to an accepting state, where [getSym] is layer i's current symbol. */
internal fun mddAcceptDistance(
    seq: IntArray,
    numStatesPerLayer: IntArray,
    layerStarts: IntArray,
    transitions: LongArray,
    recordStride: Int,
    initial: Int,
    accepting: IntArray,
    getSym: (Int) -> Long,
): Int {
    val inf = seq.size + 1
    var dp = IntArray(numStatesPerLayer[0]) { inf }
    if (initial < dp.size) dp[initial] = 0
    for (i in 0 until seq.size) {
        val cur = getSym(i)
        val ndp = IntArray(numStatesPerLayer[i + 1]) { inf }
        var p = layerStarts[i]
        val end = layerStarts[i + 1]
        while (p < end) {
            val from = transitions[p].toInt()
            val base = dp[from]
            if (base < inf) {
                val cost = base + (if (transitions[p + 1] == cur) 0 else 1)
                val to = transitions[p + 2].toInt()
                if (cost < ndp[to]) ndp[to] = cost
            }
            p += recordStride
        }
        dp = ndp
    }
    var best = inf
    for (s in accepting) if (s < dp.size && dp[s] < best) best = dp[s]
    return best
}

/**
 * A minimum-change in-domain accepting layer path, as the symbol per layer, or null when none is
 * reachable within the domains. The domain-aware, traceback-carrying counterpart of
 * [mddAcceptDistance]: forward DP over allowed transitions with parent pointers, then a backtrack
 * from the cheapest accepting state.
 */
internal fun mddRepairPath(
    state: LocalSearchState,
    seq: IntArray,
    numStatesPerLayer: IntArray,
    layerStarts: IntArray,
    transitions: LongArray,
    recordStride: Int,
    initial: Int,
    accepting: IntArray,
): LongArray? {
    val n = seq.size
    val inf = n + 1
    val dp = Array(n + 1) { IntArray(numStatesPerLayer[it]) { inf } }
    val parentState = Array(n + 1) { IntArray(numStatesPerLayer[it]) { -1 } }
    val parentSymbol = Array(n + 1) { LongArray(numStatesPerLayer[it]) { -1L } }
    if (initial < dp[0].size) dp[0][initial] = 0
    for (i in 0 until n) {
        val cur = state.assignment.intValue(seq[i])
        val frozen = state.assumptions.isFrozenInt(seq[i])
        val d = state.problem.intDomains[seq[i]]
        var p = layerStarts[i]
        val end = layerStarts[i + 1]
        while (p < end) {
            val from = transitions[p].toInt()
            val sym = transitions[p + 1]
            val to = transitions[p + 2].toInt()
            val allowed = if (frozen) sym == cur else sym in d
            val base = dp[i][from]
            if (allowed && base < inf) {
                val cost = base + (if (sym == cur) 0 else 1)
                if (cost < dp[i + 1][to]) {
                    dp[i + 1][to] = cost
                    parentState[i + 1][to] = from
                    parentSymbol[i + 1][to] = sym
                }
            }
            p += recordStride
        }
    }
    var best = inf
    var bestState = -1
    for (s in accepting) {
        if (s < dp[n].size && dp[n][s] < best) {
            best = dp[n][s]
            bestState = s
        }
    }
    if (bestState == -1) return null
    val out = LongArray(n)
    var s = bestState
    for (i in n downTo 1) {
        out[i - 1] = parentSymbol[i][s]
        s = parentState[i][s]
    }
    return out
}

/** Maintained accept-distance DP for an MDD factor: forward and backward layers over the current
 *  assignment plus the resulting accept distance, so a single-symbol move recombines one layer in
 *  O(Q·Σ) instead of resweeping the whole DP. */
internal class MddLsState(val forward: Array<IntArray>, val backward: Array<IntArray>, var distance: Int)

/** `forward[i][q]` = min symbol changes over layers `0 until i` to reach state `q` from [initial],
 *  where [getSym] is layer i's current symbol. */
internal fun mddForwardLayers(
    n: Int,
    numStatesPerLayer: IntArray,
    layerStarts: IntArray,
    transitions: LongArray,
    recordStride: Int,
    initial: Int,
    getSym: (Int) -> Long,
): Array<IntArray> {
    val inf = n + 1
    val fwd = Array(n + 1) { IntArray(numStatesPerLayer[it]) { inf } }
    if (initial < fwd[0].size) fwd[0][initial] = 0
    for (i in 0 until n) {
        val cur = getSym(i)
        var p = layerStarts[i]
        val end = layerStarts[i + 1]
        while (p < end) {
            val from = transitions[p].toInt()
            val base = fwd[i][from]
            if (base < inf) {
                val cost = base + (if (transitions[p + 1] == cur) 0 else 1)
                val to = transitions[p + 2].toInt()
                if (cost < fwd[i + 1][to]) fwd[i + 1][to] = cost
            }
            p += recordStride
        }
    }
    return fwd
}

/** `backward[i][q]` = min symbol changes over layers `i until n` to drive state `q` to an accepting
 *  state, where [getSym] is layer i's current symbol. */
internal fun mddBackwardLayers(
    n: Int,
    numStatesPerLayer: IntArray,
    layerStarts: IntArray,
    transitions: LongArray,
    recordStride: Int,
    accepting: IntArray,
    getSym: (Int) -> Long,
): Array<IntArray> {
    val inf = n + 1
    val bwd = Array(n + 1) { IntArray(numStatesPerLayer[it]) { inf } }
    for (s in accepting) if (s < bwd[n].size) bwd[n][s] = 0
    for (i in n - 1 downTo 0) {
        val cur = getSym(i)
        var p = layerStarts[i]
        val end = layerStarts[i + 1]
        while (p < end) {
            val from = transitions[p].toInt()
            val to = transitions[p + 2].toInt()
            val c = (if (transitions[p + 1] == cur) 0 else 1) + bwd[i + 1][to]
            if (c < bwd[i][from]) bwd[i][from] = c
            p += recordStride
        }
    }
    return bwd
}
