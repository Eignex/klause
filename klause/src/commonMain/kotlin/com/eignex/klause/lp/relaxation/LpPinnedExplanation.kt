package com.eignex.klause.lp.relaxation

import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.engine.exactBounds
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet

internal class LpPinnedExplanation private constructor(
    private val original: LpRelaxation,
    rows: List<IntArray>,
    private val problem: Problem,
    private val booleanVariables: IntArray,
    private val bools: BooleanArray,
    private val integerVariables: IntArray,
    private val ints: LongArray,
    private val onDecline: ((String) -> Unit)?,
) {
    private val rows = rows.map(IntArray::copyOf)

    companion object {
        fun capture(
            original: LpRelaxation,
            rows: List<IntArray>,
            session: PropagationSession,
            cancellation: Cancellation,
            onDecline: ((String) -> Unit)? = null,
            onWork: (Long) -> Unit,
        ): LpPinnedExplanation? {
            val problem = session.problem
            val source = original.model.exactState?.model ?: return null
            val entries = rows.sumOf { it.size.toLong() }
            if (source.n > 10000 || source.m > 10000 || original.colVarId.size != source.n ||
                original.colIsBool.size != source.n
            ) {
                onDecline?.invoke("SOURCE_LAYOUT")
                return null
            }
            if (entries > 65536L) {
                onDecline?.invoke("MAPPING_STORAGE")
                return null
            }
            fun stopped(units: Long): Boolean {
                onWork(units)
                cancellation.charge(units)
                return cancellation()
            }
            if (stopped(8L * (source.n + source.m + entries + rows.size + 1L))) return null
            val booleanIds = IntHashSet()
            val integerIds = IntHashSet()
            fun remember(variable: Int, boolean: Boolean): Boolean {
                val ids = if (boolean) booleanIds else integerIds
                val count = if (boolean) problem.numBoolVars else problem.numIntVars
                if (variable !in 0 until count) {
                    onDecline?.invoke("SOURCE_COORDINATE")
                    return false
                }
                if (variable in ids) return true
                if (booleanIds.size + integerIds.size >= 4096) {
                    onDecline?.invoke("PREFIX_LIMIT_B_${booleanIds.size}_I_${integerIds.size}")
                    return false
                }
                ids.add(variable)
                return true
            }
            for (column in 0 until source.n) {
                val variable = original.colVarId[column]
                if (variable >= 0 && !remember(variable, original.colIsBool[column])) return null
            }
            val visited = BooleanArray(source.m)
            var premiseEntries = 0L
            for (parents in rows) {
                for (row in parents) {
                    if (row !in visited.indices) {
                        onDecline?.invoke("ROW_MAPPING")
                        return null
                    }
                    if (visited[row]) continue
                    visited[row] = true
                    val metadata = source.row(row)
                    if (metadata.global) continue
                    val premises = metadata.premises ?: continue
                    premiseEntries += premises.size
                    if (premiseEntries > 65536L) {
                        onDecline?.invoke("PREMISE_STORAGE")
                        return null
                    }
                    if (stopped(4L * premises.size + 1L)) return null
                    for (bound in premises.boundEntries()) if (!remember(bound.variable, false)) return null
                    for (literal in premises.literalEntries()) if (!remember(Lit.variable(literal), true)) return null
                }
            }
            val count = booleanIds.size.toLong() + integerIds.size
            if (stopped(8L * (count + 1L))) return null
            val booleanVariables = booleanIds.toIntArray()
            val integerVariables = integerIds.toIntArray()
            val bools = BooleanArray(booleanVariables.size) { session.boolValue(booleanVariables[it]) ?: return null }
            val ints = LongArray(integerVariables.size) {
                val domain = session.intDomain(integerVariables[it])
                if (!domain.isFixed) return null
                domain.min
            }
            if (cancellation()) return null
            return LpPinnedExplanation(original, rows, problem, booleanVariables, bools,
                integerVariables, ints, onDecline)
        }
    }
    fun clause(
        support: IntArray,
        session: PropagationSession,
        cancellation: Cancellation,
        onWork: (Long) -> Unit,
    ): IntArray? {
        val model = original.model
        val source = model.exactState?.model ?: return null
        if (session.problem !== problem || source.n > 10000 || source.m > 10000 ||
            support.any { it !in rows.indices }
        ) return null
        fun stopped(units: Long): Boolean {
            onWork(units)
            cancellation.charge(units)
            return cancellation()
        }
        if (stopped(4L * (source.n + source.m + support.size + bools.size + ints.size) + 1L)) return null
        for (index in bools.indices) if (session.boolValue(booleanVariables[index]) != bools[index]) return null
        for (index in ints.indices) {
            val domain = session.intDomain(integerVariables[index])
            if (!domain.isFixed || domain.min != ints[index]) return null
        }
        val selected = BooleanArray(source.m)
        var entries = 0L
        for (row in support) {
            entries += rows[row].size
            if (entries > 65536L || stopped(rows[row].size.toLong() + 1L)) return null
            for (parent in rows[row]) {
                if (parent !in selected.indices) return null
                selected[parent] = true
            }
        }
        val parents = selected.indices.filter { selected[it] }.toIntArray()
        val literals = IntArrayList()
        val seen = IntHashSet()
        var rowDecline: String? = null
        if (!LpExplanation.addRowPremiseLits(literals, seen, original, parents, session, { rowDecline = it })) {
            onDecline?.invoke(rowDecline ?: "ROW_PREMISES")
            return null
        }
        for (column in 0 until source.n) {
            val columnEntries = source.columnEntries(column)
            entries += columnEntries.size
            if (entries > 65536L || stopped(columnEntries.size.toLong() + 4L)) return null
            if (parents.isNotEmpty() && columnEntries.none { selected[it.row] }) continue
            val bounds = model.exactBounds(column)
            for (lower in listOf(true, false)) {
                if ((if (lower) bounds.lower else bounds.upper) == null) continue
                val premises = LpExplanation.boundPremiseLits(original, session, column, lower) ?: run {
                    onDecline?.invoke(if (original.colRealId[column] >= 0) "REAL_BOUND" else "STRUCTURAL_BOUND")
                    return null
                }
                if (stopped(premises.size.toLong() + 1L)) return null
                for (literal in premises) if (seen.add(literal)) literals.add(literal)
            }
        }
        if (stopped(literals.size.toLong() + 1L)) return null
        if ((0 until literals.size).any { session.litTruth(literals[it]) != false }) {
            onDecline?.invoke("LITERAL_TRUTH")
            return null
        }
        return literals.toIntArray()
    }
}
