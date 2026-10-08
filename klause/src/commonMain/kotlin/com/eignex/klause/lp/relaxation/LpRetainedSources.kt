package com.eignex.klause.lp.relaxation

import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.cut.CircuitArcModel
import com.eignex.klause.lp.engine.CutAuxiliaryDefinition
import com.eignex.klause.lp.engine.CutSource
import com.eignex.klause.lp.engine.CutSourceKind
import com.eignex.klause.lp.engine.ExactLpBounds
import com.eignex.klause.lp.engine.ExactLpColumn
import com.eignex.klause.lp.engine.ExactLpModel
import com.eignex.klause.lp.engine.ExactLpNumber
import com.eignex.klause.lp.engine.ExactLpObjective
import com.eignex.klause.lp.engine.ExactLpSide
import com.eignex.klause.lp.engine.LpExactState
import com.eignex.klause.lp.engine.LpLayoutRemap
import com.eignex.klause.lp.engine.LpLayoutStorage
import com.eignex.klause.lp.engine.LpLayoutWeight
import com.eignex.klause.lp.engine.LpScopedRows
import com.eignex.klause.lp.engine.LpScopedRow
import com.eignex.klause.lp.engine.LpStructuralColumn
import com.eignex.klause.lp.engine.Sense
import com.eignex.klause.lp.engine.authoritativeModel
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation

internal class LpSourceEdit(
    val sourceState: LpExactState,
    val retired: Set<Long>,
    val columns: List<LpStructuralColumn>,
    val rows: List<LpScopedRow>,
    val permanentRows: Set<Long>,
    val objective: ExactLpObjective?,
    val bounds: List<ExactLpBounds>,
    val emittedExtent: Long,
    private val identify: (Int) -> CutSource?,
    private val valid: () -> Boolean,
    private val publish: () -> Unit,
) {
    fun source(column: Int): CutSource? = identify(column)
    fun isCurrent(): Boolean = valid()
    fun commit() = publish()
}

internal class LpSourceCompaction(
    val sourceState: LpExactState,
    val remap: LpLayoutRemap,
    val extent: Long,
    private val valid: () -> Boolean,
    private val publish: () -> Unit,
) {
    fun isCurrent(): Boolean = valid()
    fun commit() = publish()
}

internal class LpRetainedSources(
    private val problem: Problem,
    relaxer: CpToLpRelaxation,
    private val auxiliarySources: LpAuxiliarySources = relaxer.auxiliarySources,
) {
    private sealed interface ColumnKey {
        data class Source(val kind: CutSourceKind, val variable: Int, val sign: Int = 1) : ColumnKey
        data class Defined(val definition: CutAuxiliaryDefinition) : ColumnKey
        data class Anonymous(val epoch: Long, val column: Int) : ColumnKey
    }

    private class Column(
        val source: ExactLpColumn,
        val cost: ExactLpNumber,
        val variable: Int,
        val boolean: Boolean,
        val real: Int,
        val sign: Int,
        val required: LongArray?,
        val presentUpper: Long,
        val definition: CutAuxiliaryDefinition?,
    ) {
        val storageUnits: Long = 10L + (required?.size ?: 0) + (definition?.storageUnits ?: 0L)
    }

    private class Binding(val emission: LpEmission, val columns: IntArray, val rows: LongArray) {
        val storageUnits: Long = columns.size.toLong() + rows.size + emission.relaxation.model.let { model ->
            model.n.toLong() * 16L + model.m.toLong() * 16L +
                (model.doubleView?.colVal?.size ?: model.csc.rowIdx.size).toLong() * 3L +
                model.rowPremises.sumOf { (it?.vars?.size ?: 0).toLong() * 3L + (it?.boolLits?.size ?: 0) }
        }
    }
    private class Change(val depth: Int, val index: Int, val previous: Binding?)
    private class CompactionView(
        val columns: List<Column>,
        val uses: IntArray,
        val rows: LpScopedRows,
        val storage: LpLayoutStorage,
        val kept: List<Int>,
        val weight: LpLayoutWeight,
        val removedDescriptors: Long,
        val definitions: Set<CutAuxiliaryDefinition>,
    ) {
        private var catalogGeneration = -1L
        private var catalogWeight = LpLayoutWeight(0L, 0L)

        fun weight(catalog: LpAuxiliarySources): LpLayoutWeight {
            if (catalogGeneration != catalog.generation) {
                catalogWeight = catalog.storageWeight(definitions)
                catalogGeneration = catalog.generation
            }
            return LpLayoutWeight(weight.retained + catalogWeight.retained, weight.retired + catalogWeight.retired)
        }
    }

    private val emissions = LpEmissionCache(relaxer)
    private var columns = emptyList<Column>()
    private var handles = emptyMap<ColumnKey, Int>()
    private var columnUses = IntArray(0)
    private var unusedColumns = 0
    private var ownedStorageUnits = 0L
    private var compactionView: CompactionView? = null
    private var realDefinitions = emptyMap<Int, Long>()
    private val bindings = arrayOfNulls<Binding>(relaxer.emissionRegions.size)
    private val trail = ArrayList<Change>()
    private var generation = 0L
    private var columnEpoch = 0L
    private var cached: LpRelaxation? = null
    val depth: Int get() = emissions.depth
    val emittedRegions: Long get() = emissions.emittedRegions

    fun retract(targetDepth: Int) {
        require(targetDepth in 0..depth)
        check(generation < Long.MAX_VALUE)
        while (trail.isNotEmpty() && trail.last().depth > targetDepth) {
            val change = trail.removeAt(trail.lastIndex)
            ownedStorageUnits -= (bindings[change.index]?.storageUnits ?: 0L) + 3L
            bindings[change.index]?.columns?.forEach { column ->
                check(columnUses[column] > 0)
                if (--columnUses[column] == 0 && columns[column].reclaimable()) unusedColumns++
            }
            bindings[change.index] = change.previous
        }
        emissions.retract(targetDepth)
        generation++
        cached = null
        compactionView = null
    }

    fun prepare(
        state: LpExactState,
        domains: RelaxationDomains,
        cancellation: Cancellation = Cancellation.Never,
    ): LpSourceEdit {
        require(state.depth >= depth && state.model.n == columns.size)
        check(generation < Long.MAX_VALUE)
        val expectedGeneration = generation
        val update = emissions.prepare(domains, state.depth, cancellation)
        if (update.changed.isEmpty()) {
            return LpSourceEdit(
                state, emptySet(), emptyList(), emptyList(), emptySet(), null, liveBounds(domains), 0L,
                identify = { columns[it].cpSource() },
                valid = { generation == expectedGeneration },
            ) {
                check(generation == expectedGeneration) { "stale source edit" }
                update.commit()
                generation++
            }
        }
        val staged = columns.toMutableList()
        val keys = handles.toMutableMap()
        val definitions = realDefinitions.toMutableMap()
        val next = bindings.copyOf()
        val added = ArrayList<LpStructuralColumn>()
        val rows = ArrayList<LpScopedRow>()
        val permanent = HashSet<Long>()
        val retired = HashSet<Long>()
        val changes = ArrayList<Change>()
        val saved = trail.asReversed().takeWhile { it.depth == state.depth }.mapTo(HashSet()) { it.index }
        var lastId = state.rows.lastId
        var epoch = columnEpoch
        var constant = state.model.objective.constant.value
        var emittedExtent = 0L
        for (index in update.changed) {
            if (cancellation()) throw LpAssemblyCancelled()
            check(epoch < Long.MAX_VALUE)
            val emission = update.emissions[index]
            val fragment = emission.relaxation
            emittedExtent += fragment.model.n.toLong() + fragment.model.m +
                (fragment.model.doubleView?.colVal?.size ?: fragment.model.csc.rowIdx.size)
            val source = requireNotNull(fragment.model.authoritativeModel())
            require(source.objective.scale.value == BigFraction.ONE && source.objective.externalConstant.value.isZero)
            val mapped = IntArray(source.n)
            for (local in mapped.indices) {
                val key = columnKey(fragment, local, epoch)
                val old = keys[key]
                if (old != null) {
                    // Integer and mixed emitters can encode the same coordinate in Long and IEEE forms.
                    check(staged[old].source.origin.value == source.column(local).origin.value)
                    check(staged[old].cost.value == source.objective.cost(local).value)
                    mapped[local] = old
                } else {
                    val definition = fragment.colPresence[local]
                    val column = if (fragment.colReq[local] == null) {
                        source.column(local)
                    } else {
                        source.column(local).copy(bounds = ExactLpBounds(
                            ExactLpSide(ExactLpNumber.of(0L)),
                            ExactLpSide(ExactLpNumber.of(fragment.colPresentUpper[local])),
                        ))
                    }
                    val cost = source.objective.cost(local)
                    mapped[local] = staged.size
                    keys[key] = staged.size
                    staged.add(Column(
                        column, cost, fragment.colVarId[local], fragment.colIsBool[local],
                        fragment.colRealId[local], fragment.colRealSign[local], fragment.colReq[local]?.copyOf(),
                        fragment.colPresentUpper[local], definition,
                    ))
                    added.add(LpStructuralColumn(column, cost))
                    constant += cost.value * column.origin.value
                }
            }
            epoch++
            val rowTerms = Array(source.m) { ArrayList<Pair<Int, ExactLpNumber>>() }
            val rhs = List(source.m) { source.rhs(it) }.toMutableList()
            for (local in mapped.indices) {
                val global = mapped[local]
                val delta = source.column(local).origin.value - staged[global].source.origin.value
                for (entry in source.entries(local)) {
                    rowTerms[entry.row].add(global to entry.number)
                    if (!delta.isZero) {
                        rhs[entry.row] = ExactLpNumber.of(rhs[entry.row].value + entry.number.value * delta)
                    }
                }
            }
            val rowIds = LongArray(source.m)
            for (local in rowIds.indices) {
                val real = fragment.realUpperRows[local]
                val existing = real?.let(definitions::get)
                if (existing != null) {
                    rowIds[local] = existing
                    continue
                }
                check(lastId < Long.MAX_VALUE)
                val id = ++lastId
                val row = LpScopedRow(
                    id, rowTerms[local].sortedBy { it.first }, rhs[local], source.column(source.n + local),
                    source.row(local), source.objective.cost(source.n + local),
                )
                rows.add(row)
                rowIds[local] = id
                if (real != null) {
                    definitions[real] = id
                    permanent.add(id)
                }
            }
            val previous = bindings[index]
            previous?.let {
                for (local in it.rows.indices) {
                    if (local !in it.emission.relaxation.realUpperRows) retired.add(it.rows[local])
                }
            }
            if (state.depth > 0 && saved.add(index)) changes.add(Change(state.depth, index, previous))
            next[index] = Binding(emission, mapped, rowIds)
        }
        if (cancellation()) throw LpAssemblyCancelled()
        val uses = columnUses.copyOf(staged.size)
        var owned = ownedStorageUnits + staged.drop(columns.size).sumOf { it.storageUnits }
        var unused = unusedColumns + (columns.size until staged.size).count { staged[it].reclaimable() }
        fun reference(binding: Binding?, delta: Int) {
            binding?.columns?.forEach { column ->
                val previous = uses[column]
                val next = previous + delta
                check(next >= 0)
                if (staged[column].reclaimable()) {
                    if (previous == 0) unused--
                    if (next == 0) unused++
                }
                uses[column] = next
            }
        }
        for (index in update.changed) {
            owned += requireNotNull(next[index]).storageUnits - (bindings[index]?.storageUnits ?: 0L)
            reference(bindings[index], -1)
            reference(next[index], 1)
        }
        changes.forEach { reference(it.previous, 1) }
        owned += changes.sumOf { (it.previous?.storageUnits ?: 0L) + 3L }
        val objective = if (added.isEmpty()) null else ExactLpObjective(
            List(state.model.n) { state.model.objective.cost(it) } + added.map { it.cost } +
                List(state.model.m) { state.model.objective.cost(state.model.n + it) } + rows.map { it.cost },
            ExactLpNumber.of(constant), sense = Sense.MINIMIZE,
        )
        return LpSourceEdit(
            state, retired, added, rows, permanent, objective, liveBounds(staged, domains), emittedExtent,
            identify = { staged[it].cpSource() },
            valid = { generation == expectedGeneration },
        ) {
            check(generation == expectedGeneration) { "stale source edit" }
            update.commit()
            columns = staged.toList()
            handles = keys.toMap()
            columnUses = uses
            unusedColumns = unused
            ownedStorageUnits = owned
            realDefinitions = definitions.toMap()
            next.copyInto(bindings)
            trail.addAll(changes)
            columnEpoch = epoch
            generation++
            cached = null
            compactionView = null
        }
    }

    private fun Column.reclaimable(): Boolean = variable < 0 && real < 0 && cost.value.isZero

    fun prepareCompaction(
        state: LpExactState,
        cancellation: Cancellation = Cancellation.Never,
        retainedOverhead: Long = 0L,
    ): LpSourceCompaction? {
        require(state.model.n == columns.size && state.depth == depth)
        require(retainedOverhead >= 0L)
        val rows = state.rows
        if (rows.retiredCount == 0 && unusedColumns == 0 && auxiliarySources.size == 0) return null
        if (cancellation()) return null
        check(generation < Long.MAX_VALUE)
        val expectedGeneration = generation
        val catalogGeneration = auxiliarySources.generation
        val view = compactionView?.takeIf {
            it.columns === columns && it.uses === columnUses && it.rows === rows &&
                it.storage === state.model.layoutStorage
        } ?: compactionView(state, cancellation)?.also { compactionView = it } ?: return null
        val overhead = ownedStorageUnits - view.removedDescriptors + retainedOverhead + state.trailStorageUnits
        if (!view.weight(auxiliarySources).warrantsCompaction(overhead)) return null
        val kept = view.kept
        var extent = state.model.layoutStorage.total + ownedStorageUnits + retainedOverhead + state.trailStorageUnits +
            auxiliarySources.storageUnits
        val remap = LpLayoutRemap(columns.size, rows, kept)
        val indices = (0 until rows.size).associateBy { rows.row(it).id }
        fun binding(previous: Binding?): Binding? = previous?.let {
            extent += it.columns.size + it.rows.size
            check(it.rows.all { id -> indices[id]?.let { row -> remap.row(row) >= 0 } == true })
            Binding(it.emission, IntArray(it.columns.size) { index ->
                remap.column(it.columns[index]).also { column -> check(column >= 0) }
            }, it.rows)
        }
        val next = Array(bindings.size) { binding(bindings[it]) }
        val changes = trail.map { Change(it.depth, it.index, binding(it.previous)) }
        val descriptors = kept.map { columns[it] }
        val keys = handles.mapNotNull { (key, column) ->
            remap.column(column).takeIf { it >= 0 }?.let { key to it }
        }.toMap()
        val uses = kept.map { columnUses[it] }.toIntArray()
        val unused = descriptors.indices.count { descriptors[it].reclaimable() && uses[it] == 0 }
        if (cancellation()) return null
        return LpSourceCompaction(state, remap, extent, valid = {
            generation == expectedGeneration && auxiliarySources.generation == catalogGeneration
        }) {
            check(generation == expectedGeneration) { "stale source compaction" }
            check(auxiliarySources.generation == catalogGeneration) { "stale auxiliary compaction" }
            auxiliarySources.retain(view.definitions)
            columns = descriptors
            handles = keys
            columnUses = uses
            unusedColumns = unused
            ownedStorageUnits -= view.removedDescriptors
            next.copyInto(bindings)
            trail.clear()
            trail.addAll(changes)
            generation++
            cached = null
            compactionView = null
        }
    }

    private fun compactionView(state: LpExactState, cancellation: Cancellation): CompactionView? {
        val rows = state.rows
        val kept = ArrayList<Int>()
        var removed = 0L
        for (column in columns.indices) {
            if (cancellation()) return null
            if (!columns[column].reclaimable() || columnUses[column] > 0 || state.model.columnEntries(column).any {
                    !it.number.value.isZero && (rows.row(it.row).active || rows.row(it.row).suspendedAt != null)
                }
            ) {
                kept.add(column)
            } else {
                removed += columns[column].storageUnits
            }
        }
        val storage = state.model.layoutStorage
        val numeric = rows.storageWeight(storage).retireColumns(columns.size - kept.size)
        return CompactionView(columns, columnUses, rows, storage, kept,
            LpLayoutWeight(numeric.retained, numeric.retired + removed), removed,
            kept.mapNotNullTo(HashSet()) { columns[it].definition })
    }

    private fun Column.cpSource(): CutSource? = if (variable < 0) null else {
        CutSource(if (boolean) CutSourceKind.BOOLEAN else CutSourceKind.INTEGER, variable)
    }

    private fun columnKey(fragment: LpRelaxation, column: Int, epoch: Long): ColumnKey = when {
        fragment.colVarId[column] >= 0 -> ColumnKey.Source(
            if (fragment.colIsBool[column]) CutSourceKind.BOOLEAN else CutSourceKind.INTEGER, fragment.colVarId[column],
        )
        fragment.colRealId[column] >= 0 -> ColumnKey.Source(
            CutSourceKind.REAL, fragment.colRealId[column], fragment.colRealSign[column],
        )
        fragment.colPresence[column] != null -> ColumnKey.Defined(requireNotNull(fragment.colPresence[column]))
        else -> ColumnKey.Anonymous(epoch, column)
    }

    fun liveBounds(domains: RelaxationDomains): List<ExactLpBounds> = liveBounds(columns, domains)

    private fun liveBounds(columns: List<Column>, domains: RelaxationDomains): List<ExactLpBounds> = columns.map { column ->
        val origin = column.source.origin.value
        when {
            column.variable >= 0 && column.boolean -> {
                val pin = domains.boolValue(column.variable)
                ExactLpBounds(
                    ExactLpSide(ExactLpNumber.of((if (pin == true) BigFraction.ONE else BigFraction.ZERO) - origin)),
                    ExactLpSide(ExactLpNumber.of((if (pin == false) BigFraction.ZERO else BigFraction.ONE) - origin)),
                )
            }
            column.variable >= 0 -> {
                val domain = domains.intDomain(column.variable)
                ExactLpBounds(
                    ExactLpSide(ExactLpNumber.of(BigFraction.ofLong(domain.min) - origin)),
                    if (domains.honorsOpenSides && column.source.bounds.upper == null) null else {
                        ExactLpSide(ExactLpNumber.of(BigFraction.ofLong(domain.max) - origin))
                    },
                )
            }
            column.required != null -> {
                val present = column.required.indices.step(2).all {
                    domains.intDomain(column.required[it].toInt()).contains(column.required[it + 1])
                }
                ExactLpBounds(
                    ExactLpSide(ExactLpNumber.of(0L)),
                    ExactLpSide(ExactLpNumber.of(if (present) column.presentUpper else 0L)),
                )
            }
            else -> column.source.bounds
        }
    }

    fun relaxation(state: LpExactState, domains: RelaxationDomains): LpRelaxation {
        require(state.model.n == columns.size)
        val model = requireNotNull(state.ownerWorkingModel())
        cached?.let { return it.withModel(model) }
        val vars = columns.map { it.variable }.toIntArray()
        val bools = BooleanArray(columns.size) { columns[it].boolean }
        val reals = columns.map { it.real }.toIntArray()
        val signs = columns.map { it.sign }.toIntArray()
        val integerColumns = IntArray(problem.numIntVars) { -1 }
        val booleanColumns = IntArray(problem.numBoolVars) { -1 }
        for (index in columns.indices) {
            val column = columns[index]
            if (column.variable >= 0) {
                (if (column.boolean) booleanColumns else integerColumns)[column.variable] = index
            }
        }
        val factors = IntArray(state.model.m) { -1 }
        val indices = (0 until state.rows.size).associateBy { state.rows.row(it).id }
        val producers = HashSet<Int>()
        val circuits = ArrayList<CircuitArcModel>()
        val hulls = LinkedHashSet<Int>()
        for (binding in bindings.filterNotNull()) {
            val fragment = binding.emission.relaxation
            binding.columns.forEach(producers::add)
            for (local in binding.rows.indices) {
                if (local !in fragment.realUpperRows) {
                    factors[requireNotNull(indices[binding.rows[local]])] = fragment.rowFactorIds[local]
                }
            }
            hulls.addAll(fragment.hullFactorIds.toList())
            for (circuit in fragment.circuitArcs) {
                circuits.add(CircuitArcModel(
                    circuit.n, circuit.tails, circuit.heads,
                    IntArray(circuit.cols.size) { binding.columns[circuit.cols[it]] },
                ))
            }
        }
        val presence = List(columns.size) { if (it in producers) columns[it].definition else null }
        return LpRelaxation(
            model, vars, bools, bindings.filterNotNull().firstOrNull()?.emission?.relaxation?.objectiveConstant ?: 0L,
            integerColumns, booleanColumns, circuits,
            colReq = Array(columns.size) { columns[it].required?.copyOf() },
            colPresentUpper = LongArray(columns.size) { columns[it].presentUpper }, hullFactorIds = hulls.toIntArray(),
            colRealId = reals, colRealSign = signs,
            sourceMap = cpCutSources(
                model, problem, vars, bools, reals, signs, emptyMap(), presence, auxiliarySources, domains,
            ),
            colPresence = presence, rowFactorIds = factors,
            realUpperRows = realDefinitions.entries.associate { requireNotNull(indices[it.value]) to it.key },
        ).also { cached = it }
    }

    companion object {
        fun emptyModel(): ExactLpModel = ExactLpModel(
            emptyList(), emptyList(), emptyList(), emptyList(), ExactLpObjective(emptyList()),
        )
    }
}
