package com.eignex.klause.lp.bound

import com.eignex.klause.lp.LpOverflowException
import com.eignex.klause.lp.addExact
import com.eignex.klause.util.EmptyIntArray
import com.eignex.klause.util.IntArrayDeque
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.LongArrayList

/**
 * Exact minimum-cost bipartite assignment over [Long] costs — the AllDifferent Lagrangian
 * subproblem. Assigns each of [numVars] variables a distinct value from `0 until numValues`, minimizing
 * the total cost of the chosen `(variable, value)` options, where an option's cost is supplied by
 * [addOption] and absent options are forbidden.
 *
 * Solved as a successive-shortest-paths min-cost flow: a source feeds one unit to each variable,
 * each variable connects to its allowed values at the option cost, and each value feeds one unit to
 * the sink. Shortest paths are found with SPFA (Bellman–Ford queue) so negative option costs — which
 * arise once Lagrangian multipliers adjust the objective coefficients — are handled directly. All
 * arithmetic is exact; an overflow surfaces as [LpOverflowException] for the caller to treat as
 * "skip the Lagrangian bound at this node".
 */
internal class MinCostAssignment(private val numVars: Int, private val numValues: Int) {
    private val source = 0
    private val sink = 1
    private val nodeCount = 2 + numVars + numValues

    private fun varNode(i: Int) = 2 + i
    private fun valueNode(j: Int) = 2 + numVars + j

    // Residual graph as parallel edge arrays; edge e and e xor 1 are forward/back partners.
    private val to = IntArrayList()
    private val cap = IntArrayList()
    private val cost = LongArrayList()
    private val nextEdge = IntArrayList()
    private val head = IntArray(nodeCount) { -1 }

    init {
        for (i in 0 until numVars) addEdge(source, varNode(i), 1, 0L)
        for (j in 0 until numValues) addEdge(valueNode(j), sink, 1, 0L)
    }

    private fun addEdge(u: Int, v: Int, capacity: Int, edgeCost: Long) {
        to.add(v)
        cap.add(capacity)
        cost.add(edgeCost)
        nextEdge.add(head[u])
        head[u] = to.size - 1
        to.add(u)
        cap.add(0)
        cost.add(-edgeCost)
        nextEdge.add(head[v])
        head[v] = to.size - 1
    }

    /** Allow variable [varIdx] to take value [valueIdx] at the given [optionCost]. */
    fun addOption(varIdx: Int, valueIdx: Int, optionCost: Long) {
        addEdge(varNode(varIdx), valueNode(valueIdx), 1, optionCost)
    }

    /** A complete assignment (all variables matched) at [cost], or [infeasible] when none exists. */
    class Result(val feasible: Boolean, val cost: Long, val assignedValue: IntArray) {
        companion object {
            fun infeasible(): Result = Result(false, 0L, EmptyIntArray)
        }
    }

    /**
     * Solve. Returns the optimal complete assignment, or [Result.infeasible] when fewer than
     * [numVars] variables can be matched (which, for an AllDifferent subproblem, proves the node
     * infeasible). Throws [LpOverflowException] if the accumulated cost exceeds 64 bits.
     */
    fun solve(): Result {
        var total = 0L
        val prevEdge = IntArray(nodeCount)
        repeat(numVars) {
            val dist = shortestPath(prevEdge) ?: return Result.infeasible()
            total = addExact(total, dist)
            // Augment one unit along the path source→sink.
            var v = sink
            while (v != source) {
                val e = prevEdge[v]
                cap[e] = cap[e] - 1
                cap[e xor 1] = cap[e xor 1] + 1
                v = to[e xor 1]
            }
        }
        // Recover: a variable→value forward edge carrying flow has residual capacity 0.
        val assigned = IntArray(numVars) { -1 }
        for (i in 0 until numVars) {
            var e = head[varNode(i)]
            while (e != -1) {
                val target = to[e]
                if (target != source && cap[e] == 0 && (e and 1) == 0) {
                    assigned[i] = target - (2 + numVars)
                    break
                }
                e = nextEdge[e]
            }
        }
        return Result(true, total, assigned)
    }

    /** SPFA shortest path from source by cost; fills [prevEdge]; null if the sink is unreachable. */
    private fun shortestPath(prevEdge: IntArray): Long? {
        val dist = LongArray(nodeCount) { INF }
        val inQueue = BooleanArray(nodeCount)
        dist[source] = 0L
        val queue = IntArrayDeque()
        queue.addLast(source)
        inQueue[source] = true
        while (queue.isNotEmpty()) {
            val u = queue.removeFirst()
            inQueue[u] = false
            val du = dist[u]
            var e = head[u]
            while (e != -1) {
                if (cap[e] > 0 && du != INF) {
                    val nd = addExact(du, cost[e])
                    if (nd < dist[to[e]]) {
                        dist[to[e]] = nd
                        prevEdge[to[e]] = e
                        if (!inQueue[to[e]]) {
                            queue.addLast(to[e])
                            inQueue[to[e]] = true
                        }
                    }
                }
                e = nextEdge[e]
            }
        }
        return if (dist[sink] == INF) null else dist[sink]
    }

    private companion object {
        /** Unreachable sentinel; kept well below Long.MAX_VALUE so additions never overflow it. */
        const val INF: Long = Long.MAX_VALUE / 4
    }
}
