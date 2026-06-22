package com.eignex.klause.solver.factor.table

import com.eignex.klause.solver.Invariant
import com.eignex.klause.solver.factor.compressViolation
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink

/** LS invariant for [Mdd]. Constructed by [Mdd.asInvariant]. */
internal class MddInvariant(
    private val seq: IntArray,
    private val numStatesPerLayer: IntArray,
    private val layerStarts: IntArray,
    private val transitions: IntArray,
    private val initial: Int,
    private val accepting: IntArray,
    private val recordStride: Int,
    private val cost: Int,
) : Invariant {

    override fun initialize(state: LocalSearchState, factorId: Int) {
        state.factorDegree[factorId] = violationDegree(state, factorId)
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean =
        !mddPathExists(state, seq, layerStarts, transitions, recordStride, initial, accepting, -1, 0)

    override fun violationDegree(state: LocalSearchState, factorId: Int): Int = compressViolation(
        mddAcceptDistance(seq, numStatesPerLayer, layerStarts, transitions, recordStride, initial, accepting) {
            state.assignment.intValue(seq[it])
        }.toLong(),
        state.violationSoftCap,
    )

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int {
        val after = compressViolation(
            mddAcceptDistance(seq, numStatesPerLayer, layerStarts, transitions, recordStride, initial, accepting) {
                val v = seq[it]
                if (v == intVar) newValue else state.assignment.intValue(v)
            }.toLong(),
            state.violationSoftCap,
        )
        return after - state.factorDegree[factorId]
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int {
        val newValue = state.assignment.intValue(intVar)
        if (newValue == oldValue) return 0
        val after = compressViolation(
            mddAcceptDistance(seq, numStatesPerLayer, layerStarts, transitions, recordStride, initial, accepting) {
                state.assignment.intValue(seq[it])
            }.toLong(),
            state.violationSoftCap,
        )
        return after - state.factorDegree[factorId]
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
                if (transitions[p] == path[i] && transitions[p + 1] == symbol) {
                    matchedDst = transitions[p + 2]
                    break
                }
                p += recordStride
            }
            if (matchedDst < 0) {
                val d = state.problem.intDomains[seq[i]]
                var q = start
                while (q < end) {
                    if (transitions[q] == path[i]) {
                        val altSym = transitions[q + 1]
                        if (altSym != symbol && altSym in d) sink.addChannelingIntSet(state, seq[i], altSym)
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
            if (transitions[p] == qPrev) {
                val sym = transitions[p + 1]
                val dst = transitions[p + 2]
                if (sym != curLast && sym in d && accepting.any { it == dst }) {
                    sink.addChannelingIntSet(state, seq[last], sym)
                }
            }
            p += recordStride
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
            var pick = -1
            var seen = 0
            var p = layerStarts[i]
            val end = layerStarts[i + 1]
            while (p < end) {
                val sym = transitions[p + 1]
                val sameCost = recordStride < 4 || transitions[p + 3] == curWeight
                if (transitions[p] == from && transitions[p + 2] == to && sameCost && sym != cur && sym in d) {
                    seen++
                    if (state.rng.nextInt(seen) == 0) pick = sym
                }
                p += recordStride
            }
            if (pick == -1) continue
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
                val from = transitions[p]
                val sym = transitions[p + 1]
                val to = transitions[p + 2]
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
        val chosen = IntArray(n)
        var totalWeight = 0L
        var t = target
        for (i in n - 1 downTo 0) {
            var fs = -1
            var ff = -1
            var fw = 0
            var p = layerStarts[i]
            val end = layerStarts[i + 1]
            while (p < end) {
                val from = transitions[p]
                val sym = transitions[p + 1]
                if (transitions[p + 2] == t && from < fwd[i].size && fwd[i][from] && mddSymbolAllowed(
                        state,
                        seq,
                        i,
                        sym,
                    )
                ) {
                    fs = sym
                    ff = from
                    fw = if (recordStride == 4) transitions[p + 3] else 0
                    break
                }
                p += recordStride
            }
            if (fs == -1) return false
            chosen[i] = fs
            totalWeight += fw
            t = ff
        }
        if (cost >= 0) {
            if (state.assumptions.isFrozenInt(cost)) {
                if (state.assignment.intValue(cost).toLong() != totalWeight) return false
            } else {
                if (totalWeight > Int.MAX_VALUE || totalWeight.toInt() !in state.problem.intDomains[cost]) return false
            }
        }
        for (i in 0 until n) {
            if (!state.assumptions.isFrozenInt(seq[i])) state.assignment.setInt(seq[i], chosen[i])
        }
        if (cost >= 0 && !state.assumptions.isFrozenInt(cost)) state.assignment.setInt(cost, totalWeight.toInt())
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
    transitions: IntArray,
    recordStride: Int,
    initial: Int,
    accepting: IntArray,
    intVar: Int,
    override: Int,
): Boolean {
    var current = initial
    for (i in 0 until seq.size) {
        val symbol = if (seq[i] == intVar) override else state.assignment.intValue(seq[i])
        val start = layerStarts[i]
        val end = layerStarts[i + 1]
        var next = -1
        var p = start
        while (p < end) {
            if (transitions[p] == current && transitions[p + 1] == symbol) {
                next = transitions[p + 2]
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
    transitions: IntArray,
    recordStride: Int,
    from: Int,
    symbol: Int,
    i: Int,
): Int {
    var p = layerStarts[i]
    val end = layerStarts[i + 1]
    while (p < end) {
        if (transitions[p] == from && transitions[p + 1] == symbol) return transitions[p + 2]
        p += recordStride
    }
    return -1
}

/** Weight of the transition from [from] on [symbol] at layer [i] (0 for a plain MDD). */
internal fun mddRecordWeight(
    layerStarts: IntArray,
    transitions: IntArray,
    recordStride: Int,
    from: Int,
    symbol: Int,
    i: Int,
): Int {
    if (recordStride < 4) return 0
    var p = layerStarts[i]
    val end = layerStarts[i + 1]
    while (p < end) {
        if (transitions[p] == from && transitions[p + 1] == symbol) return transitions[p + 3]
        p += recordStride
    }
    return 0
}

/** Symbol [s] is usable at layer [i]. */
internal fun mddSymbolAllowed(state: LocalSearchState, seq: IntArray, i: Int, s: Int): Boolean {
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
    transitions: IntArray,
    recordStride: Int,
    initial: Int,
    accepting: IntArray,
    getSym: (Int) -> Int,
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
            val from = transitions[p]
            val base = dp[from]
            if (base < inf) {
                val cost = base + (if (transitions[p + 1] == cur) 0 else 1)
                val to = transitions[p + 2]
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
