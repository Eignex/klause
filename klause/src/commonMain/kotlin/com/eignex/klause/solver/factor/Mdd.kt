package com.eignex.klause.solver.factor

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.localsearch.LocalSearchFactor
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.propagation.PropagationState

/**
 * Layered multi-valued decision diagram acceptance. The diagram has `n+1` state layers
 * (`n = seq.size`); layer `i` has `numStatesPerLayer[i]` states. [transitions] is a flat
 * sequence of `(srcState, value, dstState[, weight])` rows; [layerStarts] indexes into it
 * (layer i spans `layerStarts[i] until layerStarts[i+1]`).
 *
 * When [weights] is non-null, each transition has a 4th field (weight) and [cost] must be
 * provided — the sum of edge weights along the accepted path equals [cost].
 *
 * Propagation:
 *  - Forward sweep: state `s` at layer `i` is forward-reachable iff some forward-reachable
 *    state at layer `i-1` has a transition on a feasible (in-domain) symbol leading to `s`.
 *  - Backward sweep symmetric from accepting states.
 *  - Prune seq[i] values that have no transition between forward∩backward reachable states.
 *  - Fail if no forward-reachable state at layer `n` is accepting.
 *  - For cost variant: tighten [cost] bounds by min/max weighted-sum path through the
 *    forward-backward reachable lattice.
 */
class Mdd(
    val seq: IntArray,
    val numStatesPerLayer: IntArray,
    val layerStarts: IntArray,
    val transitions: IntArray,
    val initial: Int,
    val accepting: IntArray,
    val recordStride: Int,  // 3 for plain MDD, 4 for cost MDD
    val cost: Int = -1,
) : LocalSearchFactor {

    init {
        require(seq.isNotEmpty()) { "Mdd: empty seq" }
        require(numStatesPerLayer.size == seq.size + 1) { "Mdd: numStatesPerLayer must be seq.size+1" }
        require(layerStarts.size == seq.size + 1) { "Mdd: layerStarts must be seq.size+1" }
        require(recordStride == 3 || recordStride == 4) { "Mdd: stride must be 3 or 4" }
        require(transitions.size % recordStride == 0) { "Mdd: transitions length not a multiple of stride" }
        if (recordStride == 4) require(cost >= 0) { "Mdd: cost-MDD requires cost var" }
    }

    override val boolVars: IntArray = EmptyIntArray
    override val intVars: IntArray = if (cost >= 0) seq + intArrayOf(cost) else seq.copyOf()

    private val acceptingSet: HashSet<Int> = accepting.toHashSet()

    override fun initialize(state: LocalSearchState, factorId: Int) {}
    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean = false
    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int = 0
    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int = 0

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        state.composeIntVarAtomAntecedents(intVars)

    /** Cached snapshot of seq domain refs at last successful propagate. When every seq
     *  variable's IntDomain reference is unchanged, the previous fixpoint still holds and
     *  the full sweep is skipped. Backtrack-safe via [PropagationState.SnapshottablePayload]:
     *  on push the engine clones the array, on pop the prior level's refs are restored. */
    private class MddState(
        val cachedSeq: Array<IntDomain?>,
        var cachedCost: IntDomain?,
    ) : PropagationState.SnapshottablePayload {
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
        for (i in 0 until n) if (payload.cachedSeq[i] !== state.intDomains[seq[i]]) { changed = true; break }
        if (!changed && cost >= 0 && payload.cachedCost !== state.intDomains[cost]) changed = true
        if (!changed && payload.cachedSeq[0] != null) return true
        val ant = state.composeIntVarAtomAntecedents(intVars)
        // Forward reachability: fwd[i] = set of reachable states at layer i.
        val fwd = Array(n + 1) { BooleanArray(numStatesPerLayer[it]) }
        if (initial < 0 || initial >= numStatesPerLayer[0]) return false
        fwd[0][initial] = true
        for (i in 0 until n) {
            val sDom = state.intDomains[seq[i]]
            val k = layerStarts[i]
            val end = layerStarts[i + 1]
            var p = k
            while (p < end) {
                val src = transitions[p]
                val sym = transitions[p + 1]
                val dst = transitions[p + 2]
                if (src in fwd[i].indices && fwd[i][src] && sym in sDom.min..sDom.max && dst in fwd[i + 1].indices) {
                    fwd[i + 1][dst] = true
                }
                p += recordStride
            }
        }
        // Check acceptance.
        var anyAccepting = false
        for (s in fwd[n].indices) if (fwd[n][s] && s in acceptingSet) { anyAccepting = true; break }
        if (!anyAccepting) return false

        // Backward reachability.
        val bwd = Array(n + 1) { BooleanArray(numStatesPerLayer[it]) }
        for (s in bwd[n].indices) if (s in acceptingSet && fwd[n][s]) bwd[n][s] = true
        for (i in n - 1 downTo 0) {
            val sDom = state.intDomains[seq[i]]
            val k = layerStarts[i]
            val end = layerStarts[i + 1]
            var p = k
            while (p < end) {
                val src = transitions[p]
                val sym = transitions[p + 1]
                val dst = transitions[p + 2]
                if (src in bwd[i].indices && dst in bwd[i + 1].indices &&
                    bwd[i + 1][dst] && fwd[i][src] && sym in sDom.min..sDom.max) {
                    bwd[i][src] = true
                }
                p += recordStride
            }
        }
        // Prune seq[i] values that have no fwd∩bwd transition.
        for (i in 0 until n) {
            val sDom = state.intDomains[seq[i]]
            // For each value in domain, check if any transition (src,val,dst) has
            // fwd[i][src] ∧ bwd[i+1][dst].
            val survives = BooleanArray(sDom.max - sDom.min + 1)
            val k = layerStarts[i]
            val end = layerStarts[i + 1]
            var p = k
            while (p < end) {
                val src = transitions[p]
                val sym = transitions[p + 1]
                val dst = transitions[p + 2]
                if (sym in sDom.min..sDom.max
                    && src in fwd[i].indices && fwd[i][src]
                    && dst in bwd[i + 1].indices && bwd[i + 1][dst]) {
                    survives[sym - sDom.min] = true
                }
                p += recordStride
            }
            for (s in sDom.min..sDom.max) {
                if (!survives[s - sDom.min]) {
                    if (!state.excludeIntValue(seq[i], s, ant)) return false
                }
            }
        }

        if (cost >= 0) {
            // Compute min/max path cost over fwd∩bwd reachable graph.
            val INF = Long.MAX_VALUE / 4
            val minCost = Array(n + 1) { LongArray(numStatesPerLayer[it]) { INF } }
            val maxCost = Array(n + 1) { LongArray(numStatesPerLayer[it]) { -INF } }
            minCost[0][initial] = 0L
            maxCost[0][initial] = 0L
            for (i in 0 until n) {
                val sDom = state.intDomains[seq[i]]
                var p = layerStarts[i]
                val end = layerStarts[i + 1]
                while (p < end) {
                    val src = transitions[p]
                    val sym = transitions[p + 1]
                    val dst = transitions[p + 2]
                    val w = transitions[p + 3].toLong()
                    if (sym in sDom.min..sDom.max
                        && src in fwd[i].indices && fwd[i][src]
                        && dst in fwd[i + 1].indices && fwd[i + 1][dst]) {
                        val nm = minCost[i][src] + w
                        if (nm < minCost[i + 1][dst]) minCost[i + 1][dst] = nm
                        val nM = maxCost[i][src] + w
                        if (nM > maxCost[i + 1][dst]) maxCost[i + 1][dst] = nM
                    }
                    p += recordStride
                }
            }
            var bestLo = INF
            var bestHi = -INF
            for (s in fwd[n].indices) {
                if (s in acceptingSet && fwd[n][s]) {
                    if (minCost[n][s] < bestLo) bestLo = minCost[n][s]
                    if (maxCost[n][s] > bestHi) bestHi = maxCost[n][s]
                }
            }
            if (bestLo == INF) return false
            if (!state.tightenIntMin(cost, bestLo.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), ant)) return false
            if (!state.tightenIntMax(cost, bestHi.coerceAtLeast(Int.MIN_VALUE.toLong()).toInt(), ant)) return false
        }
        // Record the post-propagation domain refs so the next fire can skip a redundant
        // sweep. Any pruning above will have produced fresh IntDomain refs in state.intDomains;
        // capture them after all tightening so the fast path only fires on a real no-op.
        for (i in 0 until n) payload.cachedSeq[i] = state.intDomains[seq[i]]
        if (cost >= 0) payload.cachedCost = state.intDomains[cost]
        return true
    }
}
