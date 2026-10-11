package com.eignex.klause.compile

import com.eignex.klause.lowering.FloatRepresentation
import com.eignex.klause.model.And
import com.eignex.klause.model.BoolExpr
import com.eignex.klause.model.FloatLinearConstraint
import com.eignex.klause.model.FloatSpec
import com.eignex.klause.model.IntCmpOp
import com.eignex.klause.model.NamedConstraint
import com.eignex.klause.model.PresenceSpec
import com.eignex.klause.model.SchemaEntry
import com.eignex.klause.util.IntDisjointSet
import com.eignex.skema.SchemaDef

internal fun schemaFloatRepresentations(def: SchemaDef<SchemaEntry>): Map<String, FloatRepresentation> {
    val names = def.entries.filterValues { it is FloatSpec }.keys.toList()
    val ids = names.withIndex().associate { it.value to it.index }
    val components = IntDisjointSet(names.size)
    val rows = ArrayList<FloatLinearConstraint>()
    fun collect(expr: BoolExpr) {
        when (expr) {
            is FloatLinearConstraint -> rows.add(expr)
            is And -> expr.children.forEach(::collect)
            else -> Unit
        }
    }
    def.entries.values.filterIsInstance<NamedConstraint>().forEach { collect(it.expr) }
    for (row in rows) {
        val vars = row.varNames.map { ids.getValue(it) }
        for (id in vars.drop(1)) components.union(vars.first(), id)
    }
    val reasons = HashMap<Int, String>()
    for (row in rows) {
        if (row.op == IntCmpOp.LT || row.op == IntCmpOp.GT || row.op == IntCmpOp.NE) {
            row.varNames.forEach { reasons[components.find(ids.getValue(it))] = "strict or disequal schema float row" }
        }
    }
    for (presence in def.entries.values.filterIsInstance<PresenceSpec>()) {
        ids[presence.valueName]?.let { reasons[components.find(it)] = "optional schema float value" }
    }
    return names.associateWith { name ->
        val root = components.find(ids.getValue(name))
        FloatRepresentation(root, reasons[root])
    }
}
