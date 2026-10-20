package com.eignex.klause.theory.qflra

import com.eignex.klause.arithmetic.difference.checkedDifferenceSum
import com.eignex.klause.ir.IntegerConstants
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.Term
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchExplanation
import com.eignex.klause.util.Cancellation

internal class ExactIntegerEqualityPropagation private constructor(
    private val rows: List<Row>,
    private val zero: Int,
) {
    fun propagate(
        context: SearchContext,
        stop: Cancellation,
        receive: (Int) -> ComponentResult = { ComponentResult.Consistent },
    ): ComponentResult? {
        stop.charge((zero + 1) * 5L)
        if (stop()) return ComponentResult.Indeterminate
        val scratch = Scratch(zero + 1, stop)
        for (row in rows) {
            if (!scratch.visit()) return if (stop()) ComponentResult.Indeterminate else null
            val truth = (if (row.guard == LinearRow.ALWAYS) true else context.boolValue(row.guard)) ?: continue
            val equality = when (row.relation) {
                LinearOp.EQ -> truth
                LinearOp.NE -> !truth
                else -> false
            }
            if (!equality) continue
            scratch.join(row, if (row.guard == LinearRow.ALWAYS) null else Lit.make(row.guard, !truth))
            if (scratch.declined) return if (stop()) ComponentResult.Indeterminate else null
        }
        var published = false
        fun finish(): ComponentResult? = when {
            stop() -> ComponentResult.Indeterminate
            published -> ComponentResult.Consistent
            else -> null
        }
        for (row in rows) {
            if (!scratch.visit()) return finish()
            if (row.guard == LinearRow.ALWAYS || context.boolValue(row.guard) != null) continue
            val first = scratch.find(row.first) ?: continue
            val second = scratch.find(row.second) ?: continue
            if (first.root != second.root || (first.root != zero &&
                    row.firstCoefficient * first.sign + row.secondCoefficient * second.sign != 0L)
            ) continue
            val firstOffset = signed(first.offset, row.firstCoefficient) ?: continue
            val secondOffset = signed(second.offset, row.secondCoefficient) ?: continue
            val value = checkedDifferenceSum(firstOffset, secondOffset) ?: continue
            val truth = when (row.relation) {
                LinearOp.EQ -> value == row.bound
                LinearOp.NE -> value != row.bound
                LinearOp.LE -> value <= row.bound
                LinearOp.GE -> value >= row.bound
            }
            val literal = Lit.make(row.guard, truth)
            val reason = scratch.reason(first, second, null) ?: continue
            if (reason.any { context.boolValue(Lit.variable(it)) != !Lit.isPositive(it) }) continue
            stop.charge(reason.size + 1L)
            if (stop()) return ComponentResult.Indeterminate
            val result = context.imply(literal, SearchExplanation(reason + literal))
            if (result !is ComponentResult.Consistent) return result
            val received = receive(literal)
            if (received !is ComponentResult.Consistent) return received
            published = true
        }
        return finish()
    }

    private class Scratch(size: Int, private val stop: Cancellation) {
        private val parent = IntArray(size) { it }
        private val rank = IntArray(size)
        private val sign = LongArray(size) { 1L }
        private val offset = LongArray(size)
        private val reasons = arrayOfNulls<IntArray>(size)
        private var visits = 0
        private var retainedPremises = 0
        var declined = false
            private set

        fun visit(): Boolean {
            stop.charge(1)
            if (++visits > MAX_VISITS || stop()) declined = true
            return !declined
        }

        fun find(variable: Int): Path? {
            var root = variable
            var multiplier = 1L
            var translation = 0L
            val edges = ArrayList<Int>()
            while (parent[root] != root) {
                if (!visit()) return null
                translation = checkedDifferenceSum(translation, signed(offset[root], multiplier) ?: return null)
                    ?: return null
                multiplier *= sign[root]
                edges += root
                root = parent[root]
            }
            return Path(root, multiplier, translation, edges)
        }

        fun reason(first: Path, second: Path, premise: Int?): IntArray? {
            val result = LinkedHashSet<Int>()
            if (premise != null) result += premise
            for (path in listOf(first, second)) {
                for (edge in path.edges) {
                    for (literal in checkNotNull(reasons[edge])) {
                        if (!visit()) return null
                        result += literal
                    }
                }
            }
            stop.charge(result.size + 1L)
            if (stop()) return null
            return result.toIntArray()
        }

        fun join(row: Row, premise: Int?) {
            val first = find(row.first) ?: return
            val second = find(row.second) ?: return
            if (first.root == second.root) return
            val relationSign = -row.firstCoefficient * row.secondCoefficient
            val rhs = signed(row.bound, row.firstCoefficient) ?: return
            val moved = checkedDifferenceSum(signed(second.offset, relationSign) ?: return, rhs) ?: return
            val delta = checkedDifferenceSum(moved, signed(first.offset, -1L) ?: return) ?: return
            val rootSign = first.sign * relationSign * second.sign
            val rootOffset = signed(delta, first.sign) ?: return
            val proof = reason(first, second, premise) ?: return
            if (retainedPremises + proof.size > MAX_PREMISES) {
                declined = true
                return
            }
            val zero = parent.lastIndex
            // The fixed zero coordinate must remain a root even when another tree has higher rank.
            val reverse = first.root == zero || (second.root != zero && rank[first.root] > rank[second.root])
            val child = if (reverse) second.root else first.root
            val ancestor = if (reverse) first.root else second.root
            val translation = if (reverse) signed(rootOffset, -rootSign) ?: return else rootOffset
            parent[child] = ancestor
            sign[child] = rootSign
            offset[child] = translation
            reasons[child] = proof
            retainedPremises += proof.size
            if (rank[first.root] == rank[second.root]) rank[ancestor]++
        }
    }

    private data class Path(val root: Int, val sign: Long, val offset: Long, val edges: List<Int>)

    private data class Row(
        val first: Int,
        val second: Int,
        val firstCoefficient: Long,
        val secondCoefficient: Long,
        val bound: Long,
        val relation: LinearOp,
        val guard: Int,
    )

    companion object {
        private const val MAX_ROWS = 65_536
        private const val MAX_PREMISES = 65_536
        private const val MAX_VISITS = 1_048_576

        fun prepare(model: Problem, stop: Cancellation): ExactIntegerEqualityPropagation? {
            if (model.numIntVars == 0 || model.numIntVars >= MAX_ROWS) return null
            val rows = ArrayList<Row>()
            var visited = 0
            var hasEquality = false
            for (factor in model.factors) {
                stop.charge(1)
                if (++visited > MAX_VISITS || stop()) return null
                val form = factor.linearForm as? LinearForm.Conjunction ?: continue
                for (row in form.rows) {
                    stop.charge(1)
                    if (++visited > MAX_VISITS || stop()) return null
                    if (row.constants !is IntegerConstants || row.strict || row.size !in 1..2) continue
                    if (!row.isIntegerOnly || (0 until row.size).any { row.coeff(it) !in listOf(-1L, 1L) }) continue
                    val first = Term.intVar(row.ref(0))
                    val second = if (row.size == 1) model.numIntVars else Term.intVar(row.ref(1))
                    if (first !in 0 until model.numIntVars ||
                        (row.size == 2 && second !in 0 until model.numIntVars) ||
                        (row.activator != LinearRow.ALWAYS && row.activator !in 0 until model.numBoolVars)
                    ) return null
                    if (rows.size == MAX_ROWS) return null
                    stop.charge(9)
                    if (stop()) return null
                    rows += Row(
                        first, second, row.coeff(0), if (row.size == 1) -row.coeff(0) else row.coeff(1),
                        row.bound, row.relation, row.activator,
                    )
                    hasEquality = hasEquality || row.relation == LinearOp.EQ || row.relation == LinearOp.NE
                }
            }
            return if (hasEquality) ExactIntegerEqualityPropagation(rows, model.numIntVars) else null
        }

        private fun signed(value: Long, sign: Long): Long? = when {
            sign == 1L -> value
            value == Long.MIN_VALUE -> null
            else -> -value
        }
    }
}
