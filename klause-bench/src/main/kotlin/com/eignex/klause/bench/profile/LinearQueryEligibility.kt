package com.eignex.klause.bench.profile

import kotlinx.serialization.Serializable

internal data class QuerySourceColumn(
    val free: Boolean,
    val zeroCost: Boolean,
    val zeroOrigin: Boolean,
    val protectedFinite: Boolean,
    val sideCount: Int,
)

internal enum class QueryRowBlocker {
    DECLARED_BOUND, ACTIVE_BOUND, ASSERTION, INTEGER_LOGICAL, NONZERO_COST, NONZERO_ORIGIN,
    CONDITIONAL, STRICT, PREMISES, NONZERO_RHS, SCOPED_OR_INACTIVE,
}

internal data class QueryDefinitionRow(
    val knownIdentity: Boolean,
    val blockers: Set<QueryRowBlocker>,
    val columns: IntArray,
    val sideCount: Int,
    val premiseUnits: Long,
)

internal data class QueryEligibilityInput(
    val columns: List<QuerySourceColumn>,
    val rows: List<QueryDefinitionRow>,
    val mappingConsistent: Boolean,
    val maximumInputBits: Int,
    val premiseScalarsKnown: Boolean,
)

@Serializable
internal data class QueryEligibilityResult(
    val status: String,
    val mappingConsistent: Boolean,
    val originalStructural: Int,
    val originalRows: Int,
    val knownRows: Int,
    val retainedRowBlockers: Map<String, Int>,
    val removedRows: Int,
    val retainedRows: Int,
    val retainedStructural: Int,
    val restoredZeroColumns: Int,
    val protectedFiniteColumns: Int,
    val retainedStructuralEntries: Long,
    val reconstructionEntries: Long,
    val continuationCells: Long,
    val layoutStorageUnits: Long,
    val admissionWorkUpperBound: Long,
    val admissionAllocationUpperBound: Long,
    val reconstructionCaptureWorkUpperBound: Long?,
    val reconstructionCaptureAllocationUpperBound: Long?,
    val maximumInputBits: Int,
    val staticGuards: Map<String, Boolean>,
    val unprovedRequirements: List<String> = listOf(
        "numerical working-model construction and basis", "remaining original-owner allowance",
        "subsequent exact arithmetic growth and pivots", "full original-prefix and source witness acceptance",
    ),
)

internal object LinearQueryEligibility {
    fun inspect(input: QueryEligibilityInput, scan: (Long) -> Unit = {}): QueryEligibilityResult {
        require(input.columns.size <= 16384 && input.rows.size <= 16384)
        val used = BooleanArray(input.columns.size)
        val retained = input.rows.filterNot {
            scan(1L)
            it.knownIdentity && it.blockers.isEmpty()
        }
        var entries = 0L
        var premises = 0L
        var sides = 0L
        for (row in retained) {
            for (column in row.columns) {
                scan(1L)
                require(column in used.indices)
                used[column] = true
                entries++
                require(entries <= 131072L)
            }
            require(row.premiseUnits in 0L..393216L && row.sideCount in 0..2)
            premises = Math.addExact(premises, row.premiseUnits)
            sides += row.sideCount
        }
        var structural = 0
        for ((index, column) in input.columns.withIndex()) {
            scan(1L)
            require(column.sideCount in 0..2)
            if (used[index] || !column.free || !column.zeroCost || !column.zeroOrigin || column.protectedFinite) {
                structural++
                sides += column.sideCount
            }
        }
        val rows = retained.size
        val total = structural.toLong() + rows
        val cells = rows.toLong() * (total + rows + 1L)
        val storage = Math.addExact(structural * 16L + rows * 16L + entries * 3L, premises)
        val bits = input.maximumInputBits.toLong()
        require(bits >= 0L)
        val admissionWork = 1L + storage + total + 2L * sides
        val admissionAllocation = storage * 8L + sides * (64L + (bits + 7L) / 4L)
        val reconstructionEntries = entries + rows
        val fractions = reconstructionEntries + sides + total + rows + structural + 3L
        val captureWork = 3L + storage + total + reconstructionEntries + fractions * 4L + rows
        val captureAllocation = total * 96L + storage * 8L + reconstructionEntries * 32L +
            fractions * 2L * (32L + (bits + 7L) / 8L)
        val captureKnown = input.premiseScalarsKnown && premises == 0L
        val guards = linkedMapOf(
            "sourceAndDefinitionMapping" to input.mappingConsistent,
            "continuationRows128" to (rows <= 128),
            "continuationColumns4096" to (total <= 4096L),
            "continuationCells65536" to (cells <= 65536L),
            "inputBits4096" to (bits <= 4096L),
            "admissionWork1000000" to (admissionWork <= 1_000_000L),
            "admissionAllocation4194304" to (admissionAllocation <= 4L * 1024L * 1024L),
            "reconstructionCoordinates4096" to (total <= 4096L),
            "reconstructionEntries65536IncludingLogicals" to (reconstructionEntries <= 65536L),
            "premiseScalarBoundsKnown" to captureKnown,
            "reconstructionCaptureWork250000" to (captureKnown && captureWork <= 250000L),
            "reconstructionCaptureAllocation16777216" to (captureKnown && captureAllocation <= 16L * 1024L * 1024L),
        )
        scan(input.rows.size.toLong() * (QueryRowBlocker.entries.size + 1L) + input.columns.size)
        val blockers = QueryRowBlocker.entries.associate { reason -> reason.name to
            input.rows.count { reason in it.blockers } }
        return QueryEligibilityResult(
            if (guards.values.all { it }) "static-input-lead-only" else "no-positive-static-eligibility-evidence",
            input.mappingConsistent, input.columns.size, input.rows.size, input.rows.count { it.knownIdentity }, blockers,
            input.rows.size - rows, rows, structural, input.columns.size - structural,
            input.columns.count { it.protectedFinite }, entries, reconstructionEntries, cells, storage,
            admissionWork, admissionAllocation, captureWork.takeIf { captureKnown },
            captureAllocation.takeIf { captureKnown }, input.maximumInputBits, guards,
        )
    }
}
