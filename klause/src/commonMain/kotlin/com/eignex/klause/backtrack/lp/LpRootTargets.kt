package com.eignex.klause.backtrack.lp

import com.eignex.klause.lp.bounding.LpEngine
import com.eignex.klause.lp.bounding.RootComponentValues
import com.eignex.klause.lp.bounding.RootPrimalTargets
import com.eignex.klause.solver.search.VarRef
import kotlin.math.round

internal fun LpEngine.orderRootValues(variable: VarRef, values: Sequence<Long>): Sequence<Long> {
    val targets = rootPrimalTargets
    val components = rootComponentValues
    if (targets == null && components == null) return values
    return sequence {
        val currentTargets = targets?.takeIf { it === rootPrimalTargets }
        val currentComponents = components?.takeIf { it === rootComponentValues }
        if ((currentTargets == null && currentComponents == null) || lpWorkAllowanceSpent(1L)) {
            yieldAll(values)
            return@sequence
        }
        noteNodeOverhead(1L)
        val ordinary = values.iterator()
        if (!ordinary.hasNext()) return@sequence
        val saved = currentComponents?.preferredValue(variable)
        val preferred = saved ?: currentTargets?.preferredValue(variable)
        if (preferred == null) {
            yieldAll(ordinary.asSequence())
            return@sequence
        }
        observeRootValuePreference(currentTargets, currentComponents, saved != null)
        yield(preferred)
        for (value in ordinary) if (value != preferred) yield(value)
    }
}

private fun RootComponentValues.preferredValue(variable: VarRef): Long? {
    if (variable !is VarRef.IntVar || known.getOrNull(variable.varId) != true) return null
    return values[variable.varId]
}

private fun RootPrimalTargets.preferredValue(variable: VarRef): Long? {
    val column = when (variable) {
        is VarRef.IntVar -> intColumns.getOrNull(variable.varId)
        is VarRef.Bool -> booleanColumns.getOrNull(variable.varId)
    } ?: return null
    val coordinate = coordinates.getOrNull(column)?.takeIf {
        it.isFinite() && (knownColumns == null || knownColumns.getOrNull(column) == true)
    } ?: return null
    return if (variable is VarRef.Bool) {
        if (coordinate >= 0.5) 1L else 0L
    } else round(coordinate).toLong()
}

private fun LpEngine.observeRootValuePreference(
    targets: RootPrimalTargets?,
    components: RootComponentValues?,
    saved: Boolean,
) {
    if (saved && components != null && !components.offered) {
        components.offered = true
        sink.lp.observePrimalPhase("PRIMAL_COMPONENT_VALUES", "VALUE_ORDER_OFFERED", 0L)
    }
    if (saved && components != null && components.restarted && !components.restartOffered) {
        components.restartOffered = true
        sink.lp.observePrimalPhase("PRIMAL_COMPONENT_VALUES", "RESTART_VALUE_ORDER_OFFERED", 0L)
    }
    if (!saved && targets != null && !targets.valueOrderOffered) {
        targets.valueOrderOffered = true
        sink.lp.observePrimalPhase("PRIMAL_VALUE_GUIDANCE", "VALUE_ORDER_OFFERED", 0L)
        if (targets.auxiliary) {
            sink.lp.observePrimalPhase("PRIMAL_VALUE_GUIDANCE", "AUXILIARY_ROOT_VALUE_ORDER_OFFERED", 0L)
        }
    }
}
