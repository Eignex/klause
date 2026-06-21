package com.eignex.klause.solver.factor.table

import com.eignex.klause.solver.Invariant
import com.eignex.klause.solver.factor.compressViolation
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.util.IntHashSet

/** LS invariant for [Regular]. Constructed by [Regular.asInvariant]. */
internal class RegularInvariant(
    override val boolVars: IntArray,
    override val intVars: IntArray,
    private val seq: IntArray,
    private val numStates: Int,
    private val alphabetSize: Int,
    private val transitions: IntArray,
    private val q0: Int,
    private val accepting: IntArray,
) : Invariant {

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean {
        val aset = buildAcceptingSet(accepting)
        return !regularAccepts(state, seq, q0, transitions, numStates, alphabetSize, aset)
    }

    override fun violationDegree(state: LocalSearchState, factorId: Int): Int {
        val aset = buildAcceptingSet(accepting)
        return compressViolation(
            regularAcceptDistance(seq, numStates, alphabetSize, transitions, q0, aset) {
                state.assignment.intValue(seq[it])
            }.toLong(),
            state.violationSoftCap,
        )
    }

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int {
        val aset = buildAcceptingSet(accepting)
        val before = regularAcceptDistance(seq, numStates, alphabetSize, transitions, q0, aset) {
            state.assignment.intValue(seq[it])
        }
        val after = regularAcceptDistance(seq, numStates, alphabetSize, transitions, q0, aset) {
            val v = seq[it]
            if (v == intVar) newValue else state.assignment.intValue(v)
        }
        return compressViolation(after.toLong(), state.violationSoftCap) -
            compressViolation(before.toLong(), state.violationSoftCap)
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int = 0

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        val aset = buildAcceptingSet(accepting)
        if (!regularAccepts(state, seq, q0, transitions, numStates, alphabetSize, aset)) {
            var q = q0
            for (i in seq.indices) {
                val s = state.assignment.intValue(seq[i])
                val next = regularDelta(transitions, numStates, alphabetSize, q, s)
                if (next == 0) {
                    val d = state.problem.intDomains[seq[i]]
                    d.forEach { sym ->
                        if (sym != s && regularDelta(transitions, numStates, alphabetSize, q, sym) != 0) {
                            sink.addChannelingIntSet(state, seq[i], sym)
                        }
                    }
                    return
                }
                q = next
            }
            if (q !in aset && seq.isNotEmpty()) {
                val last = seq.size - 1
                var qPrev = q0
                for (i in 0 until last) {
                    qPrev = regularDelta(
                        transitions,
                        numStates,
                        alphabetSize,
                        qPrev,
                        state.assignment.intValue(seq[i]),
                    )
                }
                val curLast = state.assignment.intValue(seq[last])
                val d = state.problem.intDomains[seq[last]]
                d.forEach { sym ->
                    val target = regularDelta(transitions, numStates, alphabetSize, qPrev, sym)
                    if (sym != curLast && target in aset) sink.addChannelingIntSet(state, seq[last], sym)
                }
            }
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
