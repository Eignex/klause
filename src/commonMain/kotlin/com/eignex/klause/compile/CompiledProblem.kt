package com.eignex.klause.compile

import com.eignex.klause.solver.Problem

/**
 * Result of compiling a [com.eignex.klause.schema.VariableSchema] to a solver-side [Problem],
 * carrying the index needed to decode an assignment back to schema values.
 *
 * `varIdByName` maps a [com.eignex.klause.ast.BoolSpec] name to its solver variable id.
 * `nominalIndicators` maps a nominal variable name to the per-label indicator variable ids.
 */
class CompiledProblem(
    val problem: Problem,
    val varIdByName: Map<String, Int>,
    val nominalIndicators: Map<String, Map<String, Int>>,
) {
    fun decodeBool(name: String, assignment: BooleanArray): Boolean {
        val id = varIdByName[name] ?: error("No Boolean variable named '$name'")
        return assignment[id]
    }

    fun decodeNominal(name: String, assignment: BooleanArray): String {
        val map = nominalIndicators[name] ?: error("No nominal variable named '$name'")
        return map.entries.firstOrNull { assignment[it.value] }?.key
            ?: error("Nominal '$name' has no label set in assignment")
    }
}
