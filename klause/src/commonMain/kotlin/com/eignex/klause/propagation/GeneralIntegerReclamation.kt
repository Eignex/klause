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
    val scratch = rows.trimmingScratchBytes
    if (rows.trimmingWork > 0L && budget.supports(rows.trimmingWork, scratch)) {
        budget.visit(rows.trimmingWork, scratch)
        budget.visit(0L)
        rows.trim()
        budget.advanced()
    }
    val overflow = intEvents.overflow ?: return
    if (learned.retiredCount > 0) overflow.sweep(learned.retiredCount, budget) { retiredLearnedFactor(it) }
    if (overflow.size == 0) {
        budget.visit(4L)
        budget.visit(0L)
        intEvents.overflow = null
        budget.advanced()
    }
}
