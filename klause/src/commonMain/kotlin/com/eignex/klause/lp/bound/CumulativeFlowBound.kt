package com.eignex.klause.lp.bound

import com.eignex.klause.lp.LpOverflowException
import com.eignex.klause.lp.addExact
import com.eignex.klause.lp.mulExact
import com.eignex.klause.lp.relaxation.SchedulingView
import com.eignex.klause.lp.relaxation.schedulingViews
import com.eignex.klause.lp.subExact
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Problem
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.LongArrayList

/**
 * Preemptive min-cost-flow feasibility bound for the scheduling globals (#454). The exact
 * **preemptive** relaxation of a [com.eignex.klause.factor.scheduling.Cumulative] /
 * [com.eignex.klause.factor.scheduling.Disjunctive]: every task `i` must place its work `Eᵢ = durᵢ·resᵢ`
 * somewhere in its release/deadline window `[estᵢ, lstᵢ + durᵢ)` at a rate of at most `resᵢ` per time
 * unit, and at every instant the total rate stays ≤ `capacity`. That is feasible iff a max-flow
 * saturates a transportation network
 *
 * ```
 *   source ──Eᵢ──► task i ──resᵢ·|J|──► time-interval J ──capacity·|J|──► sink
 * ```
 * (the `task i → J` arc exists only when `J ⊆ [estᵢ, lstᵢ + durᵢ)`). Any real non-preemptive schedule
 * induces a feasible flow, so **max-flow `< Σ Eᵢ` proves the node infeasible** — a sound prune.
 *
 * This is strictly stronger than the pairwise-window [CumulativeEnergeticBound] scan (which checks
 * one window at a time): the flow balances all release/deadline windows simultaneously. It is also
 * horizon-independent — the network's time nodes are the `O(n)` distinct start-bound breakpoints, not
 * the time axis — so it complements the horizon-gated time-indexed LP (#453). Under an incumbent that
 * has tightened the makespan (hence the task deadlines `lstᵢ`), the same feasibility test prunes
 * objective-suboptimal subtrees, which is its bounding contribution.
 *
 * Soundness mirrors [CumulativeEnergeticBound]: work uses each task's **minimum** (declared) demand
 * and the capacity its **maximum**, the release/deadline windows are the **live** start bounds, and
 * all products are checked ([mulExact]) — an overflow reports "cannot prove infeasible" rather than a
 * wrapped value. Optional tasks and variable durations are excluded by [schedulingViews]. The nogood
 * negates exactly the live start-bound atoms the network was built from (the declared demand/capacity
 * hold at every node, so they need not be cited), so it is all-false at the dead node and globally
 * valid.
 */
internal class CumulativeFlowBound(problem: Problem) {
    private val views: List<SchedulingView> = schedulingViews(problem).filter { it.starts.size in 2..MAX_TASKS }

    val applicable: Boolean get() = views.isNotEmpty()

    /** True if some scheduling factor is preemptively infeasible at the current node (⇒ prune). */
    fun isInfeasible(session: PropagationSession): Boolean = views.any { infeasible(it, session) }

    private fun infeasible(view: SchedulingView, session: PropagationSession): Boolean = try {
        deficit(view, session) > 0L
    } catch (_: LpOverflowException) {
        false // overflow ⇒ cannot prove infeasible (sound skip), never a wrapped verdict
    }

    /** A bound-atom nogood for the first preemptively infeasible factor, or null when none / overflow. */
    fun explain(session: PropagationSession): IntArray? {
        for (view in views) {
            val clause = try {
                if (deficit(view, session) > 0L) windowClause(view, session) else null
            } catch (_: LpOverflowException) {
                null
            }
            if (clause != null) return clause
        }
        return null
    }

    /** `Σ Eᵢ − maxflow`: the work that cannot be preemptively placed (positive ⇒ infeasible). */
    private fun deficit(view: SchedulingView, session: PropagationSession): Long {
        val n = view.starts.size
        val est = IntArray(n)
        val deadline = IntArray(n) // lstᵢ + durᵢ
        var totalWork = 0L
        val active = BooleanArray(n)
        for (i in 0 until n) {
            val dom = session.intDomain(view.starts[i])
            est[i] = dom.min
            deadline[i] = addExactInt(dom.max, view.durations[i])
            if (view.durations[i] > 0 && view.resources[i] > 0) {
                active[i] = true
                totalWork = addExact(totalWork, mulExact(view.durations[i].toLong(), view.resources[i].toLong()))
            }
        }
        if (totalWork == 0L) return 0L

        // Breakpoint-compressed time intervals: the distinct release/deadline values.
        val bps = breakpoints(est, deadline, active)
        if (bps.size < 2) return 0L
        val intervals = bps.size - 1

        // Node layout: 0 = source, 1..n = tasks, n+1 .. n+intervals = intervals, last = sink.
        val source = 0
        val sink = n + intervals + 1
        val flow = MaxFlow(sink + 1)
        val cap = view.capacity.toLong()
        for (i in 0 until n) {
            if (!active[i]) continue
            flow.addEdge(source, 1 + i, mulExact(view.durations[i].toLong(), view.resources[i].toLong()))
        }
        for (k in 0 until intervals) {
            val len = (bps[k + 1] - bps[k]).toLong()
            val node = n + 1 + k
            flow.addEdge(node, sink, mulExact(cap, len))
            for (i in 0 until n) {
                if (!active[i]) continue
                // Interval [bps[k], bps[k+1]) is usable by task i iff it lies inside [estᵢ, deadlineᵢ).
                if (bps[k] >= est[i] && bps[k + 1] <= deadline[i]) {
                    flow.addEdge(1 + i, node, mulExact(view.resources[i].toLong(), len))
                }
            }
        }
        return subExact(totalWork, flow.solve(source, sink))
    }

    /** Sorted distinct release/deadline values over the active tasks — the interval boundaries. */
    private fun breakpoints(est: IntArray, deadline: IntArray, active: BooleanArray): IntArray {
        val set = HashSet<Int>()
        for (i in est.indices) {
            if (!active[i]) continue
            set.add(est[i])
            set.add(deadline[i])
        }
        val arr = set.toIntArray()
        arr.sort()
        return arr
    }

    /** Negate the live start-bound atoms the infeasible network rests on: per active task,
     *  `start ≥ est` and `start ≤ lst`. Relaxing any one could free the deadlock, so the disjunction
     *  of their negations is implied by the constraint and globally valid. */
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

    private fun addExactInt(a: Int, b: Int): Int {
        val r = a.toLong() + b.toLong()
        if (r > Int.MAX_VALUE || r < Int.MIN_VALUE) throw LpOverflowException("deadline overflow: $a + $b")
        return r.toInt()
    }

    internal companion object {
        /** Per-factor task cap: above it the max-flow is skipped (sound loosening). */
        internal const val MAX_TASKS: Int = 512
    }
}

/**
 * Dinic max-flow over a small dense scheduling network (`O(n)` nodes). Capacities are [Long]; the
 * networks are gated tiny, so a textbook Dinic with BFS levels + DFS blocking flow is ample.
 */
private class MaxFlow(private val numNodes: Int) {
    private val to = IntArrayList()
    private val cap = LongArrayList()
    private val head = Array(numNodes) { IntArrayList() } // node → indices into [to]/[cap]

    fun addEdge(u: Int, v: Int, c: Long) {
        head[u].add(to.size)
        to.add(v)
        cap.add(c)
        head[v].add(to.size)
        to.add(u)
        cap.add(0L) // residual edge (edges added in pairs ⇒ e and e xor 1 are partners)
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
                    cap[e xor 1] += d // paired residual edge (edges added two at a time)
                    return d
                }
            }
            iter[u]++
        }
        return 0L
    }
}
