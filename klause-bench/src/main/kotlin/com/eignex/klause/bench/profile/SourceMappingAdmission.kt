package com.eignex.klause.bench.profile

import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BigFraction
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object SourceMappingAdmission {
    fun inspect(authority: Any?, mapping: Any?, working: Problem, costs: LinearObjective?,
        stop: ExecutionControl): JsonObject {
        var work = 0L
        val limit = 1_000_000L
        fun charge(units: Long): Boolean {
            work += minOf(units, Long.MAX_VALUE - work)
            return work <= limit && !stop()
        }
        var complete = charge(1L)
        fun reason(): String? {
            if (!complete) return null
            if (authority == null) return "accepted-authority-required"
            val nominal = field(authority, "nominal") as Problem
            val objective = field(authority, "objective") as LinearObjective
            if (costs == null) return "prepared-objective-required"
            if (costs.constant != objective.constant) return "objective-constant-changed"
            for (name in listOf("BoolWeightValues", "IntCoefficientValues", "RealCoefficientValues")) {
                val original = get(objective, name)
                val prepared = get(costs, name)
                val size = when (original) {
                    is LongArray -> original.size.toLong() + (prepared as LongArray).size
                    is DoubleArray -> original.size.toLong() + (prepared as DoubleArray).size
                    else -> error("unexpected objective buffer")
                }
                complete = charge(1L + size)
                if (!complete) return null
                val same = when (original) {
                    is LongArray -> original.contentEquals(prepared as LongArray)
                    is DoubleArray -> original.contentEquals(prepared as DoubleArray)
                    else -> false
                }
                if (!same) return "objective-$name-changed"
            }
            if (working.numBoolVars != nominal.numBoolVars || working.numIntVars != nominal.numIntVars ||
                working.numRealVars != nominal.numRealVars) return "coordinate-count-changed"
            if (mapping == null) return "source-mapping-required"
            if (get(mapping, "Source") !== nominal) return "source-model-identity-changed"
            val guarantees = checkNotNull(get(mapping, "Guarantees"))
            if (get(guarantees, "Objective") != true) return "objective-preservation-required"
            if (get(guarantees, "Reconstructability") != true) return "source-reconstruction-required"
            val adjustment = checkNotNull(get(mapping, "ObjectiveAdjustment"))
            if (get(adjustment, "Scale") != BigFraction.ONE) return "objective-scale-changed"
            if (get(adjustment, "Offset") != BigFraction.ZERO) return "objective-offset-changed"
            val chain = field(mapping, "capturedChain") as List<*>
            if (chain.size > 64) {
                complete = false
                return null
            }
            for (stage in chain.ifEmpty { listOf(mapping) }) {
                complete = charge(1L)
                if (!complete) return null
                val row = checkNotNull(stage)
                if (get(row, "OpaqueReconstruction") == true) return "opaque-stage-reconstruction"
                val rebuild = checkNotNull(get(row, "Rebuild"))
                if ((get(rebuild, "Steps") as List<*>).isNotEmpty()) return "stage-rebuild-steps-required"
            }
            return null
        }
        val decline = reason()
        complete = complete && !stop()
        return buildJsonObject {
            put("scope", "prepared-accepted-authority-mapping-guards")
            put("complete", complete)
            put("work", work)
            put("maximumWork", limit)
            put("maximumStages", 64)
            put("firstDecline", if (complete) decline else null)
            if (!complete) put("compatible", JsonNull) else put("compatible", decline == null)
            put("selectionOrSearchExecuted", false)
        }
    }

    private fun get(owner: Any, name: String): Any? = NumericalResidualProfile.call(owner, "get$name")

    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).apply {
        isAccessible = true
    }.get(owner)
}
