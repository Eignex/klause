package com.eignex.klause.presolve

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.IntArrayList

internal object CoverColumnDominance {
    private const val MAX_COLUMNS = 200_000
    private const val MAX_INDEX_WORK = 1_000_000L
    private const val MAX_COMPARE_WORK = 1_000_000L
    private const val COMPARE_WORK_PER_ENTRY = 16L

    private class Work(private val cancellation: Cancellation) {
        private var remaining = MAX_INDEX_WORK
        var entries = 0L
            private set

        fun visit(): Boolean {
            if (remaining == 0L || cancellation()) return false
            remaining--
            entries++
            cancellation.charge(1L)
            return true
        }

        fun beginComparisons() {
            remaining = minOf(MAX_COMPARE_WORK, entries * COMPARE_WORK_PER_ENTRY)
        }
    }

    private class Column {
        val clauses = IntArrayList()
        var signature = 0L
        var anchor = -1
    }

    fun pins(problem: Problem, costs: Map<Int, Long>, cancellation: Cancellation): List<Factor> {
        if (costs.isEmpty() || problem.numBoolVars > MAX_COLUMNS || cancellation()) return emptyList()
        val work = Work(cancellation)
        var hasPositiveCost = false
        for (cost in costs.values) {
            if (!work.visit()) return emptyList()
            if (cost > 0L) {
                hasPositiveCost = true
                break
            }
        }
        if (!hasPositiveCost) return emptyList()
        val eligible = BooleanArray(problem.numBoolVars) { true }
        val columns = arrayOfNulls<Column>(problem.numBoolVars)
        val root = (problem as? BakedProblem)?.rootDeductions as? PropagationResult.Implied
        for (v in eligible.indices) {
            if (!work.visit()) return emptyList()
            if (root?.boolValueOrNull(v) != null) eligible[v] = false
        }
        for (i in problem.factors.indices) {
            if (!work.visit()) return emptyList()
            val factor = problem.factors[i]
            val clause = factor as? Clause
            var cover = clause != null && clause.literals.size > 1
            if (clause != null) {
                for (lit in clause.literals) {
                    if (!work.visit()) return emptyList()
                    if (!Lit.isPositive(lit) || Lit.variable(lit) >= eligible.size) cover = false
                }
            }
            if (!cover) {
                for (v in factor.boolVars) {
                    if (!work.visit()) return emptyList()
                    eligible[v] = false
                }
                continue
            }
            checkNotNull(clause)
            for (lit in clause.literals) {
                if (!work.visit()) return emptyList()
                val v = Lit.variable(lit)
                val column = columns[v] ?: Column().also { columns[v] = it }
                column.clauses.add(i)
                column.signature = column.signature or (1L shl (i and 63))
                val anchor = column.anchor
                if (anchor < 0 || clause.literals.size < (problem.factors[anchor] as Clause).literals.size) {
                    column.anchor = i
                }
            }
        }
        work.beginComparisons()
        val pins = ArrayList<Factor>()
        for (x in columns.indices) {
            if (!work.visit()) break
            val column = columns[x] ?: continue
            val cost = costs[x] ?: 0L
            if (!eligible[x] || cost <= 0L) continue
            val anchor = problem.factors[column.anchor] as Clause
            for (lit in anchor.literals) {
                if (!work.visit()) return pins
                val y = Lit.variable(lit)
                val otherCost = costs[y] ?: 0L
                if (!eligible[y] || otherCost < 0L || cost <= otherCost) continue
                val other = checkNotNull(columns[y])
                if (column.clauses.size > other.clauses.size ||
                    (column.signature and other.signature) != column.signature
                ) {
                    continue
                }
                when (containsAll(other.clauses, column.clauses, work)) {
                    null -> return pins

                    false -> continue

                    true -> {
                        // Strictly cheaper replacements form an acyclic chain. Its last column
                        // covers every clause of each predecessor, even when intermediate columns pin.
                        pins.add(Clause(intArrayOf(Lit.make(x, false))))
                        break
                    }
                }
            }
        }
        return pins
    }

    private fun containsAll(superset: IntArrayList, subset: IntArrayList, work: Work): Boolean? {
        var i = 0
        for (j in 0 until subset.size) {
            if (!work.visit()) return null
            val clause = subset[j]
            while (i < superset.size && superset[i] < clause) {
                if (!work.visit()) return null
                i++
            }
            if (i == superset.size || superset[i] != clause) return false
        }
        return true
    }
}
