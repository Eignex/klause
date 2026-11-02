package com.eignex.klause.propagation

internal fun PropagationState.sweepRetiredGeneralIntegerRows(budget: ReclamationPreparation) {
    if (incremental || nativeEngine != null) return
    val rows = learned.integerRows ?: return
    if (rows.size == 0 && intEvents.overflow != null) {
        budget.visit(16L)
        budget.visit(0L)
        intEvents.overflow = null
        budget.advanced()
    }
    if (rows.capacity <= rows.size) return
    val scratch = rows.size.toLong() * Long.SIZE_BYTES
    if (!budget.supports(rows.trimmingWork, scratch)) return
    budget.visit(rows.trimmingWork, scratch)
    budget.visit(0L)
    rows.trim()
    budget.advanced()
}
