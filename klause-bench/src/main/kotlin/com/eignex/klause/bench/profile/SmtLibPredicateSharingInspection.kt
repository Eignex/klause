package com.eignex.klause.bench.profile

import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object SmtLibPredicateSharingInspection {
    fun inspect(
        definitions: Map<Int, List<Factor>>,
        retained: Set<Int>,
        sourceBooleans: Set<Int>,
        beforeExpansion: Set<Int>,
        factors: List<Factor>,
    ): JsonObject {
        val gates = HashMap<String, MutableSet<Int>>()
        var skipped = 0
        for ((output, parts) in definitions) {
            if (output !in retained || output in sourceBooleans) continue
            val clauses = parts.filterIsInstance<Clause>()
            if (clauses.isEmpty() || clauses.size != parts.size ||
                clauses.any { clause -> clause.literals.none { Lit.variable(it) == output } }
            ) {
                skipped++
                continue
            }
            val signature = clauses.map { clause ->
                clause.literals.map { literal ->
                    if (Lit.variable(literal) != output) "input:$literal"
                    else if (Lit.isPositive(literal)) "output+" else "output-"
                }.sorted().joinToString(",")
            }.sorted().joinToString(";")
            gates.getOrPut(signature) { HashSet() }.add(output)
        }
        val arithmetic = HashMap<String, MutableSet<Int>>()
        var arithmeticRows = 0
        for (row in factors.filterIsInstance<ReifiedLinear>()) {
            if (row.auxBoolVar in sourceBooleans) continue
            arithmeticRows++
            val terms = row.vars.indices.map { index ->
                row.vars[index] to row.constants.exactCoeff(index).toString()
            }.sortedBy { it.first }.joinToString(",") { "${it.first}:${it.second}" }
            val signature = "${row.op}:${row.constants.exactBound}:$terms"
            arithmetic.getOrPut(signature) { HashSet() }.add(row.auxBoolVar)
        }
        return buildJsonObject {
            put("schemaVersion", 2)
            put("scope", "retained private output identities; exact structural meanings, no algebraic equivalence")
            put("clauseGates", summarize(gates, beforeExpansion))
            put("retainedBooleanOwnersOutsideClauseGateScope", skipped)
            put("arithmeticRows", arithmeticRows)
            put("arithmeticPredicates", summarize(arithmetic, beforeExpansion))
            put("arithmeticOriginScope", "explicit prior owners exclude implicit primitive row allocations")
            put("sourceVerdictClaim", false)
            put("encodingInstalled", false)
            put("timingClaim", false)
        }
    }

    private fun summarize(groups: Map<String, Set<Int>>, beforeExpansion: Set<Int>): JsonObject {
        val repeated = groups.values.filter { it.size > 1 }
        return buildJsonObject {
            put("outputSignatureMemberships", groups.values.sumOf { it.size })
            put("distinctPrivateOutputs", groups.values.flatten().toSet().size)
            put("distinctMeanings", groups.size)
            put("repeatedMeaningGroups", repeated.size)
            put("groupMembershipExcess", repeated.sumOf { it.size - 1 })
            put("outputsInRepeatedGroupsBeforeExpansion", repeated.sumOf { group ->
                group.count { it in beforeExpansion }
            })
            put("outputsInRepeatedGroupsNotInPriorExplicitOwners", repeated.sumOf { group ->
                group.count { it !in beforeExpansion }
            })
        }
    }
}
