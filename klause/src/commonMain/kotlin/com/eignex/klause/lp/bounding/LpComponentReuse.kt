package com.eignex.klause.lp.bounding

import com.eignex.klause.lp.engine.LpComponentAdmission
import com.eignex.klause.lp.engine.LpComponentEditDecline
import com.eignex.klause.lp.engine.LpComponentShape

private const val MIN_COMPONENT_CELLS = 4096L
private const val MAX_COMPONENT_BYTES = 16L * 1024 * 1024
private const val MAX_COMPONENTS = 1024
private const val MAX_COMPONENT_PREPARATION_WORK = 16_000_000L

internal fun componentReuseFundingAvailable(
    allowance: Long?,
    horizon: Long? = null,
    deadlineBounded: Boolean = false,
): Boolean {
    val available = when {
        allowance == null -> horizon
        horizon == null -> allowance
        else -> minOf(allowance, horizon)
    }
    return if (available == null) !deadlineBounded else available / 4L >= MAX_COMPONENT_PREPARATION_WORK
}

internal fun componentReusePreparationWork(remaining: Long?): Long =
    minOf(MAX_COMPONENT_PREPARATION_WORK, remaining?.coerceAtLeast(1L) ?: MAX_COMPONENT_PREPARATION_WORK)

internal val LpEngine.numericalComponentAdmission: LpComponentAdmission
    get() = object : LpComponentAdmission {
        override val basisEntryLimit = 131_072L
        override val basisWorkLimit = 8_000_000L

        override fun inspect(columns: Int, rows: Int, entries: Int): Boolean {
            val outcome = componentReuseInspection(columns, rows, entries) ?: return true
            sink.lp.observeComponentAdmission(outcome, 0, 0L)
            return false
        }

        override fun declinedEdit(reason: LpComponentEditDecline) {
            sink.lp.observeComponentAdmission("EDIT_${reason.name}", 0, 0L)
        }

        override fun accepts(shape: LpComponentShape): Boolean {
            val outcome = componentReuseAdmission(shape)
            sink.lp.observeComponentAdmission(outcome, shape.blocks, shape.modeledBytes,
                if (outcome == "ADMITTED") basisEntryLimit else 0L)
            return outcome == "ADMITTED"
        }
    }

internal fun componentReuseInspection(columns: Int, rows: Int, entries: Int): String? = when {
    rows.toLong() * (columns.toLong() + rows + 1L) < MIN_COMPONENT_CELLS -> "SMALL_MODEL"
    128L * (entries.toLong() + columns + rows + 2L) + 4L * rows > MAX_COMPONENT_BYTES -> "STORAGE"
    else -> null
}

internal fun componentReuseAdmission(shape: LpComponentShape): String = when {
    shape.blocks !in 2..MAX_COMPONENTS -> "COMPONENT_COUNT"
    shape.monolithicCells < MIN_COMPONENT_CELLS -> "SMALL_MODEL"
    shape.modeledBytes > MAX_COMPONENT_BYTES -> "STORAGE"
    shape.componentCells > shape.monolithicCells - shape.monolithicCells / 4L -> "DOMINANT_COMPONENT"
    else -> "ADMITTED"
}
