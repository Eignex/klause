package com.eignex.klause.portfolio

import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.INTEGER_LEARNING_TERMS

private const val INTEGER_NEIGHBORHOOD_WORK = 4096

internal class IntegerLearningNeighborhood(val compatible: Boolean, val limited: Boolean, val work: Int) {
    companion object {
        val NONE = IntegerLearningNeighborhood(false, false, 0)

        fun inspect(problem: Problem): IntegerLearningNeighborhood = Probe().inspect(problem)
    }

    private class Probe {
        private var remaining = INTEGER_NEIGHBORHOOD_WORK
        private var limited = false

        private fun visit(): Boolean {
            if (remaining == 0) {
                limited = true
                return false
            }
            remaining--
            return true
        }

        private fun result(compatible: Boolean) =
            IntegerLearningNeighborhood(compatible, limited, INTEGER_NEIGHBORHOOD_WORK - remaining)

        fun inspect(problem: Problem): IntegerLearningNeighborhood {
            val rows = mutableMapOf<Int, MutableList<Row>>()
            val equalities = mutableMapOf<Int, MutableList<Row>>()
            for ((factor, value) in problem.factors.withIndex()) {
                if (!visit()) return result(false)
                val form = value.linearForm as? LinearForm.Conjunction ?: continue
                for (row in form.rows) {
                    if (!visit()) return result(false)
                    val candidate = compact(row, factor)
                    if (limited) return result(false)
                    candidate ?: continue
                    val previous = if (candidate.equality) rows else equalities
                    val seen = HashSet<Row>()
                    for (ref in candidate.refs) {
                        if (!visit()) return result(false)
                        for (other in previous[ref].orEmpty()) {
                            if (!visit()) return result(false)
                            if (candidate.factor == other.factor || !seen.add(other)) continue
                            if (combines(candidate, other)) return result(true)
                            if (limited) return result(false)
                        }
                    }
                    for (ref in candidate.refs) {
                        if (!visit()) return result(false)
                        rows.getOrPut(ref) { mutableListOf() }.add(candidate)
                        if (candidate.equality) {
                            if (!visit()) return result(false)
                            equalities.getOrPut(ref) { mutableListOf() }.add(candidate)
                        }
                    }
                }
            }
            return result(false)
        }

        private fun compact(row: LinearRow, factor: Int): Row? {
            if (row.size !in 2..INTEGER_LEARNING_TERMS || !row.isLongUnconditional || !row.isIntegerOnly ||
                row.strict || row.relation == LinearOp.NE) return null
            val first = row.coeff(0)
            if (first == 0L || first == Long.MIN_VALUE) return null
            val magnitude = if (first < 0L) -first else first
            if (row.bound % magnitude != 0L) return null
            val refs = IntArray(row.size)
            val signs = IntArray(row.size)
            val direction = if (row.relation == LinearOp.GE) -1 else 1
            var negative = 0L
            for (k in refs.indices) {
                if (!visit()) return null
                val sign = when (row.coeff(k)) {
                    magnitude -> 1
                    -magnitude -> { negative++; -1 }
                    else -> return null
                }
                refs[k] = row.ref(k)
                for (j in 0 until k) {
                    if (!visit()) return null
                    if (refs[j] == refs[k]) return null
                }
                signs[k] = sign * direction
            }
            if (row.bound / magnitude !in (1L - negative)..(row.size.toLong() - 1L - negative)) return null
            return Row(factor, refs, signs, row.relation == LinearOp.EQ && row.size >= 3)
        }

        private fun combines(left: Row, right: Row): Boolean {
            var same = 0
            var opposite = 0
            for (i in left.refs.indices) {
                for (j in right.refs.indices) {
                    if (!visit()) return false
                    if (left.refs[i] != right.refs[j]) continue
                    if (left.signs[i] == right.signs[j]) same++ else opposite++
                    break
                }
            }
            val terms = left.refs.size + right.refs.size
            return (opposite > 0 && terms - same - 2 * opposite in 1..INTEGER_LEARNING_TERMS) ||
                (same > 0 && terms - opposite - 2 * same in 1..INTEGER_LEARNING_TERMS)
        }
    }

    private class Row(val factor: Int, val refs: IntArray, val signs: IntArray, val equality: Boolean)
}
