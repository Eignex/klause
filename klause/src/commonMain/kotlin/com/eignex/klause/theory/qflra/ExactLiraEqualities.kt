package com.eignex.klause.theory.qflra

import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.Term
import com.eignex.klause.ir.linearRows
import com.eignex.klause.lp.ExactRowForm
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchAtomPremise
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.explainAtoms
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.compareTo
import com.eignex.klause.util.negate
import com.eignex.klause.util.toLong

internal class ExactLiraEqualities(
    private val model: Problem,
    forms: List<List<ExactRowForm>>,
    private val accept: (SearchDecision, SearchContext) -> ComponentResult,
) {
    private val zero = model.numIntVars
    // A simple forest path has at most zero links; leave room to subtract two path offsets.
    private val room = bigIntOf(Long.MAX_VALUE / (8L * (zero.toLong() + 2L)))
    private val rows = if (model.numRealVars != 0) emptyList() else {
        model.factors.flatMapIndexed { factorIndex, factor ->
            if (factor.linearForm is LinearForm.Disjunction) emptyList() else {
                factor.linearRows.mapIndexedNotNull { rowIndex, row ->
                    if ((0 until row.size).any { Term.isBool(row.ref(it)) }) return@mapIndexedNotNull null
                    val comparison = forms[factorIndex][rowIndex].comparison(true) { false }
                    val terms = comparison.terms.entries.toList()
                    if (terms.isEmpty() || terms.size > 2) return@mapIndexedNotNull null
                    val first = terms[0]
                    val second = terms.getOrNull(1)
                    if (second != null && first.value != second.value.negated()) return@mapIndexedNotNull null
                    val positive = first.value.signum() > 0
                    val magnitude = if (positive) first.value else first.value.negated()
                    if (magnitude.isZero) return@mapIndexedNotNull null
                    Prepared(
                        if (positive) first.key else second?.key ?: zero,
                        if (positive) second?.key ?: zero else first.key,
                        comparison.bound * magnitude.reciprocal(), comparison.op, comparison.strict, row.activator,
                    )
                }
            }
        }
    }
    var implied = false
        private set

    fun propagate(context: SearchContext, stop: Cancellation): ComponentResult {
        implied = false
        if (stop()) return ComponentResult.Indeterminate
        if (rows.isEmpty()) return ComponentResult.Consistent
        val forest = EqualityForest(zero + 1)
        for (integer in 0 until zero) {
            if (stop()) return ComponentResult.Indeterminate
            val lower = model.intBounds.lowerAsBigInteger(integer) ?: continue
            if (lower != model.intBounds.upperAsBigInteger(integer) || lower < room.negate() || lower > room) continue
            forest.join(integer, zero, lower.toLong(), ALWAYS)
        }
        var inconsistent: Prepared? = null
        for (row in rows) {
            if (stop()) return ComponentResult.Indeterminate
            val truth = if (row.activator == ALWAYS) true else context.boolValue(row.activator)
            val equality = (row.op == LinearOp.EQ && truth == true) || (row.op == LinearOp.NE && truth == false)
            if (!equality) continue
            if (row.bound.den != BIG_ONE) {
                return conflict(context, emptyList(), row.literal(checkNotNull(truth)), stop)
            }
            if (row.bound.num < room.negate() || row.bound.num > room) continue
            if (!forest.join(row.target, row.source, row.bound.num.toLong(), row.literal(checkNotNull(truth)))) {
                inconsistent = row
                break
            }
        }
        if (!forest.preparePaths(stop)) return ComponentResult.Indeterminate
        inconsistent?.let { row ->
            val premises = forest.premises(row.target, row.source, stop) ?: return ComponentResult.Indeterminate
            val truth = row.op == LinearOp.EQ
            return conflict(context, premises, row.literal(truth), stop)
        }
        for (row in rows) {
            if (stop()) return ComponentResult.Indeterminate
            val value = forest.difference(row.target, row.source)
            val nonintegral = (row.op == LinearOp.EQ || row.op == LinearOp.NE) && row.bound.den != BIG_ONE
            val truth = if (nonintegral) {
                row.op == LinearOp.NE
            } else {
                value?.let { row.truth(BigFraction.ofLong(it)) } ?: continue
            }
            val assigned = if (row.activator == ALWAYS) true else context.boolValue(row.activator)
            if (assigned == truth) continue
            val premises = if (nonintegral) emptyList() else {
                forest.premises(row.target, row.source, stop) ?: return ComponentResult.Indeterminate
            }
            if (assigned != null) {
                return conflict(context, premises, row.literal(assigned), stop)
            }
            val decision = SearchDecision.Bool(row.literal(truth))
            val reason = context.explainAtoms(SearchAtomPremise.All(premises), decision) ?: continue
            if (stop()) return ComponentResult.Indeterminate
            val result = context.imply(decision.literal, reason)
            if (result !is ComponentResult.Consistent) return result
            val accepted = accept(decision, context)
            if (accepted !is ComponentResult.Consistent) return accepted
            implied = true
        }
        return ComponentResult.Consistent
    }

    private fun conflict(
        context: SearchContext,
        premises: List<SearchAtomPremise>,
        literal: Int,
        stop: Cancellation,
    ): ComponentResult {
        if (stop()) return ComponentResult.Indeterminate
        val reason = context.explainAtoms(SearchAtomPremise.All(
            if (literal == ALWAYS) premises else premises + SearchAtomPremise.Asserted(SearchDecision.Bool(literal)),
        ))
        return if (stop()) ComponentResult.Indeterminate else ComponentResult.Conflict(reason)
    }

    private data class Prepared(
        val target: Int,
        val source: Int,
        val bound: BigFraction,
        val op: LinearOp,
        val strict: Boolean,
        val activator: Int,
    ) {
        fun literal(truth: Boolean): Int = if (activator == ALWAYS) ALWAYS else Lit.make(activator, truth)

        fun truth(value: BigFraction): Boolean = when (op) {
            LinearOp.EQ -> value == bound
            LinearOp.NE -> value != bound
            LinearOp.LE -> if (strict) value < bound else value <= bound
            LinearOp.GE -> if (strict) value > bound else value >= bound
        }
    }

    private class EqualityForest(size: Int) {
        private val parent = IntArray(size) { it }
        private val rank = IntArray(size)
        private val offset = LongArray(size)
        private val edges = ArrayList<Edge>()
        private val pathParent = IntArray(size) { -1 }
        private val pathGuard = IntArray(size) { ALWAYS }
        private val depth = IntArray(size)

        private fun find(vertex: Int): Pair<Int, Long> {
            var root = vertex
            var value = 0L
            while (root != parent[root]) {
                value += offset[root]
                root = parent[root]
            }
            return root to value
        }

        fun difference(target: Int, source: Int): Long? {
            val first = find(target)
            val second = find(source)
            return if (first.first == second.first) first.second - second.second else null
        }

        fun join(target: Int, source: Int, value: Long, guard: Int): Boolean {
            val first = find(target)
            val second = find(source)
            if (first.first == second.first) return first.second - second.second == value
            val delta = value - first.second + second.second
            if (rank[first.first] < rank[second.first]) {
                parent[first.first] = second.first
                offset[first.first] = delta
            } else {
                parent[second.first] = first.first
                offset[second.first] = -delta
                if (rank[first.first] == rank[second.first]) rank[first.first]++
            }
            edges += Edge(target, source, guard)
            return true
        }

        fun preparePaths(stop: Cancellation): Boolean {
            val starts = IntArray(parent.size + 1)
            for (edge in edges) {
                if (stop()) return false
                starts[edge.target + 1]++
                starts[edge.source + 1]++
            }
            for (vertex in parent.indices) starts[vertex + 1] += starts[vertex]
            val adjacency = IntArray(edges.size * 2)
            val fill = starts.copyOf()
            edges.forEachIndexed { index, edge ->
                adjacency[fill[edge.target]++] = index
                adjacency[fill[edge.source]++] = index
            }
            val pending = ArrayDeque<Int>()
            for (root in parent.indices) {
                if (stop()) return false
                if (pathParent[root] >= 0) continue
                pathParent[root] = root
                pending.add(root)
                while (pending.isNotEmpty()) {
                    if (stop()) return false
                    val vertex = pending.removeLast()
                    for (index in starts[vertex] until starts[vertex + 1]) {
                        val edge = edges[adjacency[index]]
                        val neighbor = if (edge.source == vertex) edge.target else edge.source
                        if (pathParent[neighbor] >= 0) continue
                        pathParent[neighbor] = vertex
                        pathGuard[neighbor] = edge.guard
                        depth[neighbor] = depth[vertex] + 1
                        pending.add(neighbor)
                    }
                }
            }
            return true
        }

        fun premises(target: Int, source: Int, stop: Cancellation): List<SearchAtomPremise>? {
            var first = target
            var second = source
            val result = ArrayList<SearchAtomPremise>()
            while (first != second) {
                if (stop()) return null
                val vertex = if (depth[first] >= depth[second]) first else second
                val guard = pathGuard[vertex]
                if (guard != ALWAYS) result += SearchAtomPremise.Asserted(SearchDecision.Bool(guard))
                if (vertex == first) first = pathParent[first] else second = pathParent[second]
            }
            return result
        }

        private data class Edge(val target: Int, val source: Int, val guard: Int)
    }

    private companion object {
        const val ALWAYS = -1
    }
}
