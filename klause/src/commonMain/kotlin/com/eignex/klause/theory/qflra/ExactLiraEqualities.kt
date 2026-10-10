package com.eignex.klause.theory.qflra

import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.Term
import com.eignex.klause.ir.linearRows
import com.eignex.klause.lp.ExactComparison
import com.eignex.klause.lp.ExactRowForm
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchAtomPremise
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.explainAtoms
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.PollStride
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
    private val comparisons = if (model.numRealVars != 0) emptyList() else {
        model.factors.flatMapIndexed { factorIndex, factor ->
            if (factor.linearForm is LinearForm.Disjunction) emptyList() else {
                factor.linearRows.mapIndexedNotNull { rowIndex, row ->
                    if ((0 until row.size).any { Term.isBool(row.ref(it)) }) return@mapIndexedNotNull null
                    forms[factorIndex][rowIndex].comparison(true) { false } to row.activator
                }
            }
        }
    }
    private val rows = comparisons.mapNotNull { (comparison, activator) ->
        val terms = comparison.terms.entries.toList()
        if (terms.isEmpty() || terms.size > 2) return@mapNotNull null
        val first = terms[0]
        val second = terms.getOrNull(1)
        if (second != null && first.value != second.value.negated()) return@mapNotNull null
        val positive = first.value.signum() > 0
        val magnitude = if (positive) first.value else first.value.negated()
        if (magnitude.isZero) return@mapNotNull null
        Prepared(
            if (positive) first.key else second?.key ?: zero,
            if (positive) second?.key ?: zero else first.key,
            comparison.bound * magnitude.reciprocal(), comparison.op, comparison.strict, activator,
        )
    }
    private val general = comparisons.filter { (comparison, _) ->
        val coefficients = comparison.terms.values
        coefficients.size != 1 && (coefficients.size != 2 || coefficients.first() != coefficients.last().negated())
    }
    var implied = false
        private set
    private var diagnosticPasses = 0L
    private var diagnosticPeakExclusions = 0
    private var diagnosticMatches = 0L
    private var diagnosticImplications = 0L
    private var diagnosticUnavailableReasons = 0L

    fun printDiagnosticCounters() {
        println("; smtEqualityExclusionPasses=$diagnosticPasses")
        println("; smtEqualityExclusionPeakKeys=$diagnosticPeakExclusions")
        println("; smtEqualityExclusionMatches=$diagnosticMatches")
        println("; smtEqualityExclusionImplications=$diagnosticImplications")
        println("; smtEqualityExclusionUnavailableReasons=$diagnosticUnavailableReasons")
    }

    fun propagate(context: SearchContext, stop: Cancellation): ComponentResult {
        diagnosticPasses++
        implied = false
        if (stop()) return ComponentResult.Indeterminate
        if (rows.isEmpty() && general.isEmpty()) return ComponentResult.Consistent
        val stride = PollStride()
        val metered = stop.workMeter() != null
        val progressStop = Cancellation { (metered || stride.due()) && stop() }
        val forest = EqualityForest(zero + 1)
        for (integer in 0 until zero) {
            if (progressStop()) return ComponentResult.Indeterminate
            val lower = model.intBounds.lowerAsBigInteger(integer) ?: continue
            if (lower != model.intBounds.upperAsBigInteger(integer) || lower < room.negate() || lower > room) continue
            forest.join(integer, zero, lower.toLong(), null)
        }
        var inconsistent: Prepared? = null
        for (row in rows) {
            if (progressStop()) return ComponentResult.Indeterminate
            val truth = if (row.activator == ALWAYS) true else context.boolValue(row.activator)
            val equality = (row.op == LinearOp.EQ && truth == true) || (row.op == LinearOp.NE && truth == false)
            if (!equality) continue
            if (row.bound.den != BIG_ONE) {
                return conflict(context, emptyList(), row.literal(checkNotNull(truth)), stop)
            }
            if (row.bound.num < room.negate() || row.bound.num > room) continue
            val literal = row.literal(checkNotNull(truth))
            val premise = if (literal == ALWAYS) null else SearchAtomPremise.Asserted(SearchDecision.Bool(literal))
            if (!forest.join(row.target, row.source, row.bound.num.toLong(), premise)) {
                inconsistent = row
                break
            }
        }
        if (!forest.preparePaths(progressStop)) return ComponentResult.Indeterminate
        inconsistent?.let { row ->
            val premises = forest.premises(row.target, row.source, progressStop) ?: return ComponentResult.Indeterminate
            val truth = row.op == LinearOp.EQ
            return conflict(context, premises, row.literal(truth), stop)
        }
        val closed = closeEqualities(forest, context, stop, progressStop)
        if (closed !is ComponentResult.Consistent) return closed
        val excluded = excludedOffsets(forest, context, progressStop) ?: return ComponentResult.Indeterminate
        diagnosticPeakExclusions = maxOf(diagnosticPeakExclusions, excluded.size)
        for (row in rows) {
            if (progressStop()) return ComponentResult.Indeterminate
            val value = forest.difference(row.target, row.source)
            val nonintegral = (row.op == LinearOp.EQ || row.op == LinearOp.NE) && row.bound.den != BIG_ONE
            val exclusion = if (value == null && excluded.isNotEmpty()) {
                row.offsetKey(forest)?.let { excluded[it] }
            } else null
            val truth = if (nonintegral) {
                row.op == LinearOp.NE
            } else {
                value?.let { row.truth(BigFraction.ofLong(it)) }
                    ?: exclusion?.let { row.op == LinearOp.NE } ?: continue
            }
            val assigned = if (row.activator == ALWAYS) true else context.boolValue(row.activator)
            if (assigned == truth) continue
            if (exclusion != null) diagnosticMatches++
            val premises = if (exclusion != null) {
                val paths = forest.offsetPremises(row.target, row.source, progressStop)
                    ?: return ComponentResult.Indeterminate
                paths + exclusion
            } else if (nonintegral) emptyList() else {
                forest.premises(row.target, row.source, progressStop) ?: return ComponentResult.Indeterminate
            }
            if (assigned != null) {
                return conflict(context, premises, row.literal(assigned), stop)
            }
            val decision = SearchDecision.Bool(row.literal(truth))
            val reason = context.explainAtoms(SearchAtomPremise.All(premises), decision)
            if (reason == null) {
                if (exclusion != null) diagnosticUnavailableReasons++
                continue
            }
            if (stop()) return ComponentResult.Indeterminate
            val result = context.imply(decision.literal, reason)
            if (result !is ComponentResult.Consistent) return result
            if (exclusion != null) diagnosticImplications++
            val accepted = accept(decision, context)
            if (accepted !is ComponentResult.Consistent) return accepted
            implied = true
        }
        val result = propagateGeneral(forest, context, stop, progressStop)
        return if (stop()) ComponentResult.Indeterminate else result
    }

    private fun excludedOffsets(
        forest: EqualityForest,
        context: SearchContext,
        stop: Cancellation,
    ): Map<OffsetKey, List<SearchAtomPremise>>? {
        val result = HashMap<OffsetKey, List<SearchAtomPremise>>()
        for (row in rows) {
            if (stop()) return null
            val truth = if (row.activator == ALWAYS) true else context.boolValue(row.activator)
            if (!((row.op == LinearOp.NE && truth == true) || (row.op == LinearOp.EQ && truth == false))) continue
            val key = row.offsetKey(forest) ?: continue
            if (key in result) continue
            val paths = forest.offsetPremises(row.target, row.source, stop) ?: return null
            val literal = row.literal(checkNotNull(truth))
            result[key] = if (literal == ALWAYS) paths else {
                paths + SearchAtomPremise.Asserted(SearchDecision.Bool(literal))
            }
        }
        return result
    }

    private fun Prepared.offsetKey(forest: EqualityForest): OffsetKey? {
        if ((op != LinearOp.EQ && op != LinearOp.NE) || bound.den != BIG_ONE ||
            bound.num < room.negate() || bound.num > room
        ) return null
        return forest.offsetKey(target, source, bound.num.toLong())
    }

    private data class OffsetKey(val first: Int, val second: Int, val value: Long)

    private fun closeEqualities(
        forest: EqualityForest,
        context: SearchContext,
        stop: Cancellation,
        progressStop: Cancellation,
    ): ComponentResult {
        val active = general.mapNotNull { (comparison, activator) ->
            if (progressStop()) return ComponentResult.Indeterminate
            val truth = if (activator == ALWAYS) true else context.boolValue(activator)
            if ((comparison.op == LinearOp.EQ && truth == true) || (comparison.op == LinearOp.NE && truth == false)) {
                Triple(comparison, activator, checkNotNull(truth))
            } else {
                null
            }
        }
        if (active.isEmpty()) return ComponentResult.Consistent
        repeat(GENERAL_PASSES) {
            val batch = ArrayList<DerivedEquality>()
            for ((comparison, activator, truth) in active) {
                if (progressStop()) return ComponentResult.Indeterminate
                val reduced = forest.reduce(comparison.terms, progressStop) ?: return ComponentResult.Indeterminate
                val bound = comparison.bound - reduced.offset
                val terms = reduced.coefficients.entries.toList()
                if (terms.isEmpty() && bound.isZero) continue
                if (terms.size > 2) continue
                val first = terms.firstOrNull()
                val second = terms.getOrNull(1)
                if (second != null && first?.value != second.value.negated()) continue
                val positive = first?.value?.signum() != -1
                val magnitude = first?.value?.let { if (positive) it else it.negated() } ?: BigFraction.ONE
                val normalized = bound * magnitude.reciprocal()
                val impossible = terms.isEmpty() || normalized.den != BIG_ONE
                if (!impossible && (normalized.num < room.negate() || normalized.num > room)) continue
                val premises = forest.expressionPremises(comparison.terms, progressStop)
                    ?: return ComponentResult.Indeterminate
                val literal = if (activator == ALWAYS) ALWAYS else Lit.make(activator, truth)
                if (impossible) return conflict(context, premises, literal, stop)
                val premise = SearchAtomPremise.All(
                    if (literal == ALWAYS) premises else {
                        premises + SearchAtomPremise.Asserted(SearchDecision.Bool(literal))
                    },
                )
                batch += DerivedEquality(
                    if (positive) checkNotNull(first).key else second?.key ?: zero,
                    if (positive) second?.key ?: zero else checkNotNull(first).key,
                    normalized.num.toLong(), premise,
                )
            }
            var changed = false
            for (equality in batch) {
                if (progressStop()) return ComponentResult.Indeterminate
                val previous = forest.difference(equality.target, equality.source)
                if (previous == equality.value) continue
                if (!forest.join(equality.target, equality.source, equality.value, equality.premise)) {
                    if (!forest.preparePaths(progressStop)) return ComponentResult.Indeterminate
                    val premises = forest.premises(equality.target, equality.source, progressStop)
                        ?: return ComponentResult.Indeterminate
                    return conflict(context, premises + equality.premise, ALWAYS, stop)
                }
                changed = true
            }
            if (!changed) return ComponentResult.Consistent
            if (!forest.preparePaths(progressStop)) return ComponentResult.Indeterminate
        }
        return ComponentResult.Consistent
    }

    private data class DerivedEquality(
        val target: Int,
        val source: Int,
        val value: Long,
        val premise: SearchAtomPremise,
    )

    private fun propagateGeneral(
        forest: EqualityForest,
        context: SearchContext,
        stop: Cancellation,
        progressStop: Cancellation,
    ): ComponentResult {
        for ((comparison, activator) in general) {
            if (progressStop()) return ComponentResult.Indeterminate
            val value = forest.constant(comparison.terms, progressStop) ?: continue
            val truth = comparison.truth(value)
            val assigned = if (activator == ALWAYS) true else context.boolValue(activator)
            if (assigned == truth) continue
            val premises = forest.expressionPremises(comparison.terms, progressStop)
                ?: return ComponentResult.Indeterminate
            if (assigned != null) {
                val literal = if (activator == ALWAYS) ALWAYS else Lit.make(activator, assigned)
                return conflict(context, premises, literal, stop)
            }
            val decision = SearchDecision.Bool(Lit.make(activator, truth))
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

    private fun ExactComparison.truth(value: BigFraction): Boolean = when (op) {
        LinearOp.EQ -> value == bound
        LinearOp.NE -> value != bound
        LinearOp.LE -> if (strict) value < bound else value <= bound
        LinearOp.GE -> if (strict) value > bound else value >= bound
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
        private val pathPremise = arrayOfNulls<SearchAtomPremise>(size)
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

        fun offsetKey(target: Int, source: Int, value: Long): OffsetKey? {
            val first = find(target)
            val second = find(source)
            if (first.first == second.first) return null
            val normalized = value - first.second + second.second
            return if (first.first < second.first) OffsetKey(first.first, second.first, normalized)
            else OffsetKey(second.first, first.first, -normalized)
        }

        fun offsetPremises(target: Int, source: Int, stop: Cancellation): List<SearchAtomPremise>? {
            val first = premises(target, find(target).first, stop) ?: return null
            val second = premises(source, find(source).first, stop) ?: return null
            return first + second
        }

        fun constant(terms: Map<Int, BigFraction>, stop: Cancellation): BigFraction? {
            val zero = parent.lastIndex
            val zeroRoot = find(zero).first
            val coefficients = HashMap<Int, BigFraction>()
            for ((column, coefficient) in terms) {
                if (stop()) return null
                val root = find(column).first
                if (root != zeroRoot) coefficients[root] = (coefficients[root] ?: BigFraction.ZERO) + coefficient
            }
            if (coefficients.values.any { !it.isZero }) return null
            var value = BigFraction.ZERO
            for ((column, coefficient) in terms) {
                if (stop()) return null
                val (root, offset) = find(column)
                val relative = if (root == zeroRoot) checkNotNull(difference(column, zero)) else offset
                if (relative != 0L) value += coefficient * BigFraction.ofLong(relative)
            }
            return value
        }

        fun reduce(terms: Map<Int, BigFraction>, stop: Cancellation): Reduced? {
            val zero = parent.lastIndex
            val (zeroRoot, zeroOffset) = find(zero)
            val coefficients = HashMap<Int, BigFraction>()
            var value = BigFraction.ZERO
            for ((column, coefficient) in terms) {
                if (stop()) return null
                val (root, offset) = find(column)
                val relative = if (root == zeroRoot) offset - zeroOffset else offset
                if (relative != 0L) value += coefficient * BigFraction.ofLong(relative)
                if (root != zeroRoot) coefficients[root] = (coefficients[root] ?: BigFraction.ZERO) + coefficient
            }
            return Reduced(coefficients.filterValues { !it.isZero }, value)
        }

        data class Reduced(val coefficients: Map<Int, BigFraction>, val offset: BigFraction)

        fun expressionPremises(terms: Map<Int, BigFraction>, stop: Cancellation): List<SearchAtomPremise>? {
            val zero = parent.lastIndex
            val zeroRoot = find(zero).first
            val result = ArrayList<SearchAtomPremise>()
            for (column in terms.keys) {
                if (stop()) return null
                val root = find(column).first
                result += premises(column, if (root == zeroRoot) zero else root, stop) ?: return null
            }
            return result
        }

        fun join(target: Int, source: Int, value: Long, premise: SearchAtomPremise?): Boolean {
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
            edges += Edge(target, source, premise)
            return true
        }

        fun preparePaths(stop: Cancellation): Boolean {
            pathParent.fill(-1)
            pathPremise.fill(null)
            depth.fill(0)
            val starts = IntArray(parent.size + 1)
            for (edge in edges) {
                if (stop()) return false
                starts[edge.target + 1]++
                starts[edge.source + 1]++
            }
            for (vertex in parent.indices) {
                if (stop()) return false
                starts[vertex + 1] += starts[vertex]
            }
            val adjacency = IntArray(edges.size * 2)
            val fill = starts.copyOf()
            edges.forEachIndexed { index, edge ->
                if (stop()) return false
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
                        if (stop()) return false
                        val edge = edges[adjacency[index]]
                        val neighbor = if (edge.source == vertex) edge.target else edge.source
                        if (pathParent[neighbor] >= 0) continue
                        pathParent[neighbor] = vertex
                        pathPremise[neighbor] = edge.premise
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
                pathPremise[vertex]?.let { result += it }
                if (vertex == first) first = pathParent[first] else second = pathParent[second]
            }
            return result
        }

        private data class Edge(val target: Int, val source: Int, val premise: SearchAtomPremise?)
    }

    private companion object {
        const val ALWAYS = -1
        const val GENERAL_PASSES = 4
    }
}
