package com.eignex.klause.bench.profile

import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.factor.table.Mdd
import com.eignex.klause.factor.table.Table
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.BuiltInScoringProjectionProvider
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.LocalSearchProblem
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.ScoringProjection
import com.eignex.klause.localsearch.ScoringProjectionProvider
import com.eignex.klause.solver.AssignmentCheckCapability
import com.eignex.klause.solver.ScoringCapability
import com.eignex.klause.util.Cancellation

internal object GlobalScoringViews {
    const val MAX_ARITY = 64
    const val MAX_CELLS = 65_536
    const val MAX_PATHS = 1024
    const val MAX_WORK = 262_144

    data class Admission(val factor: Int, val view: String, val reason: String?, val cells: Int = 0)
    data class Selection(val provider: ScoringProjectionProvider, val admissions: List<Admission>)
    data class Relation(val factor: Factor?, val reason: String?, val work: Int)

    fun pairwise(problem: Problem, canonical: LocalSearchProblem): Selection {
        require(canonical.problem === problem)
        val admissions = problem.factors.mapIndexedNotNull { id, factor ->
            (factor as? AllDifferent)?.let {
                Admission(id, "pairwise-distinct", when {
                    it.vars.size > MAX_ARITY -> "arity-limit"
                    it.exceptSet.size > MAX_ARITY -> "except-limit"
                    it.presents.any { literal -> literal < 0 } -> "invalid-presence"
                    else -> null
                })
            }
        }
        val accepted = admissions.filter { it.reason == null }.associateBy { problem.factors[it.factor] }
        val provider = ScoringProjectionProvider { factor ->
            val admission = accepted[factor]
            if (admission == null) BuiltInScoringProjectionProvider.select(factor) else ScoringProjection(
                ScoringCapability.GRADED, AssignmentCheckCapability.EXACT,
            ) { _, _ -> PairwiseDistinct(factor as AllDifferent, canonical.invariants[admission.factor]) }
        }
        return Selection(provider, admissions)
    }

    fun alternate(factor: Factor, stop: Cancellation = Cancellation.Never): Relation = when (factor) {
        is Table -> tableDiagram(factor, stop)
        is Mdd -> diagramTable(factor, stop)
        else -> Relation(null, "unsupported-factor", 0)
    }

    private fun tableDiagram(table: Table, stop: Cancellation): Relation {
        if (table.hi != null) return Relation(null, "interval-table", 0)
        if (table.arity > MAX_ARITY) return Relation(null, "arity-limit", 0)
        if (table.tuples.size > MAX_CELLS) return Relation(null, "cell-limit", 0)
        val counts = IntArray(table.arity + 1)
        counts[0] = 1
        val edges = Array(table.arity) { linkedMapOf<Pair<Int, Long>, Int>() }
        var work = 0
        for (row in 0 until table.numTuples) {
            var from = 0
            for (column in table.xs.indices) {
                if (stop()) return Relation(null, "cancelled", work)
                work++
                val edge = from to table.tuples[row * table.arity + column]
                from = edges[column].getOrPut(edge) { counts[column + 1]++ }
            }
        }
        val starts = IntArray(table.arity + 1)
        val records = ArrayList<Long>()
        for (layer in edges.indices) {
            starts[layer] = records.size
            for ((edge, to) in edges[layer]) {
                if (stop()) return Relation(null, "cancelled", work)
                records.add(edge.first.toLong())
                records.add(edge.second)
                records.add(to.toLong())
            }
        }
        starts[table.arity] = records.size
        return Relation(Mdd(table.xs, counts, starts, records.toLongArray(), 0,
            IntArray(counts.last()) { it }, 3), null, work)
    }

    private fun diagramTable(mdd: Mdd, stop: Cancellation): Relation {
        val width = mdd.seq.size + if (mdd.cost >= 0) 1 else 0
        if (width > MAX_ARITY) return Relation(null, "arity-limit", 0)
        if (mdd.transitions.size > MAX_CELLS) return Relation(null, "cell-limit", 0)
        if (mdd.layerStarts.first() != 0 || mdd.layerStarts.last() != mdd.transitions.size ||
            mdd.numStatesPerLayer.any { it <= 0 } || mdd.initial !in 0 until mdd.numStatesPerLayer[0] ||
            mdd.accepting.any { it !in 0 until mdd.numStatesPerLayer.last() }
        ) return Relation(null, "invalid-diagram", 0)
        val outgoing = Array(mdd.seq.size) { linkedMapOf<Int, MutableList<Int>>() }
        var work = 0
        for (layer in mdd.seq.indices) {
            val start = mdd.layerStarts[layer]
            val end = mdd.layerStarts[layer + 1]
            if (start < 0 || end > mdd.transitions.size || start > end ||
                start % mdd.recordStride != 0 || end % mdd.recordStride != 0
            ) {
                return Relation(null, "invalid-diagram", work)
            }
            val deterministic = HashSet<Pair<Int, Long>>()
            for (record in start until end step mdd.recordStride) {
                if (stop()) return Relation(null, "cancelled", work)
                work++
                val from = mdd.transitions[record]
                val to = mdd.transitions[record + 2]
                if (from !in 0L until mdd.numStatesPerLayer[layer].toLong() ||
                    to !in 0L until mdd.numStatesPerLayer[layer + 1].toLong()
                ) return Relation(null, "invalid-diagram", work)
                if (mdd.cost >= 0 && !deterministic.add(from.toInt() to mdd.transitions[record + 1])) {
                    return Relation(null, "nondeterministic-weighted-diagram", work)
                }
                outgoing[layer].getOrPut(from.toInt()) { ArrayList() }.add(record)
            }
        }
        val rows = ArrayList<Long>()
        val word = LongArray(width)
        var reason: String? = null
        val accepting = mdd.accepting.toSet()
        fun visit(layer: Int, from: Int, weight: Long) {
            if (reason != null) return
            if (stop()) {
                reason = "cancelled"
                return
            }
            if (++work > MAX_WORK) {
                reason = "work-limit"
                return
            }
            if (layer == mdd.seq.size) {
                if (from !in accepting) return
                if (rows.size / width >= MAX_PATHS || rows.size + width > MAX_CELLS) {
                    reason = "path-limit"
                    return
                }
                if (mdd.cost >= 0) word[word.lastIndex] = weight
                for (value in word) rows.add(value)
                return
            }
            for (record in outgoing[layer][from].orEmpty()) {
                word[layer] = mdd.transitions[record + 1]
                val nextWeight = try {
                    if (mdd.recordStride == 4) Math.addExact(weight, mdd.transitions[record + 3]) else weight
                } catch (_: ArithmeticException) {
                    reason = "weight-overflow"
                    return
                }
                visit(layer + 1, mdd.transitions[record + 2].toInt(), nextWeight)
                if (reason != null) return
            }
        }
        visit(0, mdd.initial, 0)
        if (reason != null) return Relation(null, reason, work)
        if (rows.isEmpty()) return Relation(null, "empty-relation", work)
        val xs = if (mdd.cost >= 0) mdd.seq + mdd.cost else mdd.seq
        return Relation(Table(xs, rows.toLongArray()), null, work)
    }
}

private class PairwiseDistinct(private val relation: AllDifferent, native: Invariant) : Invariant by native {
    override fun violationDegree(state: LocalSearchState, factorId: Int): Int = degree(state)

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean = degree(state) != 0

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Long): Int {
        val oldValue = state.assignment.intValue(intVar)
        if (oldValue == newValue) return 0
        val oldCounted = oldValue !in relation.exceptSet
        val newCounted = newValue !in relation.exceptSet
        var delta = 0
        for (left in relation.vars.indices) {
            if (relation.vars[left] != intVar || !present(state, left)) continue
            for (right in relation.vars.indices) {
                if (right == left || (relation.vars[right] == intVar && right < left) ||
                    !present(state, right)
                ) continue
                val before = state.assignment.intValue(relation.vars[right])
                val after = if (relation.vars[right] == intVar) newValue else before
                if (oldCounted && before == oldValue) delta--
                if (newCounted && after == newValue) delta++
            }
        }
        return delta
    }

    override fun deltaIfBoolFlipped(state: LocalSearchState, factorId: Int, boolVar: Int): Int =
        degree(state, boolVar = boolVar) - degree(state)

    private fun degree(state: LocalSearchState, boolVar: Int = -1): Int {
        var pairs = 0
        for (left in relation.vars.indices) {
            if (!present(state, left, boolVar)) continue
            val value = state.assignment.intValue(relation.vars[left])
            if (value in relation.exceptSet) continue
            for (right in left + 1 until relation.vars.size) {
                if (present(state, right, boolVar) && state.assignment.intValue(relation.vars[right]) == value) pairs++
            }
        }
        return pairs
    }

    private fun present(state: LocalSearchState, position: Int, boolVar: Int = -1): Boolean {
        if (relation.presents.isEmpty()) return true
        val literal = relation.presents[position]
        val variable = Lit.variable(literal)
        return Lit.evaluate(literal, state.assignment.boolValue(variable) xor (variable == boolVar))
    }
}
