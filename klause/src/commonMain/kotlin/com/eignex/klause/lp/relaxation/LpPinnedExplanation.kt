package com.eignex.klause.lp.relaxation

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
    private val bools: BooleanArray,
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
            val count = problem.numBoolVars.toLong() + problem.numIntVars
            val entries = rows.sumOf { it.size.toLong() }
            if (count > 4096L) {
                onDecline?.invoke("PREFIX_LIMIT_B_${problem.numBoolVars}_I_${problem.numIntVars}")
                return null
            }
            if (entries > 65536L) {
                onDecline?.invoke("MAPPING_STORAGE")
                return null
            }
            val units = 4L * (count + entries + rows.size + 1L)
            onWork(units)
            cancellation.charge(units)
            if (cancellation()) return null
            val bools = BooleanArray(problem.numBoolVars) { session.boolValue(it) ?: return null }
            val ints = LongArray(problem.numIntVars) {
                val domain = session.intDomain(it)
                if (!domain.isFixed) return null
                domain.min
            }
            if (cancellation()) return null
            return LpPinnedExplanation(original, rows, problem, bools, ints, onDecline)
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
        for (variable in bools.indices) if (session.boolValue(variable) != bools[variable]) return null
        for (variable in ints.indices) {
            val domain = session.intDomain(variable)
            if (!domain.isFixed || domain.min != ints[variable]) return null
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
