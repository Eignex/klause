package com.eignex.klause.solver.factor

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.localsearch.LocalSearchFactor
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.propagation.PropagationState

/**
 * Min-cost network flow factor: for each node `n`, `Σ flow[a ∈ in(n)] − Σ flow[a ∈ out(n)]
 *  = balance[n]`. When [cost] is non-negative, also enforces `Σ weight[a] · flow[a] = cost`.
 *
 * Propagation:
 *  - Component-level balance conservation (early infeasibility).
 *  - Per-node inflow/outflow interval check + bound tightening from the balance equation.
 *  - SSP lower bound on the LP relaxation of the cost: lower-bound-saturate arcs, route the
 *    residual supply/demand through a min-cost augmenting-path computation (SPFA, allowing
 *    negative weights), and update cost.min. The matching upper bound (negate weights, redo)
 *    tightens cost.max.
 *  - Reduced-cost arc pruning: from the dual potentials returned by SSP, each arc's
 *    "incremental cost to push one more unit" is its reduced cost. If forcing one extra unit
 *    on an arc already pushes the LB beyond cost.max, tighten that arc's max down to its
 *    current min (and symmetric on the upper-bound run).
 */
class MinCostFlow(
    val numNodes: Int,
    val arcFrom: IntArray,
    val arcTo: IntArray,
    val balance: IntArray,
    val flow: IntArray,
    val weight: IntArray?,
    val cost: Int, // -1 when no cost variable
    val nodeOffset: Int = 0,
) : LocalSearchFactor {

    init {
        require(arcFrom.size == arcTo.size && arcFrom.size == flow.size) {
            "MinCostFlow: arcFrom/arcTo/flow size mismatch"
        }
        require(balance.size == numNodes) { "MinCostFlow: balance size" }
        if (weight != null) require(weight.size == flow.size) { "MinCostFlow: weight size" }
    }

    override val boolVars: IntArray = EmptyIntArray
    override val intVars: IntArray = if (cost >= 0) flow + intArrayOf(cost) else flow.copyOf()

    private val inArcs: Array<IntArray> = run {
        val acc = Array(numNodes) { mutableListOf<Int>() }
        for (a in arcTo.indices) acc[arcTo[a] - nodeOffset].add(a)
        Array(numNodes) { acc[it].toIntArray() }
    }
    private val outArcs: Array<IntArray> = run {
        val acc = Array(numNodes) { mutableListOf<Int>() }
        for (a in arcFrom.indices) acc[arcFrom[a] - nodeOffset].add(a)
        Array(numNodes) { acc[it].toIntArray() }
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean = false
    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int = 0
    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int = 0

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        state.composeIntVarAtomAntecedents(intVars)

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        val ant = state.composeIntVarAtomAntecedents(intVars)
        // Component-level conservation check on the activated residual graph.
        run {
            val parent = IntArray(numNodes) { it }
            fun find(x: Int): Int {
                var r = x
                while (parent[r] != r) r = parent[r]
                var i = x
                while (parent[i] != r) {
                    val nx = parent[i]
                    parent[i] = r
                    i = nx
                }
                return r
            }
            for (a in arcFrom.indices) {
                val d = state.intDomains[flow[a]]
                if (d.min == 0 && d.max == 0) continue
                val u = arcFrom[a] - nodeOffset
                val v = arcTo[a] - nodeOffset
                val ru = find(u)
                val rv = find(v)
                if (ru != rv) parent[ru] = rv
            }
            val sums = IntArray(numNodes)
            val active = BooleanArray(numNodes)
            for (n in 0 until numNodes) {
                val r = find(n)
                sums[r] += balance[n]
                active[r] = true
            }
            for (n in 0 until numNodes) {
                if (active[n] && find(n) == n && sums[n] != 0) return false
            }
        }
        // Per-node interval tightening.
        for (n in 0 until numNodes) {
            var inMin = 0L
            var inMax = 0L
            for (a in inArcs[n]) {
                val d = state.intDomains[flow[a]]
                inMin += d.min
                inMax += d.max
            }
            var outMin = 0L
            var outMax = 0L
            for (a in outArcs[n]) {
                val d = state.intDomains[flow[a]]
                outMin += d.min
                outMax += d.max
            }
            val balN = balance[n].toLong()
            if (inMin - outMax > balN) return false
            if (inMax - outMin < balN) return false
            for (a in inArcs[n]) {
                val d = state.intDomains[flow[a]]
                val maxAllowed = (outMax + balN - (inMin - d.min)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                if (!state.tightenIntMax(flow[a], maxAllowed, ant)) return false
                val minRequired = (outMin + balN - (inMax - d.max)).coerceAtLeast(Int.MIN_VALUE.toLong()).toInt()
                if (!state.tightenIntMin(flow[a], minRequired, ant)) return false
            }
            for (a in outArcs[n]) {
                val d = state.intDomains[flow[a]]
                val maxAllowed = (inMax - balN - (outMin - d.min)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                if (!state.tightenIntMax(flow[a], maxAllowed, ant)) return false
                val minRequired = (inMin - balN - (outMax - d.max)).coerceAtLeast(Int.MIN_VALUE.toLong()).toInt()
                if (!state.tightenIntMin(flow[a], minRequired, ant)) return false
            }
        }
        // Cost relaxation: trivial linear bounds.
        if (cost >= 0 && weight != null) {
            var sumMin = 0L
            var sumMax = 0L
            for (a in flow.indices) {
                val w = weight[a]
                val d = state.intDomains[flow[a]]
                if (w >= 0) {
                    sumMin += w.toLong() * d.min
                    sumMax += w.toLong() * d.max
                } else {
                    sumMin += w.toLong() * d.max
                    sumMax += w.toLong() * d.min
                }
            }
            val cdHi = sumMax.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val cdLo = sumMin.coerceAtLeast(Int.MIN_VALUE.toLong()).toInt()
            if (!state.tightenIntMin(cost, cdLo, ant)) return false
            if (!state.tightenIntMax(cost, cdHi, ant)) return false

            // SSP-based LP relaxation (and reduced-cost arc pruning).
            if (!sspTighten(state, ant)) return false
        }
        return true
    }

    /**
     * Run SSP twice — once to lower-bound cost (minimise), once to upper-bound it (negate
     * weights, minimise). After each run, walk arcs and use reduced costs to clamp any arc
     * whose extra unit would push the LB past the opposite cost bound.
     */
    private fun sspTighten(state: PropagationState, ant: IntArray?): Boolean {
        val n = numNodes
        val m = flow.size
        // Lower bounds and capacities.
        val lb = IntArray(m)
        val ub = IntArray(m)
        for (a in 0 until m) {
            val d = state.intDomains[flow[a]]
            lb[a] = d.min
            ub[a] = d.max
        }
        // Residual supply at each node after saturating lower bounds.
        // residual[n] = balance[n] - (in_lb - out_lb). If positive, node has supply to send.
        val w = weight!!
        // Cost from lower-bound saturation.
        var fixedCost = 0L
        for (a in 0 until m) fixedCost += w[a].toLong() * lb[a]
        // Two passes: 0 = minimise (cost.min); 1 = maximise (cost.max), using negated weights.
        for (pass in 0..1) {
            val ww = if (pass == 0) w else IntArray(m) { -w[it] }
            val fixed = if (pass == 0) fixedCost else -fixedCost
            // Residual supply convention: supply[n] > 0 → must push out; < 0 → must absorb.
            // Constraint Σin − Σout = balance, so net outflow required = −balance. After
            // shipping lb on every arc, supply = −balance − (Σout_lb − Σin_lb).
            val supply = IntArray(n)
            for (i in 0 until n) supply[i] = -balance[i]
            for (a in 0 until m) {
                supply[arcFrom[a] - nodeOffset] -= lb[a]
                supply[arcTo[a] - nodeOffset] += lb[a]
            }
            // Now we need to route net = `supply` (positive = source, negative = sink), where
            // arc residual capacity is (ub - lb) at cost ww[a]. Sum supply must be 0 per
            // component (checked above).
            val (feasible, sspCost, potential) = ssp(supply, ww, ub, lb)
            if (!feasible) return false
            val totalLb = (fixed + sspCost).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
            if (pass == 0) {
                if (!state.tightenIntMin(cost, totalLb, ant)) return false
            } else {
                // pass 1 minimised negated cost = maximised real cost.
                if (!state.tightenIntMax(cost, -totalLb, ant)) return false
            }
            // Reduced-cost arc pruning: rc(a) = ww[a] + y[u] - y[v]. For pass 0 (min): pushing
            // one extra unit on arc a adds at least max(0, rc(a)) to LB (since after SSP
            // optimality every arc with residual cap has rc ≥ 0). If LB + rc(a) > cost.max,
            // arc must stay at its current upper bound for this pass. For pass 1, symmetric.
            val cmin = state.intDomains[cost].min
            val cmax = state.intDomains[cost].max
            for (a in 0 until m) {
                if (lb[a] >= ub[a]) continue
                val u = arcFrom[a] - nodeOffset
                val v = arcTo[a] - nodeOffset
                val rc = ww[a].toLong() + potential[u] - potential[v]
                if (rc < 0) continue // negative rc only on saturated arcs at optimality
                val basePlus = if (pass == 0) (fixed + sspCost) + rc else -(fixed + sspCost) - rc
                if (pass == 0 && basePlus > cmax) {
                    // Forbid increasing arc a — pin to lb.
                    if (!state.tightenIntMax(flow[a], lb[a], ant)) return false
                    ub[a] = lb[a]
                } else if (pass == 1 && basePlus < cmin) {
                    if (!state.tightenIntMax(flow[a], lb[a], ant)) return false
                    ub[a] = lb[a]
                }
            }
        }
        return true
    }

    /**
     * Successive shortest paths min-cost flow on the residual graph defined by capacities
     * [ub] - [lb] at cost [w]. [supply] is the residual demand vector (positive = source,
     * negative = sink). Uses SPFA (Bellman-Ford in FIFO form) for shortest paths to support
     * negative weights. Returns (feasible, cost, finalPotentials). finalPotentials are valid
     * dual potentials yielding reduced costs ≥ 0 on every residual arc with leftover cap.
     */
    private fun ssp(supply: IntArray, w: IntArray, ub: IntArray, lb: IntArray): Triple<Boolean, Long, LongArray> {
        val n = numNodes
        val m = flow.size
        // Build per-node adjacency to residual arcs. For each original arc a we maintain a
        // residual capacity counter; reverse arc cap starts at 0 and grows as we push.
        val resFwd = IntArray(m) { ub[it] - lb[it] }
        val resBwd = IntArray(m) // reverse arcs have 0 cap initially
        val adj = Array(n) { mutableListOf<Int>() }
        // Encode arcs as: index < m → forward arc a; m+a → reverse of a.
        for (a in 0 until m) {
            adj[arcFrom[a] - nodeOffset].add(a)
            adj[arcTo[a] - nodeOffset].add(m + a)
        }
        val potential = LongArray(n)
        var totalCost = 0L
        // For each source node, push out its supply.
        // We process by total supply iteratively until none remain.
        val inf = Long.MAX_VALUE / 4
        while (true) {
            // Find any source with positive supply and run SPFA shortest paths to all nodes.
            var src = -1
            for (i in 0 until n) {
                if (supply[i] > 0) {
                    src = i
                    break
                }
            }
            if (src == -1) break
            val dist = LongArray(n) { inf }
            val prevArc = IntArray(n) { -1 }
            val prevNode = IntArray(n) { -1 }
            val inQueue = BooleanArray(n)
            dist[src] = 0L
            val q = ArrayDeque<Int>()
            q.addLast(src)
            inQueue[src] = true
            while (q.isNotEmpty()) {
                val u = q.removeFirst()
                inQueue[u] = false
                for (e in adj[u]) {
                    val cap: Int
                    val ec: Long
                    val v: Int
                    if (e < m) {
                        cap = resFwd[e]
                        ec = w[e].toLong()
                        v = arcTo[e] - nodeOffset
                    } else {
                        val a = e - m
                        cap = resBwd[a]
                        ec = -w[a].toLong()
                        v = arcFrom[a] - nodeOffset
                    }
                    if (cap <= 0) continue
                    val nd = dist[u] + ec
                    if (nd < dist[v]) {
                        dist[v] = nd
                        prevArc[v] = e
                        prevNode[v] = u
                        if (!inQueue[v]) {
                            q.addLast(v)
                            inQueue[v] = true
                        }
                    }
                }
            }
            // Find any sink (supply < 0) with reachable distance; pick the cheapest.
            var bestSink = -1
            var bestDist = inf
            for (i in 0 until n) {
                if (supply[i] < 0 && dist[i] < bestDist) {
                    bestDist = dist[i]
                    bestSink = i
                }
            }
            if (bestSink == -1) return Triple(false, 0L, potential)
            // Determine the bottleneck along the path from src to bestSink.
            var bottleneck = minOf(supply[src], -supply[bestSink])
            run {
                var v = bestSink
                while (v != src) {
                    val e = prevArc[v]
                    val cap = if (e < m) resFwd[e] else resBwd[e - m]
                    if (cap < bottleneck) bottleneck = cap
                    v = prevNode[v]
                }
            }
            // Push.
            run {
                var v = bestSink
                while (v != src) {
                    val e = prevArc[v]
                    if (e < m) {
                        resFwd[e] -= bottleneck
                        resBwd[e] += bottleneck
                    } else {
                        val a = e - m
                        resBwd[a] -= bottleneck
                        resFwd[a] += bottleneck
                    }
                    v = prevNode[v]
                }
            }
            supply[src] -= bottleneck
            supply[bestSink] += bottleneck
            totalCost += bottleneck * bestDist
            // Update potentials so that reduced costs stay non-negative.
            for (i in 0 until n) if (dist[i] < inf) potential[i] += dist[i]
        }
        return Triple(true, totalCost, potential)
    }
}
