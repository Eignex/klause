package com.eignex.klause.backtrack.lp

import com.eignex.klause.lp.bounding.LpEngine
import com.eignex.klause.solver.search.VarRef
import kotlin.math.round

internal fun LpEngine.orderRootValues(variable: VarRef, values: Sequence<Long>): Sequence<Long> {
    val targets = rootPrimalTargets ?: return values
    return sequence {
        if (rootPrimalTargets !== targets || lpWorkAllowanceSpent(1L)) {
            yieldAll(values)
            return@sequence
        }
        noteNodeOverhead(1L)
        val ordinary = values.iterator()
        if (!ordinary.hasNext()) return@sequence
        val column = when (variable) {
            is VarRef.IntVar -> targets.intColumns.getOrNull(variable.varId)
            is VarRef.Bool -> targets.booleanColumns.getOrNull(variable.varId)
        }
        val coordinate = column?.let { col ->
            targets.coordinates.getOrNull(col)?.takeIf {
                it.isFinite() && (targets.knownColumns == null || targets.knownColumns.getOrNull(col) == true)
            }
        }
        if (coordinate == null) {
            yieldAll(ordinary.asSequence())
            return@sequence
        }
        if (!targets.valueOrderOffered) {
            targets.valueOrderOffered = true
            sink.lp.observePrimalPhase("PRIMAL_VALUE_GUIDANCE", "VALUE_ORDER_OFFERED", 0L)
            if (targets.auxiliary) {
                sink.lp.observePrimalPhase("PRIMAL_VALUE_GUIDANCE", "AUXILIARY_ROOT_VALUE_ORDER_OFFERED", 0L)
            }
        }
        val preferred = if (variable is VarRef.Bool) {
            if (coordinate >= 0.5) 1L else 0L
        } else round(coordinate).toLong()
        yield(preferred)
        for (value in ordinary) if (value != preferred) yield(value)
    }
}
