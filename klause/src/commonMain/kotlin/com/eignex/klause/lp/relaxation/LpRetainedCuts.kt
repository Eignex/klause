package com.eignex.klause.lp.relaxation

import com.eignex.klause.lp.cut.SourceCut
import com.eignex.klause.lp.cut.orNull
import com.eignex.klause.lp.engine.Cut
import com.eignex.klause.lp.engine.CutProvenance
import com.eignex.klause.lp.engine.ExactLpBounds
import com.eignex.klause.lp.engine.ExactLpColumn
import com.eignex.klause.lp.engine.ExactLpNumber
import com.eignex.klause.lp.engine.ExactLpRow
import com.eignex.klause.lp.engine.ExactLpSide
import com.eignex.klause.lp.engine.LpExactState
import com.eignex.klause.lp.engine.LpLayoutRemap
import com.eignex.klause.lp.engine.LpScopedRow
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation

internal class LpCutEdit(
    val sourceState: LpExactState,
    val retired: Set<Long>,
    val rows: List<LpScopedRow>,
    private val valid: () -> Boolean,
    private val publish: () -> Unit,
) {
    val emittedExtent: Long get() = rows.sumOf { it.coefficientCount.toLong() + 1L }
    fun isCurrent(): Boolean = valid()
    fun commit() = publish()
}

internal class LpRetainedCuts {
    private class Binding(val id: Long, val source: SourceCut, val numeric: Cut) {
        val numericKey: String = numeric.key()
        val proof: CutProvenance get() = requireNotNull(numeric.provenance)
        val storageUnits: Long = numeric.cols.size.toLong() * 2L + numericKey.length +
            source.expression.storageUnits + proof.storageUnits + 3L
    }
    private class Change(val depth: Int, val previous: List<Binding>)
    private var bindings = emptyList<Binding>()
    private val trail = ArrayList<Change>()
    private var generation = 0L
    var depth: Int = 0
        private set
    var storageUnits: Long = 0L
        private set

    fun retract(targetDepth: Int) {
        require(targetDepth in 0..depth)
        check(generation < Long.MAX_VALUE)
        while (trail.isNotEmpty() && trail.last().depth > targetDepth) {
            bindings = trail.removeAt(trail.lastIndex).previous
        }
        depth = targetDepth
        updateStorage()
        generation++
    }

    private fun updateStorage() {
        val unique = HashSet<Binding>()
        unique.addAll(bindings)
        trail.forEach { unique.addAll(it.previous) }
        storageUnits = unique.sumOf { it.storageUnits } + bindings.size + trail.sumOf { it.previous.size.toLong() + 2L }
    }

    fun parentRows(state: LpExactState): Map<Int, CutProvenance> {
        if (bindings.isEmpty()) return emptyMap()
        val indices = (0 until state.rows.size).associateBy { state.rows.row(it).id }
        return bindings.associate { binding ->
            val row = requireNotNull(indices[binding.id])
            check(state.rows.row(row).active)
            row to binding.proof
        }
    }

    fun prepareCompaction(
        state: LpExactState,
        remap: LpLayoutRemap,
        cancellation: Cancellation = Cancellation.Never,
    ): LpCutEdit? {
        require(remap.matches(state.model, state.rows))
        check(generation < Long.MAX_VALUE)
        val expectedGeneration = generation
        val mapped = HashMap<Binding, Binding>()
        fun binding(previous: Binding): Binding = mapped.getOrPut(previous) {
            val row = state.rows.index(previous.id)
            check(row >= 0 && remap.row(row) >= 0)
            val cut = previous.numeric
            val columns = IntArray(cut.cols.size) { remap.column(cut.cols[it]).also { column -> check(column >= 0) } }
            if (columns.contentEquals(cut.cols)) previous else {
                Binding(previous.id, previous.source, Cut(columns, cut.coeffs, cut.rel, cut.rhs,
                    cut.global, previous.proof))
            }
        }
        val next = bindings.map {
            if (cancellation()) return null
            binding(it)
        }
        val changes = trail.map { change ->
            if (cancellation()) return null
            Change(change.depth, change.previous.map(::binding))
        }
        if (cancellation()) return null
        return LpCutEdit(state, emptySet(), emptyList(), valid = { generation == expectedGeneration }) {
            check(generation == expectedGeneration) { "stale cut compaction" }
            bindings = next
            trail.clear()
            trail.addAll(changes)
            updateStorage()
            generation++
        }
    }

    fun prepare(
        state: LpExactState,
        relaxation: LpRelaxation,
        selected: List<Cut>? = null,
        cancellation: Cancellation = Cancellation.Never,
    ): LpCutEdit? {
        require(state.depth >= depth)
        val map = relaxation.sourceMap ?: return null
        check(generation < Long.MAX_VALUE)
        val expectedGeneration = generation
        val requested = if (selected == null) {
            bindings.map { it.source }
        } else {
            selected.map { cut ->
                if (cancellation()) return null
                SourceCut.fromCut(cut, relaxation).orNull() ?: return null
            }
        }
        val existing = bindings.associateBy { it.source.key }
        val next = ArrayList<Binding>()
        val rows = ArrayList<LpScopedRow>()
        val seen = HashSet<String>()
        var lastId = state.rows.lastId
        for (source in requested) {
            if (cancellation()) return null
            val cut = source.toCut(map).orNull()
            if (cut == null) {
                if (selected != null) return null
                continue
            }
            val key = source.key
            if (!seen.add(key)) continue
            val previous = existing[key]
            val active = previous?.let { state.rows.index(it.id) }?.let { it >= 0 && state.rows.row(it).active } == true
            val priorCut = if (previous != null && active) previous.source.toCut(map).orNull() else null
            if (previous != null && priorCut != null && priorCut.key() == cut.key() &&
                previous.numericKey == cut.key()
            ) {
                next.add(previous)
                continue
            }
            check(lastId < Long.MAX_VALUE)
            val id = ++lastId
            val proof = requireNotNull(cut.provenance)
            rows.add(row(state, id, cut, proof))
            next.add(Binding(id, source, cut))
        }
        val kept = next.mapTo(HashSet()) { it.id }
        val retired = bindings.map { it.id }.filterTo(HashSet()) { it !in kept }
        val changed = next != bindings
        return LpCutEdit(state, retired, rows, valid = { generation == expectedGeneration }) {
            check(generation == expectedGeneration) { "stale cut edit" }
            if (changed && state.depth > 0 && trail.lastOrNull()?.depth != state.depth) {
                trail.add(Change(state.depth, bindings))
            }
            bindings = next.toList()
            depth = state.depth
            if (changed) updateStorage()
            generation++
        }
    }

    private fun row(state: LpExactState, id: Long, cut: Cut, proof: CutProvenance): LpScopedRow {
        val coefficients = LinkedHashMap<Int, BigFraction>()
        val sign = if (cut.rel == Relation.GE) BigFraction.MINUS_ONE else BigFraction.ONE
        var rhs = BigFraction.ofLong(cut.rhs) * sign
        for (index in cut.cols.indices) {
            val column = cut.cols[index]
            val coefficient = BigFraction.ofLong(cut.coeffs[index]) * sign
            coefficients[column] = (coefficients[column] ?: BigFraction.ZERO) + coefficient
            rhs -= coefficient * state.model.column(column).origin.value
        }
        val zero = ExactLpSide(ExactLpNumber.of(0L))
        val integral = rhs.den == BIG_ONE && coefficients.all { (column, coefficient) ->
            coefficient.den == BIG_ONE && state.model.column(column).integral
        }
        return LpScopedRow(
            id,
            coefficients.filterValues { !it.isZero }.entries.sortedBy { it.key }
                .map { it.key to ExactLpNumber.of(it.value) },
            ExactLpNumber.of(rhs),
            ExactLpColumn(ExactLpBounds(zero, if (cut.rel == Relation.EQ) zero else null), integral = integral),
            ExactLpRow(global = proof.global),
        )
    }
}
