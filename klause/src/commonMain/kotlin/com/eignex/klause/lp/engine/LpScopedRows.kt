package com.eignex.klause.lp.engine

internal data class LpRowIdentity(
    val id: Long,
    val depth: Int?,
    val active: Boolean = true,
    val suspendedAt: Int? = null,
)

internal class LpScopedRows(entries: List<LpRowIdentity>, val lastId: Long) {
    private val identities = entries.toList()
    private val indices by lazy { identities.withIndex().associate { it.value.id to it.index } }
    private var weightedStorage: LpLayoutStorage? = null
    private var layoutWeight: LpLayoutWeight? = null
    val size: Int get() = identities.size
    val activeCount: Int = identities.count { it.active }
    val retainedCount: Int = identities.count { it.active || it.suspendedAt != null }
    val retiredCount: Int get() = size - retainedCount

    init {
        require(lastId >= -1L)
        require(identities.all { it.id in 0..lastId && (it.depth == null || it.depth >= 0) })
        require(
            identities.all {
                it.suspendedAt == null ||
                    (!it.active && it.suspendedAt > 0 && it.suspendedAt >= (it.depth ?: 0))
            },
        )
        require(identities.zipWithNext().all { (a, b) -> a.id < b.id })
    }

    fun row(index: Int): LpRowIdentity = identities[index]
    fun index(id: Long): Int = indices[id] ?: -1
    fun entries(): List<LpRowIdentity> = identities.toList()
    fun sameIdentities(other: LpScopedRows): Boolean = identities.map { it.id } == other.identities.map { it.id }
    fun sameAuthority(other: LpScopedRows): Boolean = lastId == other.lastId && identities == other.identities

    fun storageWeight(storage: LpLayoutStorage): LpLayoutWeight {
        if (storage === weightedStorage) return requireNotNull(layoutWeight)
        return storage.weights(this).also {
            weightedStorage = storage
            layoutWeight = it
        }
    }

    fun append(id: Long, depth: Int?): LpScopedRows = append(listOf(id), depth)

    fun append(ids: List<Long>, depth: Int?, permanent: Set<Long> = emptySet()): LpScopedRows = if (ids.isEmpty()) {
        this
    } else {
        LpScopedRows(identities + ids.map { id -> LpRowIdentity(id, if (id in permanent) null else depth) }, ids.last())
    }

    fun deactivate(indices: Set<Int>): LpScopedRows = LpScopedRows(
        identities.mapIndexed { index, row ->
            if (index in indices) row.copy(active = false, suspendedAt = null) else row
        },
        lastId,
    )

    fun suspend(indices: Set<Int>, depth: Int): LpScopedRows = LpScopedRows(
        identities.mapIndexed { index, row ->
            if (index in indices && row.active) {
                // A row created in this scope cannot reappear after the scope ends.
                row.copy(active = false, suspendedAt = depth.takeUnless { row.depth == it })
            } else {
                row
            }
        },
        lastId,
    )

    fun popped(targetDepth: Int): LpScopedRows {
        val next = identities.map { row ->
            when {
                row.depth != null && row.depth > targetDepth -> row.copy(active = false, suspendedAt = null)
                row.suspendedAt != null && row.suspendedAt > targetDepth -> row.copy(active = true, suspendedAt = null)
                else -> row
            }
        }
        return if (next == identities) this else LpScopedRows(next, lastId)
    }

    fun compact(): LpScopedRows = LpScopedRows(identities.filter { it.active || it.suspendedAt != null }, lastId)

    companion object {
        fun initial(size: Int): LpScopedRows = LpScopedRows(List(size) { LpRowIdentity(it.toLong(), null) }, size - 1L)
    }
}

internal class LpScopedRow(
    val id: Long,
    coefficients: List<Pair<Int, ExactLpNumber>>,
    val rhs: ExactLpNumber,
    val logical: ExactLpColumn,
    val metadata: ExactLpRow = ExactLpRow(),
    val cost: ExactLpNumber = ExactLpNumber.of(0L),
) {
    private val terms = coefficients.toList()
    val coefficientCount: Int get() = terms.size
    fun coefficients(): List<Pair<Int, ExactLpNumber>> = terms.toList()

    fun validFor(model: ExactLpModel): Boolean = validFor(model.n)

    fun validFor(structuralColumns: Int): Boolean = id >= 0L && logical.origin.value.isZero &&
        terms.all { it.first in 0 until structuralColumns } &&
        terms.zipWithNext().all { (a, b) -> a.first < b.first } &&
        (!metadata.strict || logical.bounds.lower?.number?.value?.isZero == true)
}

internal data class LpStructuralColumn(val column: ExactLpColumn, val cost: ExactLpNumber = ExactLpNumber.of(0L))

internal class LpLayoutRemap(
    private val n: Int,
    private val rows: LpScopedRows,
    retainedColumns: List<Int> = List(n) { it },
) {
    private val rowMap = IntArray(rows.size) { -1 }
    private val columnMap = IntArray(n + rows.size) { -1 }
    val retainedColumns: List<Int> = retainedColumns.toList()
    val retainedRows: List<Int> = (0 until rows.size).filter { rows.row(it).active || rows.row(it).suspendedAt != null }
    val unchanged: Boolean get() = retainedColumns.size == n && retainedRows.size == rows.size

    init {
        require(this.retainedColumns.all { it in 0 until n })
        require(this.retainedColumns.zipWithNext().all { (a, b) -> a < b })
        this.retainedColumns.forEachIndexed { next, previous -> columnMap[previous] = next }
        retainedRows.forEachIndexed { next, previous ->
            rowMap[previous] = next
            columnMap[n + previous] = this.retainedColumns.size + next
        }
    }

    fun matches(model: ExactLpModel, identities: LpScopedRows): Boolean =
        n == model.n && rows.sameAuthority(identities)

    fun row(previous: Int): Int = rowMap[previous]
    fun column(previous: Int): Int = columnMap[previous]
}

internal fun ExactLpModel.appendScopedRows(
    columns: List<LpStructuralColumn>,
    rows: List<LpScopedRow>,
): ExactLpModel {
    val newEntries = Array(n + columns.size) { ArrayList<ExactLpEntry>() }
    for ((index, row) in rows.withIndex()) {
        for ((column, number) in row.coefficients()) newEntries[column].add(ExactLpEntry(m + index, number))
    }
    return ExactLpModel(
        List(newEntries.size) { column ->
            if (column < n) entries(column) + newEntries[column] else newEntries[column]
        },
        List(m) { rhs(it) } + rows.map { it.rhs },
        List(n) { column(it) } + columns.map { it.column } + List(m) { column(n + it) } + rows.map { it.logical },
        List(m) { row(it) } + rows.map { it.metadata },
        objective.withCosts(
            List(n) { objective.cost(it) } + columns.map { it.cost } +
                List(m) { objective.cost(n + it) } + rows.map { it.cost },
        ),
    )
}

internal fun ExactLpModel.appendScopedRow(row: LpScopedRow): ExactLpModel = appendScopedRows(emptyList(), listOf(row))

internal fun ExactLpModel.compactLayout(remap: LpLayoutRemap): ExactLpModel = ExactLpModel(
    remap.retainedColumns.map { column ->
        entries(column).mapNotNull { entry ->
            val next = remap.row(entry.row)
            if (next < 0) null else ExactLpEntry(next, entry.number)
        }
    },
    remap.retainedRows.map { rhs(it) },
    remap.retainedColumns.map { column(it) } + remap.retainedRows.map { column(n + it) },
    remap.retainedRows.map { row(it) },
    objective.withCosts(
        remap.retainedColumns.map { objective.cost(it) } + remap.retainedRows.map { objective.cost(n + it) },
    ),
)

private fun ExactLpObjective.withCosts(costs: List<ExactLpNumber>): ExactLpObjective = ExactLpObjective(
    costs,
    constant,
    scale,
    externalConstant,
    sense,
)
