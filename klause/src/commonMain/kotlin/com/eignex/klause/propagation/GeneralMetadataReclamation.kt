package com.eignex.klause.propagation

internal fun PropagationState.sweepRetiredGeneralMetadata(budget: ReclamationPreparation) {
    if (incremental || nativeEngine != null) return
    val rows = learned.initializedStore ?: return
    if (rows.hasEmptyReservation) {
        budget.visit(12L, 16L)
        budget.visit(0L)
        rows.releaseEmptyReservation()
    }
    while (learned.retiredMetadataHead >= 0) {
        budget.visit(32L, 16L)
        budget.visit(0L)
        val index = learned.retiredMetadataHead
        val next = rows[index, GeneralLearnedRows.NEXT_RETIRED]
        learned.releaseRetiredMetadata(index)
        learned.retiredMetadataHead = next
        if (next < 0) learned.retiredMetadataTail = -1
    }
}
