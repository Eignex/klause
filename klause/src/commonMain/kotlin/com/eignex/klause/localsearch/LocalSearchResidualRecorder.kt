package com.eignex.klause.localsearch

import com.eignex.klause.factor.arithmetic.ComparisonClause
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearInvariant
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.arithmetic.ReifiedLinearInvariant
import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.factor.scheduling.internals.CumulativeLsState
import com.eignex.klause.factor.table.Element
import com.eignex.klause.ir.IntegerConstants
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.solver.objective.FunctionalObjective
import com.eignex.klause.solver.result.LocalSearchResidual
import com.eignex.klause.solver.result.LocalSearchResidualFactor

internal class LocalSearchResidualRecorder {
    var best: LocalSearchResidual? = null
        private set

    fun observe(state: LocalSearchState) {
        if (state.cost >= (best?.cost ?: Long.MAX_VALUE)) return
        val byKind = LinkedHashMap<String, Long>()
        val representatives = LinkedHashMap<String, Int>()
        for (fid in state.problem.factors.indices) {
            val degree = state.factorDegree[fid]
            if (degree == 0) continue
            val factor = state.problem.factors[fid]
            val kind = if (factor is ReifiedLinear) {
                val shape = if (factor.vars.size != 1) {
                    "multi"
                } else {
                    val domain = state.rootDomains[factor.vars[0]]
                    if (domain.min >= 0L && domain.max <= 1L) "binary" else "unary"
                }
                "ReifiedLinear.${factor.op}.$shape"
            } else {
                factor::class.simpleName ?: "Unknown"
            }
            byKind[kind] = (byKind[kind] ?: 0L) + degree
            val previous = representatives[kind]
            if (previous == null || degree > state.factorDegree[previous]) representatives[kind] = fid
        }
        val factors = representatives.entries.sortedByDescending { state.factorDegree[it.value] }
            .take(MAX_FACTORS).map { (kind, fid) ->
                val factor = state.problem.factors[fid]
                val bools = factor.boolVars.distinct()
                val ints = when (factor) {
                    is Cumulative -> cumulativeCoordinates(state, fid, factor)
                    is Element -> elementCoordinates(state, factor)
                    else -> factor.intVars.distinct()
                }
                LocalSearchResidualFactor(
                    fid, kind, state.factorDegree[fid].toLong(),
                    bools.take(MAX_COORDINATES).associateWith(state.assignment::boolValue),
                    ints.take(MAX_COORDINATES).associateWith(state.assignment::intValue),
                    (bools.size - MAX_COORDINATES).coerceAtLeast(0),
                    (ints.size - MAX_COORDINATES).coerceAtLeast(0),
                    factorDetails(state, fid),
                )
            }
        val definitions = definitionDetails(state, factors)
        best = LocalSearchResidual(state.cost, byKind, factors.map { factor ->
            val details = LinkedHashMap(factor.details)
            for (v in factor.ints.keys) details.putAll(definitions.getValue("i$v"))
            for (v in factor.bools.keys) details.putAll(definitions.getValue("b$v"))
            factor.copy(details = details)
        })
    }

    private fun factorDetails(state: LocalSearchState, fid: Int): Map<String, String> {
        val details = LinkedHashMap<String, String>()
        when (val factor = state.problem.factors[fid]) {
            is ComparisonClause -> {
                for (i in 0 until minOf(factor.vars.size, MAX_COORDINATES)) {
                    details["literal.$i"] = "i${factor.vars[i]}:${factor.ops[i]}:${factor.consts[i]}"
                }
                details["omittedLiterals"] = (factor.vars.size - MAX_COORDINATES).coerceAtLeast(0).toString()
            }
            is Linear -> rowDetails(details, factor.vars, factor.op, factor.integerConstants,
                if (state.factors[fid] is LinearInvariant) state.longPayload[fid] else null)
            is ReifiedLinear -> rowDetails(details, factor.vars, factor.op, factor.integerConstants,
                if (state.factors[fid] is ReifiedLinearInvariant) state.longPayload[fid] else null)
        }
        return details
    }

    private fun rowDetails(
        details: MutableMap<String, String>,
        vars: IntArray,
        op: LinearOp,
        constants: IntegerConstants?,
        activity: Long?,
    ) {
        details["op"] = op.toString()
        if (constants == null) {
            details["row"] = "unsupported-constants"
            details["activity"] = "unsupported-grader"
            return
        }
        details["bound"] = constants.bound.toString()
        for (i in 0 until minOf(vars.size, MAX_COORDINATES)) {
            details["term.$i"] = "${constants.coeff(i)}:i${vars[i]}"
        }
        details["omittedTerms"] = (vars.size - MAX_COORDINATES).coerceAtLeast(0).toString()
        details["activity"] = activity?.toString() ?: "unsupported-grader"
    }

    private fun definitionDetails(
        state: LocalSearchState,
        factors: List<LocalSearchResidualFactor>,
    ): Map<String, Map<String, String>> {
        val details = LinkedHashMap<String, Map<String, String>>()
        for (factor in factors) {
            for (v in factor.ints.keys) details["i$v"] = mapOf("i$v.definition.kind" to "none")
            for (v in factor.bools.keys) details["b$v"] = mapOf("b$v.definition.kind" to "none")
        }
        if (details.isEmpty()) return details
        val network = state.invariants ?: return details
        // A flat pass captures only requested outputs; it never follows their dependency cones.
        for (i in 0 until network.size) {
            val node = network.node(i)
            val coordinate = if (node.outIsBool) "b${node.out}" else "i${node.out}"
            if (coordinate !in details) continue
            val inputs = ArrayList<String>(MAX_COORDINATES)
            for (v in node.intInputs.take(MAX_COORDINATES)) inputs.add("i$v")
            for (v in node.boolInputs.take(MAX_COORDINATES - inputs.size)) inputs.add("b$v")
            details[coordinate] = mapOf(
                "$coordinate.definition.kind" to definitionKind(node),
                "$coordinate.definition.inputs" to inputs.joinToString(",").ifEmpty { "-" },
                "$coordinate.definition.omittedInputs" to
                    (node.intInputs.size + node.boolInputs.size - inputs.size).toString(),
            )
        }
        return details
    }

    private fun definitionKind(node: DefinitionalSweep.SweepNode): String = when (node) {
        is DefinitionalSweep.SweepNode.IntDef -> when (node.node) {
            is FunctionalObjective.Abs -> "IntDef.Abs"
            is FunctionalObjective.Extreme -> "IntDef.Extreme"
            is FunctionalObjective.Times -> "IntDef.Times"
            is FunctionalObjective.Power -> "IntDef.Power"
            is FunctionalObjective.Plus -> "IntDef.Plus"
            is FunctionalObjective.Lin -> "IntDef.Lin"
        }
        is DefinitionalSweep.SweepNode.CmpReif -> "CmpReif"
        is DefinitionalSweep.SweepNode.SetInReif -> "SetInReif"
        is DefinitionalSweep.SweepNode.Bool2Int -> "Bool2Int"
        is DefinitionalSweep.SweepNode.BoolFold -> "BoolFold"
        is DefinitionalSweep.SweepNode.ElementDef -> "ElementDef"
        is LiteralIntDefinition -> "LiteralIntDefinition"
        is EqualityPredicateDefinition -> "EqualityPredicateDefinition"
        is ComparisonPredicateDefinition -> "ComparisonPredicateDefinition"
        is ElementResultDefinition -> "ElementResultDefinition"
        else -> "unsupported-definition"
    }

    private fun elementCoordinates(state: LocalSearchState, factor: Element): List<Int> {
        val coordinates = arrayListOf(factor.idx, factor.result)
        if (factor.arrIsVars) {
            val index = state.assignment.intValue(factor.idx)
            val offset = factor.indexOffset.toLong()
            if (index >= offset && index < offset + factor.arr.size) {
                coordinates.add(factor.arr[(index - offset).toInt()].toInt())
            }
        }
        coordinates.addAll(factor.intVars.toList())
        return coordinates.distinct()
    }

    private fun cumulativeCoordinates(state: LocalSearchState, fid: Int, factor: Cumulative): List<Int> {
        val ls = state.refPayload[fid] as? CumulativeLsState ?: return factor.intVars.distinct()
        val peak = ls.usage.indices.maxByOrNull { ls.usage[it] } ?: return factor.intVars.distinct()
        val time = ls.tLow + peak
        val coordinates = ArrayList<Int>()
        if (factor.capacityVar >= 0) coordinates.add(factor.capacityVar)
        for (i in factor.starts.indices) {
            if (!OptionalPresence.isPresentInAssignment(factor.presents, i, state)) continue
            val start = state.assignment.intValue(factor.starts[i])
            val duration = if (factor.durationVars.isEmpty()) {
                factor.durations[i]
            } else {
                state.assignment.intValue(factor.durationVars[i])
            }
            val demand = if (factor.resourceVars.isEmpty()) {
                factor.resources[i]
            } else {
                state.assignment.intValue(factor.resourceVars[i])
            }
            if (duration <= 0L || demand <= 0L || start > time || time >= start + duration) continue
            coordinates.add(factor.starts[i])
            if (factor.durationVars.isNotEmpty()) coordinates.add(factor.durationVars[i])
            if (factor.resourceVars.isNotEmpty()) coordinates.add(factor.resourceVars[i])
        }
        coordinates.addAll(factor.intVars.toList())
        return coordinates.distinct()
    }

    private companion object {
        const val MAX_FACTORS = 8
        const val MAX_COORDINATES = 16
    }
}
