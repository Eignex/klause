package com.eignex.klause.theory.difference

import com.eignex.klause.arithmetic.difference.DifferenceEdge
import com.eignex.klause.arithmetic.difference.DifferenceFragment
import com.eignex.klause.arithmetic.difference.DifferencePathCompletion
import com.eignex.klause.arithmetic.difference.DifferencePathEdges
import com.eignex.klause.arithmetic.difference.DifferencePathScratch
import com.eignex.klause.arithmetic.difference.checkedDifferenceSubtract
import com.eignex.klause.arithmetic.difference.checkedDifferenceSum
import com.eignex.klause.arithmetic.difference.differenceShortestPaths
import com.eignex.klause.ir.Lit
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchExplanation
import com.eignex.klause.util.Cancellation

@Suppress("ReturnCount")
internal class DifferenceGuardPropagation private constructor(
    private val base: DifferenceFragment,
    stop: Cancellation,
) {
    private val source = IntArray(base.edges.size) { nodeOf(base, base.edges[it].source, stop) }
    private val target = IntArray(base.edges.size) { nodeOf(base, base.edges[it].target, stop) }
    private val weights = LongArray(base.edges.size) { base.edges[it].bound }
    private val adjacencyStart = IntArray(base.numNodes + 1)
    private val adjacencyEdge = IntArray(base.edges.size)
    private val bucketStart = IntArray(base.numNodes + 1)
    private val bucketEdge = IntArray(base.edges.count { it.guard != DifferenceEdge.ALWAYS })
    private val active = BooleanArray(base.edges.size)
    private val potential = LongArray(base.numNodes)
    private val scratch = DifferencePathScratch(base.numNodes)
    private val graph = DifferencePathEdges(source, target, weights, adjacencyStart, adjacencyEdge)
    private var cursor = 0
    private var reset = true

    init {
        for (edge in base.edges.indices) {
            adjacencyStart[source[edge] + 1]++
            if (base.edges[edge].guard != DifferenceEdge.ALWAYS) bucketStart[target[edge] + 1]++
        }
        for (node in 1..base.numNodes) {
            adjacencyStart[node] += adjacencyStart[node - 1]
            bucketStart[node] += bucketStart[node - 1]
        }
        val outgoing = adjacencyStart.copyOf()
        val incoming = bucketStart.copyOf()
        for (edge in base.edges.indices) {
            adjacencyEdge[outgoing[source[edge]]++] = edge
            if (base.edges[edge].guard != DifferenceEdge.ALWAYS) bucketEdge[incoming[target[edge]]++] = edge
        }
    }

    fun retract() {
        reset = true
        scratch.completion = DifferencePathCompletion.Declined
    }

    fun propagate(fragment: DifferenceFragment, values: LongArray, context: SearchContext): ComponentResult {
        var interrupted = false
        var remaining = POLL_INTERVAL
        fun token(): Cancellation = context.executionControls.cancellation
        fun nativeStop(): Boolean {
            if (!interrupted) interrupted = context.executionControls.exhausted
            return interrupted
        }
        fun forcePoll(): Boolean {
            remaining = POLL_INTERVAL
            if (!nativeStop()) interrupted = context.cancelled() || token().isCancelled()
            return nativeStop()
        }
        fun advance(units: Long): Boolean {
            if (nativeStop()) return true
            if (units >= remaining) return forcePoll()
            remaining -= units.toInt()
            return false
        }
        val stop = object : Cancellation {
            override fun isCancelled(): Boolean =
                if (token().workMeter() != null) forcePoll() else advance(1)

            override fun deadline() = token().deadline()
            override fun workMeter() = token().workMeter()

            override fun charge(units: Long) {
                val owner = token()
                val metered = owner.workMeter() != null
                owner.charge(units)
                if (metered || token().workMeter() != null) forcePoll() else advance(units)
            }
        }
        fun finish(result: ComponentResult): ComponentResult {
            if (forcePoll() || result is ComponentResult.Indeterminate) {
                reset = true
                scratch.completion = DifferencePathCompletion.Declined
                return ComponentResult.Indeterminate
            }
            return result
        }
        if (forcePoll()) return finish(ComponentResult.Indeterminate)
        var changed = reset
        for (edge in base.edges.indices) {
            stop.charge(1)
            if (stop()) return finish(ComponentResult.Indeterminate)
            val guard = base.edges[edge].guard
            val holds = guard == DifferenceEdge.ALWAYS ||
                context.boolValue(Lit.variable(guard)) == Lit.isPositive(guard)
            if (active[edge] != holds) changed = true
            active[edge] = holds
        }
        for (node in 0 until base.numNodes) {
            stop.charge(1)
            if (stop()) return finish(ComponentResult.Indeterminate)
            val original = if (node == base.zeroNode) fragment.zeroNode else nodeOf(fragment, base.nodes[node], stop)
            if (stop()) return finish(ComponentResult.Indeterminate)
            if (original !in values.indices) return finish(ComponentResult.Consistent)
            potential[node] = values[original]
        }
        for (edge in base.edges.indices) {
            stop.charge(1)
            if (stop()) return finish(ComponentResult.Indeterminate)
            if (!active[edge]) continue
            val first = checkedDifferenceSum(potential[source[edge]], weights[edge])
                ?: return finish(ComponentResult.Consistent)
            val reduced = checkedDifferenceSubtract(first, potential[target[edge]])
                ?: return finish(ComponentResult.Consistent)
            if (reduced < 0L) return finish(ComponentResult.Consistent)
        }
        if (changed) cursor = 0
        reset = false
        // No reduced distance crosses calls: completed origins describe the original active graph only.
        scratch.completion = DifferencePathCompletion.Declined
        val result = sweep(context, stop, ::forcePoll)
        if (result is ComponentResult.Indeterminate) {
            reset = true
            scratch.completion = DifferencePathCompletion.Declined
        }
        return result
    }

    private fun sweep(context: SearchContext, stop: Cancellation, forcePoll: () -> Boolean): ComponentResult {
        var heads = 0
        while (cursor < base.numNodes && heads < HEAD_SWEEP_BUDGET) {
            if (stop()) return ComponentResult.Indeterminate
            val origin = cursor++
            val first = bucketStart[origin]
            val end = bucketStart[origin + 1]
            if (first == end) continue
            stop.charge((end - first) * 2L)
            if (forcePoll()) return ComponentResult.Indeterminate
            val needed = IntArray(end - first) { source[bucketEdge[first + it]] }
            var open = false
            for (visit in first until end) {
                stop.charge(1)
                if (stop()) return ComponentResult.Indeterminate
                if (context.boolValue(Lit.variable(base.edges[bucketEdge[visit]].guard)) == null) open = true
            }
            if (!open) continue
            heads++
            if (forcePoll()) return ComponentResult.Indeterminate
            val completion = differenceShortestPaths(graph, active, potential, origin, needed, scratch, stop)
            if (forcePoll()) return ComponentResult.Indeterminate
            when (completion) {
                DifferencePathCompletion.Abandoned -> return ComponentResult.Indeterminate
                DifferencePathCompletion.Declined -> continue
                DifferencePathCompletion.Completed -> Unit
            }
            for (visit in first until end) {
                stop.charge(1)
                if (stop()) return ComponentResult.Indeterminate
                val edge = bucketEdge[visit]
                val guard = base.edges[edge].guard
                if (context.boolValue(Lit.variable(guard)) != null || !scratch.settled(source[edge])) continue
                stop.charge(3)
                val offset = checkedDifferenceSubtract(scratch.distance[source[edge]], potential[origin]) ?: continue
                val distance = checkedDifferenceSum(offset, potential[source[edge]]) ?: continue
                val cycle = checkedDifferenceSum(distance, weights[edge]) ?: continue
                if (cycle >= 0L) continue
                if (forcePoll()) return ComponentResult.Indeterminate
                val path = scratch.pathTo(source[edge], source, stop)
                    ?: if (stop()) return ComponentResult.Indeterminate else continue
                val reason = reason(edge, path, context, stop, forcePoll)
                    ?: if (stop()) return ComponentResult.Indeterminate else continue
                if (forcePoll()) return ComponentResult.Indeterminate
                // The new guard changes the source snapshot; let the ordinary fixpoint reconcile it first.
                reset = true
                scratch.completion = DifferencePathCompletion.Declined
                return context.imply(Lit.negate(guard), reason)
            }
        }
        return if (forcePoll()) ComponentResult.Indeterminate else ComponentResult.Consistent
    }

    private fun reason(
        edge: Int,
        path: IntArray,
        context: SearchContext,
        stop: Cancellation,
        forcePoll: () -> Boolean,
    ): SearchExplanation? {
        stop.charge(path.size * 2L + 1L)
        if (forcePoll()) return null
        val literals = LinkedHashSet<Int>()
        literals.add(Lit.negate(base.edges[edge].guard))
        var current = source[edge]
        var sum = 0L
        for (step in path) {
            stop.charge(1)
            if (stop() || step !in base.edges.indices || !active[step] || target[step] != current) return null
            val guard = base.edges[step].guard
            if (guard != DifferenceEdge.ALWAYS) {
                if (context.boolValue(Lit.variable(guard)) != Lit.isPositive(guard)) return null
                literals.add(Lit.negate(guard))
            }
            sum = checkedDifferenceSum(sum, weights[step]) ?: return null
            current = source[step]
        }
        if (current != target[edge]) return null
        val cycle = checkedDifferenceSum(sum, weights[edge]) ?: return null
        if (cycle >= 0L) return null
        stop.charge(literals.size.toLong())
        return if (forcePoll()) null else SearchExplanation(literals.toIntArray())
    }

    internal companion object {
        // External cancellation remains cooperative; attached meters retain eager polls after every debit.
        private const val POLL_INTERVAL = 64
        // Each open origin costs one sparse query, matching the finite difference refutation sweep.
        private const val HEAD_SWEEP_BUDGET = 64
        // Optional primitive indexes share the existing private difference incidence envelope.
        private const val MAX_EDGES = 65_536

        private fun nodeOf(fragment: DifferenceFragment, endpoint: Int, stop: Cancellation): Int {
            if (endpoint == DifferenceFragment.ZERO) return fragment.zeroNode
            if (stop()) return 0
            var lower = 0
            var upper = fragment.nodes.lastIndex
            while (lower <= upper) {
                stop.charge(1)
                if (stop()) return 0
                val middle = (lower + upper) ushr 1
                val value = fragment.nodes[middle]
                when {
                    value < endpoint -> lower = middle + 1
                    value > endpoint -> upper = middle - 1
                    else -> return middle
                }
            }
            return -1
        }

        fun create(
            base: DifferenceFragment,
            numBools: Int,
            stop: Cancellation,
            maxEdges: Int = MAX_EDGES,
        ): DifferenceGuardPropagation? {
            if (base.edges.size > maxEdges) return null
            var guarded = false
            val room = Long.MAX_VALUE / (8L * (base.numNodes + 1))
            for (edge in base.edges) {
                stop.charge(1)
                if (stop() || edge.bound == Long.MIN_VALUE || edge.bound !in -room..room) return null
                if (edge.guard == DifferenceEdge.ALWAYS) continue
                if (Lit.variable(edge.guard) !in 0 until numBools) return null
                guarded = true
            }
            if (!guarded) return null
            // Includes owned adjacency/group buffers, query stamps and exact heap arrays and their fills.
            stop.charge(base.edges.size * 12L + base.numNodes * 16L)
            if (stop()) return null
            val prepared = DifferenceGuardPropagation(base, stop)
            return if (stop()) null else prepared
        }
    }
}
