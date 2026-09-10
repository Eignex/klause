package com.eignex.klause.lp.engine

internal data class LpRowIdentity(val id: Long, val depth: Int?, val active: Boolean = true)

internal class LpScopedRows(entries: List<LpRowIdentity>, val lastId: Long) {
    private val identities = entries.toList()
    val size: Int get() = identities.size
    val activeCount: Int get() = identities.count { it.active }

    init {
        require(lastId >= -1L)
        require(identities.all { it.id in 0..lastId && (it.depth == null || it.depth >= 0) })
        require(identities.zipWithNext().all { (a, b) -> a.id < b.id })
    }

    fun row(index: Int): LpRowIdentity = identities[index]
    fun index(id: Long): Int = identities.indexOfFirst { it.id == id }
    fun entries(): List<LpRowIdentity> = identities.toList()
    fun sameIdentities(other: LpScopedRows): Boolean = identities.map { it.id } == other.identities.map { it.id }
    fun sameAuthority(other: LpScopedRows): Boolean = lastId == other.lastId && identities == other.identities

    fun append(id: Long, depth: Int?): LpScopedRows = LpScopedRows(identities + LpRowIdentity(id, depth), id)

    fun deactivate(indices: Set<Int>): LpScopedRows = LpScopedRows(
        identities.mapIndexed { index, row -> if (index in indices) row.copy(active = false) else row },
        lastId,
    )

    fun compact(): LpScopedRows = LpScopedRows(identities.filter { it.active }, lastId)

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
    fun coefficients(): List<Pair<Int, ExactLpNumber>> = terms.toList()

    fun validFor(model: ExactLpModel): Boolean = id >= 0L && logical.origin.value.isZero &&
        terms.all { it.first in 0 until model.n } &&
        terms.zipWithNext().all { (a, b) -> a.first < b.first } &&
        (!metadata.strict || logical.bounds.lower?.number?.value?.isZero == true)
}

internal class LpRowRemap(n: Int, rows: LpScopedRows) {
    private val rowMap = IntArray(rows.size) { -1 }
    private val columnMap = IntArray(n + rows.size) { if (it < n) it else -1 }
    val retained: List<Int> = (0 until rows.size).filter { rows.row(it).active }

    init {
        retained.forEachIndexed { next, previous ->
            rowMap[previous] = next
            columnMap[n + previous] = n + next
        }
    }

    fun row(previous: Int): Int = rowMap[previous]
    fun column(previous: Int): Int = columnMap[previous]
}

internal fun ExactLpModel.appendScopedRow(row: LpScopedRow): ExactLpModel {
    val terms = row.coefficients().toMap()
    return ExactLpModel(
        List(n) { column -> entries(column) + listOfNotNull(terms[column]?.let { ExactLpEntry(m, it) }) },
        List(m) { rhs(it) } + row.rhs,
        List(numVars) { column(it) } + row.logical,
        List(m) { row(it) } + row.metadata,
        objective.withCosts(List(numVars) { objective.cost(it) } + row.cost),
    )
}

internal fun ExactLpModel.compactScopedRows(remap: LpRowRemap): ExactLpModel = ExactLpModel(
    List(n) { column ->
        entries(column).mapNotNull { entry ->
            val next = remap.row(entry.row)
            if (next < 0) null else ExactLpEntry(next, entry.number)
        }
    },
    remap.retained.map { rhs(it) },
    List(n) { column(it) } + remap.retained.map { column(n + it) },
    remap.retained.map { row(it) },
    objective.withCosts(List(n) { objective.cost(it) } + remap.retained.map { objective.cost(n + it) }),
)

private fun ExactLpObjective.withCosts(costs: List<ExactLpNumber>): ExactLpObjective = ExactLpObjective(
    costs,
    constant,
    scale,
    externalConstant,
    sense,
)
