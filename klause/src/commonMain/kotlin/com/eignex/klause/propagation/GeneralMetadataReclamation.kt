package com.eignex.klause.propagation

internal fun PropagationState.sweepRetiredGeneralMetadata(budget: ReclamationPreparation) {
    if (incremental || nativeEngine != null) return
    val rows = learned.initializedStore ?: return
    if (rows.hasEmptyReservation) {
        budget.visit(12L, 16L)
        budget.visit(0L)
        rows.releaseEmptyReservation()
        budget.advanced()
    }
    while (rows.hasRetiredRows) {
        budget.visit(32L, 16L)
        budget.visit(0L)
        learned.releaseRetiredMetadata()
        budget.advanced()
    }
    rows.sweepDirectory(budget)
}
