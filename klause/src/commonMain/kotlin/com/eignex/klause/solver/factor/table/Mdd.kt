package com.eignex.klause.solver.factor.table

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.factor.compressViolation
import com.eignex.klause.solver.factor.remapVars
import com.eignex.klause.solver.factor.table.internals.MddIncrementalState
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.solver.propagation.IntEvent
import com.eignex.klause.solver.propagation.PropagationState

/**
 * Layered multi-valued decision diagram acceptance. The diagram has `n+1` state layers
 * (`n = seq.size`); layer `i` has `numStatesPerLayer(i)` states. [transitions] is a flat
 * sequence of `(srcState, value, dstState[, weight])` rows; [layerStarts] indexes into it
 * (layer i spans `layerStarts(i) until layerStarts(i+1)`).
 *
 * When `weights` is non-null, each transition has a 4th field (weight) and [cost] must be
 * provided — the sum of edge weights along the accepted path equals [cost].
 *
 * Propagation:
 *  - Forward sweep: state `s` at layer `i` is forward-reachable iff some forward-reachable
 *    state at layer `i-1` has a transition on a feasible (in-domain) symbol leading to `s`.
 *  - Backward sweep symmetric from accepting states.
 *  - Prune `seq(i)` values that have no transition between forward∩backward reachable states.
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
    val recordStride: Int,
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

    /** Position-faithful (layer i matters): keeps the sequence vars in order and folds in the whole
     *  diagram — per-layer state counts, layer offsets, the transition records, the initial and
     *  accepting states, the record stride, and the cost var (#531). */
    override fun structuralKey(): String = "mdd:$initial:$recordStride:$cost:${numStatesPerLayer.joinToString(",")}:" +
        "${layerStarts.joinToString(",")}:${transitions.joinToString(",")}:" +
        "${accepting.joinToString(",")}:${seq.joinToString(",")}"

    /** Symbol relabeling (#536): each transition record is `(fromState, symbol, toState[, cost])`, so a
     *  value permutation maps the symbol field of every record. Sound — the `seq` values are the
     *  symbols and there is no positional-variable/constant coupling (unlike Element). No bijection
     *  check is needed: records carry the symbol explicitly, so any map yields a valid diagram and the
     *  verification's key comparison decides whether the relabeling is actually a symmetry. */
    override fun remapValues(valueMap: (Int) -> Int): Factor {
        val newTransitions = transitions.copyOf()
        var p = 0
        while (p < newTransitions.size) {
            newTransitions[p + 1] = valueMap(newTransitions[p + 1])
            p += recordStride
        }
        return Mdd(seq, numStatesPerLayer, layerStarts, newTransitions, initial, accepting, recordStride, cost)
    }

    override val boolVars: IntArray = EmptyIntArray
    override val intVars: IntArray = if (cost >= 0) seq + intArrayOf(cost) else seq.copyOf()

    /** Advisor subscription (#623): the layered reachability sweep reads each sequence variable's
     *  bounds (`sym in min..max`), not interior holes, so it wakes on bound moves only — interior
     *  [IntEvent.VALUE_REMOVED] carves cannot change the reachability bitsets. Consumes the dirty-
     *  variable delta (#624); the incremental propagator ([MddIncrementalState]) recomputes only the
     *  layers a changed position reaches. */
    override val initialIntEventWatches: IntArray = IntEvent.boundEventWatches(intVars)

    override val consumesIntEventDelta: Boolean = true

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

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        val inc = (state.refPayload[factorId] as? MddIncrementalState) ?: run {
            val fresh = MddIncrementalState(
                state, seq, numStatesPerLayer, layerStarts, transitions, initial, accepting, recordStride, cost,
            )
            state.refPayload[factorId] = fresh
            fresh
        }
        return inc.propagate(state, factorId)
    }

    override val providesImplicitNeighbourhood: Boolean get() = true

    /** Feasibility-preserving neighbourhood: at a layer `i`, replace its symbol with another
     *  in-domain symbol whose transition has the *same* source, destination, and (for a cost-MDD)
     *  weight. The path through the diagram — and therefore acceptance and the path cost — is
     *  unchanged, only the surface symbol differs. Only meaningful on an accepted assignment. */
    override fun proposeStructuredMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        val n = seq.size
        val path = IntArray(n + 1)
        path[0] = initial
        for (i in 0 until n) {
            val nxt = step(path[i], state.assignment.intValue(seq[i]), i)
            if (nxt < 0) return // not on an accepting path — nothing structure-preserving to offer.
            path[i + 1] = nxt
        }
        if (accepting.none { it == path[n] }) return
        var emitted = 0
        var attempts = 0
        while (emitted < STRUCTURED_MOVE_CAP && attempts < STRUCTURED_MOVE_CAP * MOVE_ATTEMPT_STRIDE) {
            attempts++
            val i = state.rng.nextInt(n)
            val cur = state.assignment.intValue(seq[i])
            val from = path[i]
            val to = path[i + 1]
            val curWeight = recordWeight(from, cur, i)
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

    /** Feasible init: reconstruct an in-domain accepting path through the layered diagram by
     *  forward reachability (per-layer symbols restricted to the variable's domain, or the pinned
     *  value for a frozen var). For a cost-MDD the realised path weight is written to the cost var.
     *  Returns false — leaving the random assignment — when no in-domain accepting path exists or
     *  the cost var can't take the path weight. */
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
                if (from < fwd[i].size && fwd[i][from] && symbolAllowed(state, i, sym)) fwd[i + 1][to] = true
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
                if (transitions[p + 2] == t && from < fwd[i].size && fwd[i][from] && symbolAllowed(state, i, sym)) {
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

    /** Destination state of the transition from [from] on [symbol] at layer [i], or -1 if none. */
    private fun step(from: Int, symbol: Int, i: Int): Int {
        var p = layerStarts[i]
        val end = layerStarts[i + 1]
        while (p < end) {
            if (transitions[p] == from && transitions[p + 1] == symbol) return transitions[p + 2]
            p += recordStride
        }
        return -1
    }

    /** Weight of the transition from [from] on [symbol] at layer [i] (0 for a plain MDD). */
    private fun recordWeight(from: Int, symbol: Int, i: Int): Int {
        if (recordStride < 4) return 0
        var p = layerStarts[i]
        val end = layerStarts[i + 1]
        while (p < end) {
            if (transitions[p] == from && transitions[p + 1] == symbol) return transitions[p + 3]
            p += recordStride
        }
        return 0
    }

    /** Symbol [s] is usable at layer [i]: the pinned value for a frozen variable, else any value
     *  in the variable's domain. */
    private fun symbolAllowed(state: LocalSearchState, i: Int, s: Int): Boolean {
        val v = seq[i]
        return if (state.assumptions.isFrozenInt(v)) {
            state.assignment.intValue(v) == s
        } else {
            s in state.problem.intDomains[v]
        }
    }

    private companion object {
        /** Cap on same-transition symbol substitutions offered per [proposeStructuredMoves] call. */
        const val STRUCTURED_MOVE_CAP: Int = 4

        /** Rejection-sampling attempts per requested move before giving up. */
        const val MOVE_ATTEMPT_STRIDE: Int = 6
    }
}
