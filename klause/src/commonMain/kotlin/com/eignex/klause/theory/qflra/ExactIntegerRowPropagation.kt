package com.eignex.klause.theory.qflra

import com.eignex.klause.ir.IntegerConstants
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.Term
import com.eignex.klause.lp.lattice.UnitEqualityBasis
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchExplanation
import com.eignex.klause.util.Cancellation

internal class ExactIntegerRowPropagation private constructor(
    private val rows: List<Row>,
    private val observe: ((String) -> Unit)?,
) {
    fun propagate(
        context: SearchContext,
        stop: Cancellation,
        receive: (Int) -> ComponentResult = { ComponentResult.Consistent },
    ): ComponentResult? {
        stop.charge(2L * rows.size + 2L)
        if (stop()) return ComponentResult.Indeterminate
        var activeWide = false
        for (row in rows) {
            if (stop()) return ComponentResult.Indeterminate
            if (row.columns.size >= 3 && truth(row, context)?.let { equality(row, it) } == true) {
                activeWide = true
                break
            }
        }
        if (!activeWide) return null
        val basis = UnitEqualityBasis(stop)
        val coupled = BooleanArray(rows.size)
        var hasCoupled = false
        for ((index, row) in rows.withIndex()) {
            stop.charge(2L)
            if (stop()) return ComponentResult.Indeterminate
            val truth = truth(row, context) ?: continue
            if (!equality(row, truth)) continue
            val prior = basis.size
            val reduced = basis.add(row.columns, row.coefficients, row.bound, index)
            if (stop()) return ComponentResult.Indeterminate
            if (basis.declined) {
                observe?.invoke("SPARSE_EQUALITY_DECLINED_${basis.decline}")
                return null
            }
            if (basis.size > prior) {
                observe?.invoke("SPARSE_EQUALITY_PIVOT")
                if (checkNotNull(reduced).columns.size >= 3) {
                    coupled[index] = true
                    hasCoupled = true
                    observe?.invoke("SPARSE_EQUALITY_WIDE_PIVOT")
                }
            } else {
                observe?.invoke(if (reduced == null) "SPARSE_EQUALITY_ROW_DECLINED" else "SPARSE_EQUALITY_ROW_UNSTORED")
            }
        }
        if (!hasCoupled) return null
        var published = false
        for (row in rows) {
            stop.charge(2L)
            if (stop()) return ComponentResult.Indeterminate
            val reduced = basis.reduce(row.columns, row.coefficients, row.bound)
            if (stop()) return ComponentResult.Indeterminate
            if (basis.declined) {
                observe?.invoke("SPARSE_EQUALITY_DECLINED_${basis.decline}")
                break
            }
            if (reduced == null) {
                observe?.invoke("SPARSE_EQUALITY_TARGET_DECLINED")
                continue
            }
            if (reduced.columns.isNotEmpty()) continue
            val known = truth(row, context)
            val proved = when (row.relation) {
                LinearOp.EQ -> reduced.bound == 0L
                LinearOp.NE -> reduced.bound != 0L
                LinearOp.LE -> reduced.bound >= 0L
                LinearOp.GE -> reduced.bound <= 0L
            }
            if (known == proved) continue
            if (!reduced.support.any { coupled[it] }) continue
            stop.charge(10L * reduced.support.size + 8L)
            if (stop()) return ComponentResult.Indeterminate
            val reason = reason(reduced.support, context) ?: continue
            if (known != null && row.guard != LinearRow.ALWAYS) {
                val premise = Lit.make(row.guard, !known)
                if (reason.size == MAX_ROW && premise !in reason) continue
                reason += premise
            } else if (known == null && reason.size == MAX_ROW) continue
            observe?.invoke("SPARSE_EQUALITY_MATCH")
            if (stop()) return ComponentResult.Indeterminate
            if (reason.any { context.boolValue(Lit.variable(it)) != !Lit.isPositive(it) }) continue
            if (known != null) {
                observe?.invoke("SPARSE_EQUALITY_CONFLICT")
                if (stop()) return ComponentResult.Indeterminate
                if (reason.any { context.boolValue(Lit.variable(it)) != !Lit.isPositive(it) }) continue
                return ComponentResult.Conflict(SearchExplanation(reason.toIntArray()))
            }
            val literal = Lit.make(row.guard, proved)
            val result = context.imply(literal, SearchExplanation(reason.toIntArray() + literal))
            if (result !is ComponentResult.Consistent) return result
            observe?.invoke("SPARSE_EQUALITY_PUBLISHED")
            val received = receive(literal)
            if (received !is ComponentResult.Consistent) return received
            observe?.invoke("SPARSE_EQUALITY_RECEIVED")
            published = true
        }
        return when {
            stop() -> ComponentResult.Indeterminate
            published -> ComponentResult.Consistent
            else -> null
        }
    }

    private fun reason(support: IntArray, context: SearchContext): LinkedHashSet<Int>? {
        val reason = LinkedHashSet<Int>()
        for (index in support) {
            val row = rows.getOrNull(index) ?: return null
            val truth = truth(row, context) ?: return null
            if (!equality(row, truth)) return null
            if (row.guard != LinearRow.ALWAYS) reason += Lit.make(row.guard, !truth)
        }
        return reason
    }

    private fun truth(row: Row, context: SearchContext): Boolean? =
        if (row.guard == LinearRow.ALWAYS) true else context.boolValue(row.guard)

    private fun equality(row: Row, truth: Boolean): Boolean =
        (row.relation == LinearOp.EQ && truth) || (row.relation == LinearOp.NE && !truth)

    private class Row(
        val columns: IntArray,
        val coefficients: LongArray,
        val bound: Long,
        val relation: LinearOp,
        val guard: Int,
    )

    companion object {
        private const val MAX_ROWS = 65_536
        private const val MAX_TERMS = 131_072
        private const val MAX_ROW = 4_096

        fun prepare(
            model: Problem,
            stop: Cancellation,
            observe: ((String) -> Unit)? = null,
        ): ExactIntegerRowPropagation? {
            if (model.numIntVars == 0 || model.numIntVars >= MAX_ROWS || model.numRealVars != 0) return null
            val rows = ArrayList<Row>()
            var terms = 0
            var wideEquality = false
            for (factor in model.factors) {
                stop.charge(1L)
                if (stop()) return null
                val form = factor.linearForm as? LinearForm.Conjunction ?: continue
                for (row in form.rows) {
                    stop.charge(1L)
                    if (stop()) return null
                    if (row.constants !is IntegerConstants || row.strict || !row.isIntegerOnly ||
                        row.size !in 1..MAX_ROW
                    ) continue
                    if (rows.size >= MAX_ROWS || row.size > MAX_TERMS - terms) return null
                    if (row.activator != LinearRow.ALWAYS && row.activator !in 0 until model.numBoolVars) return null
                    stop.charge(5L * row.size + 6L)
                    if (stop()) return null
                    val columns = IntArray(row.size) { Term.intVar(row.ref(it)) }
                    val coefficients = LongArray(row.size) { row.coeff(it) }
                    if (columns.any { it !in 0 until model.numIntVars }) return null
                    if (coefficients.any { it == 0L } || columns.toSet().size != columns.size) continue
                    rows += Row(columns, coefficients, row.bound, row.relation, row.activator)
                    terms += row.size
                    wideEquality = wideEquality || (row.size >= 3 &&
                        (row.relation == LinearOp.EQ || row.relation == LinearOp.NE))
                }
            }
            return if (wideEquality && !stop()) ExactIntegerRowPropagation(rows, observe) else null
        }
    }
}
