package com.eignex.klause.theory.qflra

import com.eignex.klause.lp.engine.ExactLpModel
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.CheckedLongOverflowException
import com.eignex.klause.util.ScanMeter
import com.eignex.klause.util.addExact

internal object IntegerCutOriginProbe {
    enum class Outcome {
        NO_STRUCTURAL_COLUMNS,
        NONINTEGRAL_COLUMN,
        OPEN_LOWER,
        NON_LONG_LOWER,
        NON_LONG_ORIGIN,
        ORIGIN_SHIFT_OVERFLOW,
        FINITE_LONG_ORIGINS,
    }

    fun inspect(model: ExactLpModel, cancellation: Cancellation): Outcome? {
        val scan = ScanMeter(cancellation)
        return try {
            if (!scan.check()) return null
            val outcome = classify(model, scan)
            if (scan.check()) outcome else null
        } finally {
            scan.flush()
        }
    }

    private fun classify(model: ExactLpModel, scan: ScanMeter): Outcome? {
        if (model.n == 0) return Outcome.NO_STRUCTURAL_COLUMNS
        for (index in 0 until model.n) {
            if (!scan.visit(1L)) return null
            val column = model.column(index)
            if (!column.integral) return Outcome.NONINTEGRAL_COLUMN
            val lowerSide = column.bounds.lower ?: return Outcome.OPEN_LOWER
            val lower = lowerSide.number.exactLong() ?: return Outcome.NON_LONG_LOWER
            val origin = column.origin.exactLong() ?: return Outcome.NON_LONG_ORIGIN
            try {
                addExact(lower, origin)
            } catch (_: CheckedLongOverflowException) {
                return Outcome.ORIGIN_SHIFT_OVERFLOW
            }
        }
        return Outcome.FINITE_LONG_ORIGINS
    }
}
