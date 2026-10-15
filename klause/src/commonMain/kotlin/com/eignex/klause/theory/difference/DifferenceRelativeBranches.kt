package com.eignex.klause.theory.difference

import com.eignex.klause.arithmetic.difference.DifferenceEdge
import com.eignex.klause.arithmetic.difference.DifferenceFragment
import com.eignex.klause.arithmetic.difference.checkedDifferenceSum
import com.eignex.klause.ir.Lit
import com.eignex.klause.solver.search.SearchBranchKind
import com.eignex.klause.solver.search.SearchBranchProposal
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchTheoryAtom
import com.eignex.klause.solver.search.SearchTheoryDecision
import com.eignex.klause.util.Cancellation

@Suppress("ReturnCount")
internal class DifferenceRelativeBranches private constructor(
    private val intervals: List<Interval>,
    private val source: DifferenceFragment,
    private val sourceBools: Int,
) : SearchBranchProposal {
    override val branchKind: SearchBranchKind get() = SearchBranchKind.Arithmetic
    private val splits = Array(intervals.size) { ArrayList<Split>() }
    private var graph = source
    private var closed = false
    var numBools: Int = sourceBools
        private set

    val fragment: DifferenceFragment get() = graph

    override fun nextBranch(context: SearchContext): List<SearchDecision>? {
        if (closed) return null
        val stop = context.executionControls.cancellation
        if (!hasOpenBoolean(context, stop)) return null
        for (index in intervals.indices) {
            stop.charge(1)
            if (stopped(context)) return null
            val interval = intervals[index]
            var lower = interval.lower
            var upper = interval.upper
            for (split in splits[index]) {
                stop.charge(1)
                if (stopped(context)) return null
                when (context.boolValue(Lit.variable(split.atom.positive.literal))) {
                    true -> upper = minOf(upper, split.threshold)
                    false -> lower = maxOf(lower, split.threshold + 1)
                    null -> Unit
                }
            }
            if (lower >= upper) continue
            val threshold = lower + (upper - lower) / 2
            val prior = splits[index].firstOrNull { it.threshold == threshold }
            if (prior != null) return alternatives(prior.atom, threshold, interval.preferred)
            if (graph.edges.size > MAX_EDGES - 2) return null
            val forward = Bound(interval.from, interval.to, threshold)
            val reverse = Bound(interval.to, interval.from, -threshold - 1)
            stop.charge(graph.edges.size * 4L + graph.numNodes * GRAPH_NODE_UNITS + 2L)
            if (stopped(context)) return null
            val atom = context.registerAtom(forward, reverse) ?: return null
            val edges = ArrayList<DifferenceEdge>(graph.edges.size + 2)
            edges.addAll(graph.edges)
            edges += DifferenceEdge(forward.from, forward.to, forward.bound, atom.positive.literal)
            edges += DifferenceEdge(reverse.from, reverse.to, reverse.bound, atom.negative.literal)
            val extended = DifferenceFragment(edges)
            if (!extended.carriesAPotential() || stopped(context)) return null
            graph = extended
            numBools = maxOf(numBools, Lit.variable(atom.positive.literal) + 1)
            splits[index] += Split(threshold, atom)
            return alternatives(atom, threshold, interval.preferred)
        }
        return null
    }

    fun close() {
        closed = true
        splits.forEach { it.clear() }
        graph = source
        numBools = sourceBools
    }

    private fun stopped(context: SearchContext): Boolean {
        val stopped = context.cancelled() || context.executionControls.exhausted
        if (stopped) context.requestCancellationPoll()
        return stopped
    }

    private fun hasOpenBoolean(context: SearchContext, stop: Cancellation): Boolean {
        for (variable in 0 until sourceBools) {
            stop.charge(1)
            if (stopped(context)) return false
            if (context.boolValue(variable) == null) return true
        }
        return false
    }

    private fun alternatives(atom: SearchTheoryAtom, threshold: Long, preferred: Long): List<SearchDecision> =
        atom.alternatives().let { if (preferred <= threshold) it else it.asReversed() }

    private data class Bound(val from: Int, val to: Int, val bound: Long) : SearchTheoryDecision
    private class Split(val threshold: Long, val atom: SearchTheoryAtom)
    private class Interval(
        val from: Int,
        val to: Int,
        val lower: Long,
        val upper: Long,
        val preferred: Long = lower,
    )

    companion object {
        private const val MAX_EDGES = 65_536
        private const val GRAPH_NODE_UNITS = 32L
        private const val MAX_INTERVAL_WIDTH = 255L

        fun create(source: DifferenceFragment, sourceBools: Int, stop: Cancellation): DifferenceRelativeBranches? {
            if (source.edges.size > MAX_EDGES || !source.carriesAPotential() || stop()) return null
            val bounds = HashMap<Pair<Int, Int>, Long>()
            for (edge in source.edges) {
                stop.charge(1)
                if (stop()) return null
                if (edge.guard != DifferenceEdge.ALWAYS || edge.source == DifferenceFragment.ZERO ||
                    edge.target == DifferenceFragment.ZERO || edge.source == edge.target
                ) continue
                val key = edge.source to edge.target
                bounds[key] = minOf(bounds[key] ?: edge.bound, edge.bound)
            }
            val intervals = ArrayList<Interval>()
            for ((pair, upper) in bounds) {
                stop.charge(1)
                if (stop()) return null
                val (from, to) = pair
                if (from >= to) continue
                val opposite = bounds[to to from] ?: continue
                val width = checkedDifferenceSum(upper, opposite) ?: continue
                if (width !in 1..MAX_INTERVAL_WIDTH || opposite == Long.MIN_VALUE) continue
                intervals += Interval(from, to, -opposite, upper)
            }
            val orderingVisits = (Int.SIZE_BITS - intervals.size.countLeadingZeroBits()).coerceAtLeast(1)
            stop.charge(intervals.size.toLong() * orderingVisits)
            if (stop() || intervals.isEmpty()) return null
            intervals.sortWith(compareBy<Interval> { it.upper - it.lower }.thenBy { it.from }.thenBy { it.to })
            stop.charge(intervals.size * 2L)
            if (stop()) return null
            val ordered = intervals.mapIndexed { index, interval ->
                val preferred = preferredValue(index, interval, stop) ?: return null
                Interval(interval.from, interval.to, interval.lower, interval.upper, preferred)
            }
            return if (stop()) null else DifferenceRelativeBranches(ordered, source, sourceBools)
        }

        private fun preferredValue(index: Int, interval: Interval, stop: Cancellation): Long? {
            val cardinality = interval.upper - interval.lower + 1
            var ordinal = index.toLong()
            var offset = 0L
            while (ordinal > 0) {
                stop.charge(1)
                if (stop()) return null
                offset = (offset + ordinal % cardinality) % cardinality
                ordinal /= cardinality
            }
            return interval.lower + offset
        }
    }
}
