package com.eignex.klause.solver.factor.table

import com.eignex.klause.solver.Invariant
import com.eignex.klause.solver.factor.compressViolation
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.util.IntHashSet

/** LS invariant for [Regular]. Constructed by [Regular.asInvariant]. */
internal class RegularInvariant(
    private val seq: IntArray,
    private val numStates: Int,
    private val alphabetSize: Int,
    private val transitions: IntArray,
    private val q0: Int,
    private val accepting: IntArray,
) : Invariant {

    private val acceptingSet: IntHashSet = buildAcceptingSet(accepting)

    /** Positions in [seq] each variable occupies — usually one, so a single-symbol move recombines a
     *  single DP column. A variable repeated across positions falls back to a full recompute. */
    private val positionsByVar: Map<Int, IntArray> = run {
        val tmp = HashMap<Int, MutableList<Int>>()
        for (i in seq.indices) tmp.getOrPut(seq[i]) { mutableListOf() }.add(i)
        tmp.mapValues { it.value.toIntArray() }
    }

    override fun initialize(state: LocalSearchState, factorId: Int) {
        val st = buildState(state)
        state.refPayload[factorId] = st
        state.factorDegree[factorId] = compressViolation(st.distance.toLong(), state.violationSoftCap)
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean =
        !regularAccepts(state, seq, q0, transitions, numStates, alphabetSize, acceptingSet)

    override fun violationDegree(state: LocalSearchState, factorId: Int): Int {
        val st = state.refPayload[factorId] as? RegularLsState ?: return fullDegree(state)
        return compressViolation(st.distance.toLong(), state.violationSoftCap)
    }

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int {
        val st = state.refPayload[factorId] as RegularLsState
        val newDist = distanceWith(state, st, intVar, newValue)
        return compressViolation(newDist.toLong(), state.violationSoftCap) - state.factorDegree[factorId]
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int {
        if (state.assignment.intValue(intVar) == oldValue) return 0
        // Apply is once per accepted move; rebuild both layers (O(n·Q·Σ), the order the old full
        // recompute already cost) so the per-candidate delta stays O(Q·Σ).
        val before = state.factorDegree[factorId]
        val rebuilt = buildState(state)
        state.refPayload[factorId] = rebuilt
        return compressViolation(rebuilt.distance.toLong(), state.violationSoftCap) - before
    }

    private fun fullDegree(state: LocalSearchState): Int = compressViolation(
        regularAcceptDistance(seq, numStates, alphabetSize, transitions, q0, acceptingSet) {
            state.assignment.intValue(seq[it])
        }.toLong(),
        state.violationSoftCap,
    )

    private fun buildState(state: LocalSearchState): RegularLsState {
        val getSym = { i: Int -> state.assignment.intValue(seq[i]) }
        val forward = regularForwardLayers(seq.size, numStates, alphabetSize, transitions, q0, getSym)
        val backward = regularBackwardLayers(seq.size, numStates, alphabetSize, transitions, acceptingSet, getSym)
        return RegularLsState(forward, backward, acceptingDistance(forward))
    }

    /** Accept distance with [intVar] set to [newValue]. A single-position variable recombines its DP
     *  column in O(Q·Σ); a repeated variable falls back to a full recompute. */
    private fun distanceWith(state: LocalSearchState, st: RegularLsState, intVar: Int, newValue: Int): Int {
        val positions = positionsByVar[intVar]
        if (positions == null || positions.size != 1) {
            return regularAcceptDistance(seq, numStates, alphabetSize, transitions, q0, acceptingSet) {
                if (seq[it] == intVar) newValue else state.assignment.intValue(seq[it])
            }
        }
        val p = positions[0]
        val inf = seq.size + 1
        var best = inf
        for (q in 1..numStates) {
            val fq = st.forward[p][q]
            if (fq >= inf) continue
            for (s in 1..alphabetSize) {
                val nq = regularDelta(transitions, numStates, alphabetSize, q, s)
                if (nq == 0) continue
                val bq = st.backward[p + 1][nq]
                if (bq >= inf) continue
                val cand = fq + (if (s == newValue) 0 else 1) + bq
                if (cand < best) best = cand
            }
        }
        return best
    }

    private fun acceptingDistance(forward: Array<IntArray>): Int {
        val n = seq.size
        var best = n + 1
        for (q in accepting) if (q in 1..numStates && forward[n][q] < best) best = forward[n][q]
        return best
    }

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        if (seq.isEmpty() || regularAccepts(state, seq, q0, transitions, numStates, alphabetSize, acceptingSet)) return
        val path = IntArray(seq.size + 1)
        path[0] = q0
        for (i in seq.indices) {
            val s = state.assignment.intValue(seq[i])
            val next = regularDelta(transitions, numStates, alphabetSize, path[i], s)
            if (next == 0) {
                val d = state.problem.intDomains[seq[i]]
                d.forEach { sym ->
                    if (sym != s && regularDelta(transitions, numStates, alphabetSize, path[i], sym) != 0) {
                        sink.addChannelingIntSet(state, seq[i], sym)
                    }
                }
                return
            }
            path[i + 1] = next
        }
        if (path[seq.size] !in acceptingSet) {
            val last = seq.size - 1
            val curLast = state.assignment.intValue(seq[last])
            val d = state.problem.intDomains[seq[last]]
            d.forEach { sym ->
                val target = regularDelta(transitions, numStates, alphabetSize, path[last], sym)
                if (sym != curLast && target in acceptingSet) sink.addChannelingIntSet(state, seq[last], sym)
            }
        }
    }

    override fun proposeExtendedRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        if (seq.isEmpty() || regularAccepts(state, seq, q0, transitions, numStates, alphabetSize, acceptingSet)) return
        // DP-optimal repair: a minimum-change in-domain accepting run. Each position where it differs
        // from the current symbol is a move that strictly reduces the accept distance, so the search
        // can walk straight to feasibility instead of chasing one dead-state fix at a time.
        val target = regularRepairPath(state, seq, numStates, alphabetSize, transitions, q0, acceptingSet) ?: return
        for (i in seq.indices) {
            if (target[i] != state.assignment.intValue(seq[i])) sink.addChannelingIntSet(state, seq[i], target[i])
        }
    }

    override val providesImplicitNeighbourhood: Boolean get() = true

    override fun proposeStructuredMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        val n = seq.size
        val path = IntArray(n + 1)
        path[0] = q0
        for (i in 0 until n) {
            path[i + 1] = regularDelta(transitions, numStates, alphabetSize, path[i], state.assignment.intValue(seq[i]))
            if (path[i + 1] == 0) return
        }
        var emitted = 0
        var attempts = 0
        while (emitted < REGULAR_STRUCTURED_MOVE_CAP &&
            attempts < REGULAR_STRUCTURED_MOVE_CAP * REGULAR_MOVE_ATTEMPT_STRIDE
        ) {
            attempts++
            val i = state.rng.nextInt(n)
            val cur = state.assignment.intValue(seq[i])
            val q = path[i]
            val nq = path[i + 1]
            val d = state.problem.intDomains[seq[i]]
            var pick = -1
            var seen = 0
            for (s in 1..alphabetSize) {
                if (s == cur || s !in d || regularDelta(transitions, numStates, alphabetSize, q, s) != nq) continue
                seen++
                if (state.rng.nextInt(seen) == 0) pick = s
            }
            if (pick == -1) continue
            sink.addChannelingIntSet(state, seq[i], pick)
            emitted++
        }
    }

    override fun seedFeasible(state: LocalSearchState, factorId: Int): Boolean {
        val n = seq.size
        val fwd = Array(n + 1) { BooleanArray(numStates + 1) }
        fwd[0][q0] = true
        for (i in 0 until n) {
            for (q in 1..numStates) {
                if (!fwd[i][q]) continue
                for (s in 1..alphabetSize) {
                    if (!regularSymbolAllowed(state, seq, i, s)) continue
                    val nq = regularDelta(transitions, numStates, alphabetSize, q, s)
                    if (nq != 0) fwd[i + 1][nq] = true
                }
            }
        }
        var target = -1
        for (q in accepting) {
            if (q in 1..numStates && fwd[n][q]) {
                target = q
                break
            }
        }
        if (target == -1) return false
        val chosen = IntArray(n)
        var t = target
        for (i in n - 1 downTo 0) {
            var fq = -1
            var fs = -1
            outer@ for (q in 1..numStates) {
                if (!fwd[i][q]) continue
                for (s in 1..alphabetSize) {
                    if (!regularSymbolAllowed(state, seq, i, s)) continue
                    if (regularDelta(transitions, numStates, alphabetSize, q, s) == t) {
                        fq = q
                        fs = s
                        break@outer
                    }
                }
            }
            if (fq == -1) return false
            chosen[i] = fs
            t = fq
        }
        for (i in 0 until n) {
            if (!state.assumptions.isFrozenInt(seq[i])) state.assignment.setInt(seq[i], chosen[i])
        }
        return true
    }
}

private const val REGULAR_STRUCTURED_MOVE_CAP: Int = 4
private const val REGULAR_MOVE_ATTEMPT_STRIDE: Int = 6

internal fun buildAcceptingSet(accepting: IntArray): IntHashSet {
    val s = IntHashSet(accepting.size)
    for (q in accepting) s.add(q)
    return s
}

/** Look up `δ(state, symbol)` with 1-based addressing. Returns 0 for the dead state. */
internal fun regularDelta(transitions: IntArray, numStates: Int, alphabetSize: Int, stateQ: Int, symbol: Int): Int {
    if (stateQ < 1 || stateQ > numStates) return 0
    if (symbol < 1 || symbol > alphabetSize) return 0
    return transitions[(stateQ - 1) * alphabetSize + (symbol - 1)]
}

internal fun regularAccepts(
    state: LocalSearchState,
    seq: IntArray,
    q0: Int,
    transitions: IntArray,
    numStates: Int,
    alphabetSize: Int,
    acceptingSet: IntHashSet,
): Boolean {
    var q = q0
    for (i in seq.indices) {
        q = regularDelta(transitions, numStates, alphabetSize, q, state.assignment.intValue(seq[i]))
        if (q == 0) return false
    }
    return q in acceptingSet
}

/** Symbol [s] is usable at position [i]. */
internal fun regularSymbolAllowed(state: LocalSearchState, seq: IntArray, i: Int, s: Int): Boolean {
    val v = seq[i]
    return if (state.assumptions.isFrozenInt(v)) {
        state.assignment.intValue(v) == s
    } else {
        s in state.problem.intDomains[v]
    }
}

/** Min symbol changes to reach an accepting run, where [getSym] is position i's current symbol. */
internal fun regularAcceptDistance(
    seq: IntArray,
    numStates: Int,
    alphabetSize: Int,
    transitions: IntArray,
    q0: Int,
    acceptingSet: IntHashSet,
    getSym: (Int) -> Int,
): Int {
    val inf = seq.size + 1
    var dp = IntArray(numStates + 1) { inf }
    dp[q0] = 0
    for (i in seq.indices) {
        val cur = getSym(i)
        val ndp = IntArray(numStates + 1) { inf }
        for (q in 1..numStates) {
            val base = dp[q]
            if (base >= inf) continue
            for (sym in 1..alphabetSize) {
                val nq = regularDelta(transitions, numStates, alphabetSize, q, sym)
                if (nq == 0) continue
                val cost = base + (if (sym == cur) 0 else 1)
                if (cost < ndp[nq]) ndp[nq] = cost
            }
        }
        dp = ndp
    }
    var best = inf
    for (q in 1..numStates) if (q in acceptingSet && dp[q] < best) best = dp[q]
    return best
}

/**
 * A minimum-change accepting run using only in-domain symbols, returned as the symbol per position,
 * or null when no accepting run is reachable within the domains. The domain-aware,
 * traceback-carrying counterpart of [regularAcceptDistance]: forward DP over allowed symbols with
 * parent pointers, then a backtrack from the cheapest accepting state.
 */
internal fun regularRepairPath(
    state: LocalSearchState,
    seq: IntArray,
    numStates: Int,
    alphabetSize: Int,
    transitions: IntArray,
    q0: Int,
    acceptingSet: IntHashSet,
): IntArray? {
    val n = seq.size
    val inf = n + 1
    val dp = Array(n + 1) { IntArray(numStates + 1) { inf } }
    val parentState = Array(n + 1) { IntArray(numStates + 1) { -1 } }
    val parentSymbol = Array(n + 1) { IntArray(numStates + 1) { -1 } }
    dp[0][q0] = 0
    for (i in 0 until n) {
        val cur = state.assignment.intValue(seq[i])
        for (q in 1..numStates) {
            val base = dp[i][q]
            if (base >= inf) continue
            for (s in 1..alphabetSize) {
                if (!regularSymbolAllowed(state, seq, i, s)) continue
                val nq = regularDelta(transitions, numStates, alphabetSize, q, s)
                if (nq == 0) continue
                val cost = base + if (s == cur) 0 else 1
                if (cost < dp[i + 1][nq]) {
                    dp[i + 1][nq] = cost
                    parentState[i + 1][nq] = q
                    parentSymbol[i + 1][nq] = s
                }
            }
        }
    }
    var best = inf
    var bestQ = -1
    for (q in 1..numStates) {
        if (q in acceptingSet && dp[n][q] < best) {
            best = dp[n][q]
            bestQ = q
        }
    }
    if (bestQ == -1) return null
    val out = IntArray(n)
    var q = bestQ
    for (i in n downTo 1) {
        out[i - 1] = parentSymbol[i][q]
        q = parentState[i][q]
    }
    return out
}

/** Maintained accept-distance DP for a Regular factor: forward and backward layers over the current
 *  assignment plus the resulting accept distance, so a single-symbol move recombines one column in
 *  O(Q·Σ) instead of resweeping the whole DP. */
internal class RegularLsState(val forward: Array<IntArray>, val backward: Array<IntArray>, var distance: Int)

/** `forward[i][q]` = min symbol changes over positions `0 until i` to reach state `q` from `q0`,
 *  where [getSym] is position i's current symbol. */
internal fun regularForwardLayers(
    n: Int,
    numStates: Int,
    alphabetSize: Int,
    transitions: IntArray,
    q0: Int,
    getSym: (Int) -> Int,
): Array<IntArray> {
    val inf = n + 1
    val fwd = Array(n + 1) { IntArray(numStates + 1) { inf } }
    if (q0 in 1..numStates) fwd[0][q0] = 0
    for (i in 0 until n) {
        val cur = getSym(i)
        for (q in 1..numStates) {
            val base = fwd[i][q]
            if (base >= inf) continue
            for (s in 1..alphabetSize) {
                val nq = regularDelta(transitions, numStates, alphabetSize, q, s)
                if (nq == 0) continue
                val cost = base + (if (s == cur) 0 else 1)
                if (cost < fwd[i + 1][nq]) fwd[i + 1][nq] = cost
            }
        }
    }
    return fwd
}

/** `backward[i][q]` = min symbol changes over positions `i until n` to drive state `q` to an
 *  accepting state, where [getSym] is position i's current symbol. */
internal fun regularBackwardLayers(
    n: Int,
    numStates: Int,
    alphabetSize: Int,
    transitions: IntArray,
    acceptingSet: IntHashSet,
    getSym: (Int) -> Int,
): Array<IntArray> {
    val inf = n + 1
    val bwd = Array(n + 1) { IntArray(numStates + 1) { inf } }
    for (q in 1..numStates) if (q in acceptingSet) bwd[n][q] = 0
    for (i in n - 1 downTo 0) {
        val cur = getSym(i)
        for (q in 1..numStates) {
            var best = inf
            for (s in 1..alphabetSize) {
                val nq = regularDelta(transitions, numStates, alphabetSize, q, s)
                if (nq == 0) continue
                val c = (if (s == cur) 0 else 1) + bwd[i + 1][nq]
                if (c < best) best = c
            }
            bwd[i][q] = best
        }
    }
    return bwd
}
