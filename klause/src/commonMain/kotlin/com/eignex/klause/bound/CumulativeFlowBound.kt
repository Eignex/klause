package com.eignex.klause.bound

import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.relaxation.SchedulingView
import com.eignex.klause.lp.relaxation.schedulingViews
import com.eignex.klause.propagation.PropagationSession
import com.eignex.util.collections.IntArrayList
import com.eignex.util.collections.LongArrayList
import com.eignex.util.collections.LongHashSet
import com.eignex.util.numbers.CheckedLongOverflowException
import com.eignex.util.numbers.addExact
import com.eignex.util.numbers.mulExact
import com.eignex.util.numbers.subExact

// The preemptive relaxation of a cumulative: each task places work `Eᵢ = durᵢ·resᵢ` in `[estᵢ, lstᵢ + durᵢ)` at
// rate at most `resᵢ` under `capacity`, feasible iff a max flow saturates
// `source ─Eᵢ→ task i ─resᵢ·|J|→ interval J ─capacity·|J|→ sink` (Horn, "Some simple scheduling algorithms",
// Naval Research Logistics Quarterly 1974, https://doi.org/10.1002/nav.3800210113). Every real schedule induces a
// feasible flow, so a deficit prunes the node. It balances all windows at once and its time nodes are start-bound
// breakpoints, so it is horizon-independent. Work uses declared minimum demands and capacity its maximum, so the
// nogood cites only live start bounds.
internal class CumulativeFlowBound(
    problem: Problem,
    private val views: List<SchedulingView> = schedulingViews(problem).filter { it.starts.size in 2..MAX_TASKS },
) : SchedulingFeasibilityBound {

    val applicable: Boolean get() = views.isNotEmpty()

    override fun isInfeasible(session: PropagationSession): Boolean = views.any { infeasible(it, session) }

    private fun infeasible(view: SchedulingView, session: PropagationSession): Boolean = try {
        deficit(view, session) > 0L
    } catch (_: CheckedLongOverflowException) {
        false
    }

    override fun explain(session: PropagationSession): IntArray? {
        for (view in views) {
            val clause = try {
                if (deficit(view, session) > 0L) windowClause(view, session) else null
            } catch (_: CheckedLongOverflowException) {
                null
            }
            if (clause != null) return clause
        }
        return null
    }

    // `Σ Eᵢ − maxflow`: positive means infeasible.
    private fun deficit(view: SchedulingView, session: PropagationSession): Long {
        val n = view.starts.size
        val est = LongArray(n)
        val deadline = LongArray(n)
        var totalWork = 0L
        val active = BooleanArray(n)
        for (i in 0 until n) {
            val dom = session.intDomain(view.starts[i])
            est[i] = dom.min
            deadline[i] = addExact(dom.max, view.durations[i])
            if (view.durations[i] > 0 && view.resources[i] > 0) {
                active[i] = true
                totalWork = addExact(totalWork, mulExact(view.durations[i], view.resources[i]))
            }
        }
        if (totalWork == 0L) return 0L

        val bps = breakpoints(est, deadline, active)
        if (bps.size < 2) return 0L
        val intervals = bps.size - 1

        val source = 0
        val sink = n + intervals + 1
        val flow = MaxFlow(sink + 1)
        val cap = view.capacity
        for (i in 0 until n) {
            if (!active[i]) continue
            flow.addEdge(source, 1 + i, mulExact(view.durations[i], view.resources[i]))
        }
        for (k in 0 until intervals) {
            val len = subExact(bps[k + 1], bps[k])
            val node = n + 1 + k
            flow.addEdge(node, sink, mulExact(cap, len))
            for (i in 0 until n) {
                if (!active[i]) continue
                if (bps[k] >= est[i] && bps[k + 1] <= deadline[i]) {
                    flow.addEdge(1 + i, node, mulExact(view.resources[i], len))
                }
            }
        }
        return subExact(totalWork, flow.solve(source, sink))
    }

    private fun breakpoints(est: LongArray, deadline: LongArray, active: BooleanArray): LongArray {
        val set = LongHashSet()
        for (i in est.indices) {
            if (!active[i]) continue
            set.add(est[i])
            set.add(deadline[i])
        }
        val arr = set.toLongArray()
        arr.sort()
        return arr
    }

    private fun windowClause(view: SchedulingView, session: PropagationSession): IntArray {
        val lits = IntArrayList()
        for (i in view.starts.indices) {
            if (view.durations[i] <= 0 || view.resources[i] <= 0) continue
            val dom = session.intDomain(view.starts[i])
            lits.add(session.boundGeLit(view.starts[i], dom.min, positive = false))
            lits.add(session.boundLeLit(view.starts[i], dom.max, positive = false))
        }
        return lits.toIntArray()
    }

    internal companion object {
        internal const val MAX_TASKS: Int = 512
    }
}

// Dinic (1970) max flow.
private class MaxFlow(private val numNodes: Int) {
    private val to = IntArrayList()
    private val cap = LongArrayList()
    private val head = Array(numNodes) { IntArrayList() }

    fun addEdge(u: Int, v: Int, c: Long) {
        head[u].add(to.size)
        to.add(v)
        cap.add(c)
        head[v].add(to.size)
        to.add(u)
        cap.add(0L) // edges come in pairs, so e xor 1 is the partner
    }

    fun solve(s: Int, t: Int): Long {
        var total = 0L
        val level = IntArray(numNodes)
        val iter = IntArray(numNodes)
        while (bfs(s, t, level)) {
            for (i in iter.indices) iter[i] = 0
            while (true) {
                val f = dfs(s, t, Long.MAX_VALUE, level, iter)
                if (f == 0L) break
                total = addExact(total, f)
            }
        }
        return total
    }

    private fun bfs(s: Int, t: Int, level: IntArray): Boolean {
        for (i in level.indices) level[i] = -1
        level[s] = 0
        val queue = IntArrayList()
        queue.add(s)
        var qi = 0
        while (qi < queue.size) {
            val u = queue[qi++]
            val edges = head[u]
            for (j in 0 until edges.size) {
                val e = edges[j]
                if (cap[e] > 0L && level[to[e]] < 0) {
                    level[to[e]] = level[u] + 1
                    queue.add(to[e])
                }
            }
        }
        return level[t] >= 0
    }

    private fun dfs(u: Int, t: Int, pushed: Long, level: IntArray, iter: IntArray): Long {
        if (u == t) return pushed
        val edges = head[u]
        while (iter[u] < edges.size) {
            val e = edges[iter[u]]
            val v = to[e]
            if (cap[e] > 0L && level[v] == level[u] + 1) {
                val d = dfs(v, t, minOf(pushed, cap[e]), level, iter)
                if (d > 0L) {
                    cap[e] -= d
                    cap[e xor 1] += d
                    return d
                }
            }
            iter[u]++
        }
        return 0L
    }
}
