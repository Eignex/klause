package com.eignex.klause.solver.factor

import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.localsearch.LocalSearchFactor
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.propagation.PropagationState

/**
 * Shared base for [Path] and [Tree]: a directed graph over [numNodes] with parallel
 * `from[e]` / `to[e]` arc arrays. Each node has a presence bool (in [nodePresent]) and each
 * arc has a presence bool (in [edgePresent]). Propagation enforces:
 *
 *  - edge ⇒ both endpoints present
 *  - present non-special node has correct in/out degree (1/1 for path internal, 0/1 for
 *    source, 1/0 for sink, 1/anything for tree non-root, 0/anything for tree root)
 *  - reachability: if forced-present source's BFS cannot reach a forced-present sink (or
 *    root cannot reach a forced-present non-root in the tree), fail.
 *
 * Decompositions still emit the underlying flow / rank constraints (CompilerGlobalsLowering)
 * — this factor adds an early-failure check via BFS so the search prunes infeasible
 * orientations before the linear decomposition triggers.
 */
abstract class PathTreeBase(
    val numNodes: Int,
    val from: IntArray,
    val to: IntArray,
    val nodePresent: IntArray,
    val edgePresent: IntArray,
    val nodeOffset: Int,
) : LocalSearchFactor {

    init {
        require(from.size == to.size) { "PathTree: from/to size mismatch" }
        require(from.size == edgePresent.size) { "PathTree: edgePresent size mismatch" }
        require(nodePresent.size == numNodes) { "PathTree: nodePresent size mismatch" }
    }

    override val intVars: IntArray = computeIntVars()

    protected abstract fun computeIntVars(): IntArray

    /** Out-arcs per node (built once for the lifetime of the factor). */
    protected val outArcs: Array<IntArray> = run {
        val acc = Array(numNodes) { mutableListOf<Int>() }
        for (e in from.indices) acc[from[e] - nodeOffset].add(e)
        Array(numNodes) { acc[it].toIntArray() }
    }
    protected val inArcs: Array<IntArray> = run {
        val acc = Array(numNodes) { mutableListOf<Int>() }
        for (e in to.indices) acc[to[e] - nodeOffset].add(e)
        Array(numNodes) { acc[it].toIntArray() }
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean = false
    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int = 0
    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int = 0

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? {
        // Combine antecedents over bool/int operands: the static arc topology means any
        // bound shift on the presence bools or the source/sink/root int counts.
        val all = ArrayList<Int>()
        for (b in boolVars) {
            val v = state.boolValues[b] ?: continue
            all.add(Lit.make(b, positive = v))
        }
        // For each int var include its bound atoms.
        val intAnt = state.composeIntVarAtomAntecedents(intVars)
        if (intAnt != null) for (x in intAnt) all.add(x)
        return all.toIntArray()
    }

    /** Edge-presence value: 1 (true), -1 (false), 0 (undetermined). */
    protected fun edgeState(state: PropagationState, e: Int): Int {
        val v = state.boolValues[edgePresent[e]] ?: return 0
        return if (v) 1 else -1
    }

    protected fun nodeState(state: PropagationState, v: Int): Int {
        val b = state.boolValues[nodePresent[v]] ?: return 0
        return if (b) 1 else -1
    }
}

/**
 * `path(numNodes, from, to, source, sink, nodePresent, edgePresent)`.
 * Adds: edge-implies-endpoint, source/sink must be present, and a BFS reachability check
 * from source to sink over edges whose presence is not forced false.
 */
class Path(
    numNodes: Int,
    from: IntArray,
    to: IntArray,
    val source: Int,
    val sink: Int,
    nodePresent: IntArray,
    edgePresent: IntArray,
    nodeOffset: Int = 0,
) : PathTreeBase(numNodes, from, to, nodePresent, edgePresent, nodeOffset) {

    override val boolVars: IntArray = nodePresent + edgePresent
    override fun computeIntVars(): IntArray = intArrayOf(source, sink)

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        // Bound source/sink into [nodeOffset, nodeOffset + numNodes - 1].
        val ant = state.composeIntVarAtomAntecedents(intVars)
        if (!state.tightenIntMin(source, nodeOffset, ant)) return false
        if (!state.tightenIntMax(source, nodeOffset + numNodes - 1, ant)) return false
        if (!state.tightenIntMin(sink, nodeOffset, ant)) return false
        if (!state.tightenIntMax(sink, nodeOffset + numNodes - 1, ant)) return false

        // Edge present → both endpoints present.
        for (e in from.indices) {
            if (edgeState(state, e) == 1) {
                if (!state.pinBool(nodePresent[from[e] - nodeOffset], true, ant)) return false
                if (!state.pinBool(nodePresent[to[e] - nodeOffset], true, ant)) return false
            }
        }

        // Reachability + SCC pruning. When source/sink are pinned, do bidirectional BFS
        // and prune any node/edge that can't appear on a source→sink path.
        val srcDom = state.intDomains[source]
        val sinkDom = state.intDomains[sink]
        if (srcDom.min == srcDom.max && sinkDom.min == sinkDom.max) {
            val src = srcDom.min - nodeOffset
            val snk = sinkDom.min - nodeOffset
            if (!state.pinBool(nodePresent[src], true, ant)) return false
            if (!state.pinBool(nodePresent[snk], true, ant)) return false

            // Forward reachable from src (over not-forced-false edges/nodes).
            val fwd = BooleanArray(numNodes)
            run {
                fwd[src] = true
                val q = ArrayDeque<Int>()
                q.add(src)
                while (q.isNotEmpty()) {
                    val v = q.removeFirst()
                    for (e in outArcs[v]) {
                        if (edgeState(state, e) == -1) continue
                        val u = to[e] - nodeOffset
                        if (!fwd[u] && nodeState(state, u) != -1) {
                            fwd[u] = true
                            q.add(u)
                        }
                    }
                }
            }
            if (!fwd[snk]) return false

            // Backward reachable to snk (reverse BFS on the same residual graph).
            val bwd = BooleanArray(numNodes)
            run {
                bwd[snk] = true
                val q = ArrayDeque<Int>()
                q.add(snk)
                while (q.isNotEmpty()) {
                    val v = q.removeFirst()
                    for (e in inArcs[v]) {
                        if (edgeState(state, e) == -1) continue
                        val u = from[e] - nodeOffset
                        if (!bwd[u] && nodeState(state, u) != -1) {
                            bwd[u] = true
                            q.add(u)
                        }
                    }
                }
            }

            // A node lies on some src→snk path iff fwd[v] ∧ bwd[v]. Force-prune the rest.
            for (v in 0 until numNodes) {
                val present = state.boolValues[nodePresent[v]]
                if (!(fwd[v] && bwd[v]) && present != false) {
                    if (!state.pinBool(nodePresent[v], false, ant)) return false
                }
            }
            // An edge lies on some src→snk path iff fwd[from] ∧ bwd[to] ∧ not-forced-false.
            for (e in from.indices) {
                if (edgeState(state, e) == -1) continue
                val u = from[e] - nodeOffset
                val w = to[e] - nodeOffset
                if (!(fwd[u] && bwd[w])) {
                    if (!state.pinBool(edgePresent[e], false, ant)) return false
                }
            }
        }
        return true
    }
}

/**
 * `tree(numNodes, from, to, root, nodePresent, edgePresent)`.
 * Root present, edges imply endpoints, BFS-from-root reachability over present nodes.
 */
class Tree(
    numNodes: Int,
    from: IntArray,
    to: IntArray,
    val root: Int,
    nodePresent: IntArray,
    edgePresent: IntArray,
    nodeOffset: Int = 0,
) : PathTreeBase(numNodes, from, to, nodePresent, edgePresent, nodeOffset) {

    override val boolVars: IntArray = nodePresent + edgePresent
    override fun computeIntVars(): IntArray = intArrayOf(root)

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        val ant = state.composeIntVarAtomAntecedents(intVars)
        if (!state.tightenIntMin(root, nodeOffset, ant)) return false
        if (!state.tightenIntMax(root, nodeOffset + numNodes - 1, ant)) return false
        for (e in from.indices) {
            if (edgeState(state, e) == 1) {
                if (!state.pinBool(nodePresent[from[e] - nodeOffset], true, ant)) return false
                if (!state.pinBool(nodePresent[to[e] - nodeOffset], true, ant)) return false
            }
        }
        val rd = state.intDomains[root]
        if (rd.min == rd.max) {
            val r = rd.min - nodeOffset
            if (!state.pinBool(nodePresent[r], true, ant)) return false
            // Forward reachable from root over not-forced-false edges/nodes.
            val fwd = BooleanArray(numNodes)
            fwd[r] = true
            val q = ArrayDeque<Int>()
            q.add(r)
            while (q.isNotEmpty()) {
                val v = q.removeFirst()
                for (e in outArcs[v]) {
                    if (edgeState(state, e) == -1) continue
                    val u = to[e] - nodeOffset
                    if (!fwd[u] && nodeState(state, u) != -1) {
                        fwd[u] = true
                        q.add(u)
                    }
                }
            }
            // Any forced-present node must be forward-reachable from root.
            for (v in 0 until numNodes) {
                if (v == r) continue
                if (nodeState(state, v) == 1 && !fwd[v]) return false
            }
            // Any unreachable node cannot be present; prune.
            for (v in 0 until numNodes) {
                if (v == r) continue
                val present = state.boolValues[nodePresent[v]]
                if (!fwd[v] && present != false) {
                    if (!state.pinBool(nodePresent[v], false, ant)) return false
                }
            }
            // An edge can only exist between reachable endpoints.
            for (e in from.indices) {
                if (edgeState(state, e) == -1) continue
                val u = from[e] - nodeOffset
                val w = to[e] - nodeOffset
                if (!(fwd[u] && fwd[w])) {
                    if (!state.pinBool(edgePresent[e], false, ant)) return false
                }
            }
        }
        return true
    }
}
