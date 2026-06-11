package com.eignex.klause.solver.factor

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.solver.propagation.PropagationState

/**
 * Layered multi-valued decision diagram acceptance. The diagram has `n+1` state layers
 * (`n = seq.size`); layer `i` has `numStatesPerLayer[i]` states. [transitions] is a flat
 * sequence of `(srcState, value, dstState[, weight])` rows; [layerStarts] indexes into it
 * (layer i spans `layerStarts[i] until layerStarts[i+1]`).
 *
 * When `weights` is non-null, each transition has a 4th field (weight) and [cost] must be
 * provided — the sum of edge weights along the accepted path equals [cost].
 *
 * Propagation:
 *  - Forward sweep: state `s` at layer `i` is forward-reachable iff some forward-reachable
 *    state at layer `i-1` has a transition on a feasible (in-domain) symbol leading to `s`.
 *  - Backward sweep symmetric from accepting states.
 *  - Prune `seq[i]` values that have no transition between forward∩backward reachable states.
 *  - Fail if no forward-reachable state at layer `n` is accepting.
 *  - For cost variant: tighten [cost] bounds by min/max weighted-sum path through the
 *    forward-backward reachable lattice.
 */
class Mdd(
    /** Sequence variable ids, one per layer. */
    val seq: IntArray,
    /** Number of states in each layer (length `seq.size + 1`). */
    val numStatesPerLayer: IntArray,
    /** Prefix-sum index into [transitions] per layer. */
    val layerStarts: IntArray,
    /** Flat transition records; stride [recordStride]. */
    val transitions: IntArray,
    /** Start state. */
    val initial: Int,
    /** Accepting states at the final layer. */
    val accepting: IntArray,
    /** Ints per transition record: 3 for plain MDD, 4 for cost MDD. */
    val recordStride: Int, // 3 for plain MDD, 4 for cost MDD
    /** Cost variable id, or -1 for a plain (non-cost) MDD. */
    val cost: Int = -1,
) : Factor {

    init {
        require(seq.isNotEmpty()) { "Mdd: empty seq" }
        require(numStatesPerLayer.size == seq.size + 1) { "Mdd: numStatesPerLayer must be seq.size+1" }
        require(layerStarts.size == seq.size + 1) { "Mdd: layerStarts must be seq.size+1" }
        require(recordStride == 3 || recordStride == 4) { "Mdd: stride must be 3 or 4" }
        require(transitions.size % recordStride == 0) { "Mdd: transitions length not a multiple of stride" }
        if (recordStride == 4) require(cost >= 0) { "Mdd: cost-MDD requires cost var" }
    }

    override fun remap(boolMap: IntArray, intMap: IntArray): Factor = Mdd(
        seq.remapVars(intMap),
        numStatesPerLayer,
        layerStarts,
        transitions,
        initial,
        accepting,
        recordStride,
        if (cost >= 0) intMap[cost] else cost,
    )

    override val boolVars: IntArray = EmptyIntArray
    override val intVars: IntArray = if (cost >= 0) seq + intArrayOf(cost) else seq.copyOf()

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean = !pathExists(state, -1, 0)

    /** Graded violation: the minimum number of sequence positions whose symbol must change for
     *  a valid path through the layered DAG to reach an accepting state — an edit-distance over
     *  layers — compressed. `0` iff a path currently exists; saturates at `seq.size + 1` when no
     *  symbol assignment admits an accepting path. Gives CBLS a gradient toward a feasible path. */
    override fun violationDegree(state: LocalSearchState, factorId: Int): Int =
        compressViolation(acceptDistance { state.assignment.intValue(seq[it]) }.toLong(), state.violationSoftCap)

    /** Min symbol changes for a layer-by-layer path to an accepting state, where `getSym(i)` is
     *  layer `i`'s current symbol (a matching transition costs 0, any other costs 1). State ids
     *  are layer-local, sized by [numStatesPerLayer]. */
    private inline fun acceptDistance(getSym: (Int) -> Int): Int {
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

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int {
        val before = acceptDistance { state.assignment.intValue(seq[it]) }
        val after = acceptDistance {
            val v = seq[it]
            if (v == intVar) newValue else state.assignment.intValue(v)
        }
        return compressViolation(after.toLong(), state.violationSoftCap) -
            compressViolation(before.toLong(), state.violationSoftCap)
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int = 0

    /** Walk the layered DAG along the current assignment (with one optional override of
     *  [intVar] → [override]). Returns true iff the path lands in an accepting state. */
    private fun pathExists(state: LocalSearchState, intVar: Int, override: Int): Boolean {
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

    /** Repair by finding the first dead position in the assignment-path through the layered
     *  DAG; at that position, propose every in-domain symbol that has a valid transition
     *  from the live state (regardless of forward feasibility to an accepting state — the
     *  cheap heuristic; the engine will eventually find paths that lead to acceptance). */
    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        if (!isViolated(state, factorId)) return
        var current = initial
        for (i in 0 until seq.size) {
            val symbol = state.assignment.intValue(seq[i])
            val start = layerStarts[i]
            val end = layerStarts[i + 1]
            var matchedDst = -1
            var p = start
            while (p < end) {
                if (transitions[p] == current && transitions[p + 1] == symbol) {
                    matchedDst = transitions[p + 2]
                    break
                }
                p += recordStride
            }
            if (matchedDst < 0) {
                // Dead end at layer i. Propose any symbol with a transition from `current`.
                val d = state.problem.intDomains[seq[i]]
                var q = start
                while (q < end) {
                    if (transitions[q] == current) {
                        val altSym = transitions[q + 1]
                        if (altSym != symbol && altSym in d) sink.addChannelingIntSet(state, seq[i], altSym)
                    }
                    q += recordStride
                }
                return
            }
            current = matchedDst
        }
        // Path completed without accepting. Try last-position changes that reach accepting.
        if (seq.isNotEmpty()) {
            val last = seq.size - 1
            // Recompute state up to last - 1.
            var qPrev = initial
            for (i in 0 until last) {
                val symbol = state.assignment.intValue(seq[i])
                val start = layerStarts[i]
                val end = layerStarts[i + 1]
                var p = start
                while (p < end) {
                    if (transitions[p] == qPrev && transitions[p + 1] == symbol) {
                        qPrev = transitions[p + 2]
                        break
                    }
                    p += recordStride
                }
            }
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
    }

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        state.composeIntVarAtomAntecedents(intVars)

    /** Cached snapshot of seq domain refs at last successful propagate. When every seq
     *  variable's IntDomain reference is unchanged, the previous fixpoint still holds and
     *  the full sweep is skipped. Backtrack-safe via [PropagationState.SnapshottablePayload]:
     *  on push the engine clones the array, on pop the prior level's refs are restored. */
    private class MddState(val cachedSeq: Array<IntDomain?>, var cachedCost: IntDomain?) :
        PropagationState.SnapshottablePayload {
        override fun snapshotCopy(): MddState = MddState(cachedSeq.copyOf(), cachedCost)
    }

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        val n = seq.size
        // Incremental fast path: if nothing relevant has changed since the last fire, the
        // previous propagator pass already reached fixpoint and we can return immediately.
        val payload = (state.refPayload[factorId] as? MddState) ?: run {
            val fresh = MddState(arrayOfNulls(n), null)
            state.refPayload[factorId] = fresh
            fresh
        }
        var changed = false
        for (i in 0 until n) {
            if (payload.cachedSeq[i] !== state.intDomains[seq[i]]) {
                changed = true
                break
            }
        }
        if (!changed && cost >= 0 && payload.cachedCost !== state.intDomains[cost]) changed = true
        if (!changed && payload.cachedSeq[0] != null) return true
        val ant = state.composeIntVarAtomAntecedents(intVars)
        // Forward reachability: fwd[i] is a packed bitset over [0, numStatesPerLayer[i]).
        // Each layer stores `(numStates + 63) / 64` longs; bit s tests layer-s reachability.
        val fwd = Array(n + 1) { LongArray((numStatesPerLayer[it] + 63) ushr 6) }
        if (initial < 0 || initial >= numStatesPerLayer[0]) return false
        fwd[0][initial ushr 6] = fwd[0][initial ushr 6] or (1L shl (initial and 63))
        for (i in 0 until n) {
            val sDom = state.intDomains[seq[i]]
            val numNext = numStatesPerLayer[i + 1]
            val fwdI = fwd[i]
            val fwdN = fwd[i + 1]
            val k = layerStarts[i]
            val end = layerStarts[i + 1]
            var p = k
            while (p < end) {
                val src = transitions[p]
                val sym = transitions[p + 1]
                val dst = transitions[p + 2]
                if (src >= 0 && src < numStatesPerLayer[i] &&
                    ((fwdI[src ushr 6] ushr (src and 63)) and 1L) != 0L &&
                    sym in sDom.min..sDom.max &&
                    dst in 0 until numNext
                ) {
                    fwdN[dst ushr 6] = fwdN[dst ushr 6] or (1L shl (dst and 63))
                }
                p += recordStride
            }
        }
        // Check acceptance: any accepting state with its fwd-bit set.
        var anyAccepting = false
        for (s in accepting) {
            if (s in 0 until numStatesPerLayer[n] &&
                ((fwd[n][s ushr 6] ushr (s and 63)) and 1L) != 0L
            ) {
                anyAccepting = true
                break
            }
        }
        if (!anyAccepting) return false

        // Backward reachability.
        val bwd = Array(n + 1) { LongArray((numStatesPerLayer[it] + 63) ushr 6) }
        for (s in accepting) {
            if (s in 0 until numStatesPerLayer[n] &&
                ((fwd[n][s ushr 6] ushr (s and 63)) and 1L) != 0L
            ) {
                bwd[n][s ushr 6] = bwd[n][s ushr 6] or (1L shl (s and 63))
            }
        }
        for (i in n - 1 downTo 0) {
            val sDom = state.intDomains[seq[i]]
            val numI = numStatesPerLayer[i]
            val numN = numStatesPerLayer[i + 1]
            val bwdI = bwd[i]
            val bwdN = bwd[i + 1]
            val fwdI = fwd[i]
            val k = layerStarts[i]
            val end = layerStarts[i + 1]
            var p = k
            while (p < end) {
                val src = transitions[p]
                val sym = transitions[p + 1]
                val dst = transitions[p + 2]
                if (src in 0 until numI && dst in 0 until numN &&
                    ((bwdN[dst ushr 6] ushr (dst and 63)) and 1L) != 0L &&
                    ((fwdI[src ushr 6] ushr (src and 63)) and 1L) != 0L &&
                    sym in sDom.min..sDom.max
                ) {
                    bwdI[src ushr 6] = bwdI[src ushr 6] or (1L shl (src and 63))
                }
                p += recordStride
            }
        }
        // Prune seq[i] values that have no fwd∩bwd transition.
        for (i in 0 until n) {
            val sDom = state.intDomains[seq[i]]
            val span = sDom.max - sDom.min + 1
            val survives = LongArray((span + 63) ushr 6)
            val numI = numStatesPerLayer[i]
            val numN = numStatesPerLayer[i + 1]
            val fwdI = fwd[i]
            val bwdN = bwd[i + 1]
            val k = layerStarts[i]
            val end = layerStarts[i + 1]
            var p = k
            while (p < end) {
                val src = transitions[p]
                val sym = transitions[p + 1]
                val dst = transitions[p + 2]
                if (sym in sDom.min..sDom.max &&
                    src in 0 until numI &&
                    ((fwdI[src ushr 6] ushr (src and 63)) and 1L) != 0L &&
                    dst in 0 until numN &&
                    ((bwdN[dst ushr 6] ushr (dst and 63)) and 1L) != 0L
                ) {
                    val off = sym - sDom.min
                    survives[off ushr 6] = survives[off ushr 6] or (1L shl (off and 63))
                }
                p += recordStride
            }
            for (s in sDom.min..sDom.max) {
                val off = s - sDom.min
                if (((survives[off ushr 6] ushr (off and 63)) and 1L) == 0L) {
                    if (!state.excludeIntValue(seq[i], s, ant)) return false
                }
            }
        }

        if (cost >= 0) {
            // Compute min/max path cost over fwd∩bwd reachable graph.
            val inf = Long.MAX_VALUE / 4
            val minCost = Array(n + 1) { LongArray(numStatesPerLayer[it]) { inf } }
            val maxCost = Array(n + 1) { LongArray(numStatesPerLayer[it]) { -inf } }
            minCost[0][initial] = 0L
            maxCost[0][initial] = 0L
            for (i in 0 until n) {
                val sDom = state.intDomains[seq[i]]
                val numI = numStatesPerLayer[i]
                val numN = numStatesPerLayer[i + 1]
                val fwdI = fwd[i]
                val fwdN = fwd[i + 1]
                var p = layerStarts[i]
                val end = layerStarts[i + 1]
                while (p < end) {
                    val src = transitions[p]
                    val sym = transitions[p + 1]
                    val dst = transitions[p + 2]
                    val w = transitions[p + 3].toLong()
                    if (sym in sDom.min..sDom.max &&
                        src in 0 until numI &&
                        ((fwdI[src ushr 6] ushr (src and 63)) and 1L) != 0L &&
                        dst in 0 until numN &&
                        ((fwdN[dst ushr 6] ushr (dst and 63)) and 1L) != 0L
                    ) {
                        val nm = minCost[i][src] + w
                        if (nm < minCost[i + 1][dst]) minCost[i + 1][dst] = nm
                        val nM = maxCost[i][src] + w
                        if (nM > maxCost[i + 1][dst]) maxCost[i + 1][dst] = nM
                    }
                    p += recordStride
                }
            }
            var bestLo = inf
            var bestHi = -inf
            for (s in accepting) {
                if (s in 0 until numStatesPerLayer[n] &&
                    ((fwd[n][s ushr 6] ushr (s and 63)) and 1L) != 0L
                ) {
                    if (minCost[n][s] < bestLo) bestLo = minCost[n][s]
                    if (maxCost[n][s] > bestHi) bestHi = maxCost[n][s]
                }
            }
            if (bestLo == inf) return false
            // Cost var is Int-typed: if the min path cost exceeds Int.MAX_VALUE (or the max
            // path cost is below Int.MIN_VALUE), the constraint is unsatisfiable. Clamping
            // bestLo down to Int.MAX_VALUE would otherwise leave Int.MAX_VALUE in the domain
            // as a spurious feasible value.
            if (bestLo > Int.MAX_VALUE.toLong()) return false
            if (bestHi < Int.MIN_VALUE.toLong()) return false
            val loBound = if (bestLo < Int.MIN_VALUE.toLong()) Int.MIN_VALUE else bestLo.toInt()
            val hiBound = if (bestHi > Int.MAX_VALUE.toLong()) Int.MAX_VALUE else bestHi.toInt()
            if (!state.tightenIntMin(cost, loBound, ant)) return false
            if (!state.tightenIntMax(cost, hiBound, ant)) return false
        }
        // Record the post-propagation domain refs so the next fire can skip a redundant
        // sweep. Any pruning above will have produced fresh IntDomain refs in state.intDomains;
        // capture them after all tightening so the fast path only fires on a real no-op.
        for (i in 0 until n) payload.cachedSeq[i] = state.intDomains[seq[i]]
        if (cost >= 0) payload.cachedCost = state.intDomains[cost]
        return true
    }
}
