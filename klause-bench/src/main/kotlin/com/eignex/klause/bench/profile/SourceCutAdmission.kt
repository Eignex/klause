package com.eignex.klause.bench.profile

import com.eignex.klause.ir.LinearSourceProblem
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.ProblemSettings
import com.eignex.klause.util.ExecutionControl
import com.eignex.klause.util.WorkMeter
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object SourceCutAdmission {
    fun inspect(source: LinearSourceProblem, settings: ProblemSettings, stop: ExecutionControl): JsonObject {
        var work = 0L
        val limit = 1_000_000L
        val control = object : ExecutionControl() {
            override fun isCancelled(): Boolean = stop() || work >= limit
            override fun deadline() = stop.deadline()
            override val accounting = WorkMeter { units -> work += minOf(units, Long.MAX_VALUE - work) }
        }
        fun lower(value: LinearSourceProblem, numerical: Boolean): Any? =
            Class.forName("com.eignex.klause.lowering.LinearSourceViewKt").methods.single {
                it.name == "lowerLinearSource" && it.parameterCount == 5
            }.invoke(null, value, settings, numerical, control, null)
        val nominal = lower(source, source.semantics.effective.feasibilityTolerance?.isExact == false)
        val envelope = Class.forName("com.eignex.klause.solver.pipeline.LinearToleranceEnvelopeKt").methods.single {
            it.name == "absoluteToleranceEnvelope"
        }.invoke(null, source, control) as LinearSourceProblem?
        val accepted = envelope?.let { lower(it, false) }
        val compatible = if (nominal == null || accepted == null || control()) null else
            Class.forName("com.eignex.klause.solver.pipeline.LinearToleranceAuthorityKt").methods.single {
                it.name == "sharesToleranceCoordinates"
            }.invoke(null, nominal, accepted, control) as Boolean
        val nominalModel = nominal?.let { get(it, "Model") as Problem }
        val acceptedModel = accepted?.let { get(it, "Model") as Problem }
        var nonBinary = 0
        val nonBinarySample = ArrayList<Int>(8)
        if (nominalModel != null) for (id in 0 until nominalModel.numIntVars) {
            if (control()) break
            control.charge(1L)
            if (!nominalModel.intBounds.hasLower(id) || !nominalModel.intBounds.hasUpper(id) ||
                nominalModel.intBounds.lower(id) < 0L || nominalModel.intBounds.upper(id) > 1L) {
                nonBinary++
                if (nonBinarySample.size < 8) nonBinarySample += id
            }
        }
        val complete = nominal != null && accepted != null && compatible != null && !control()
        return buildJsonObject {
            put("scope", "recomputed-source-setup-guards")
            put("complete", complete)
            put("work", work)
            put("maximumWork", limit)
            put("selectionOrSearchExecuted", false)
            if (!complete) {
                put("firstDecline", JsonNull)
                return@buildJsonObject
            }
            val model = checkNotNull(nominalModel)
            val nominalView = checkNotNull(nominal)
            val acceptedView = checkNotNull(accepted)
            val shared = checkNotNull(compatible)
            put("nominal", shape(nominalView, model))
            put("accepted", shape(acceptedView, checkNotNull(acceptedModel)))
            put("nonBinaryCanonicalIntegers", nonBinary)
            put("nonBinaryCanonicalSample", buildJsonArray {
                nonBinarySample.forEach { id ->
                    add(buildJsonObject {
                        put("id", id)
                        put("lower", if (model.intBounds.hasLower(id)) model.intBounds.lower(id) else null)
                        put("upper", if (model.intBounds.hasUpper(id)) model.intBounds.upper(id) else null)
                    })
                }
            })
            put("sameColumns", (get(nominalView, "Columns") as IntArray)
                .contentEquals(get(acceptedView, "Columns") as IntArray))
            put("sameObjective", get(nominalView, "Objective") == get(acceptedView, "Objective"))
            put("sameObjectiveScale", get(nominalView, "ObjectiveScale") == get(acceptedView, "ObjectiveScale"))
            put("sharesToleranceCoordinates", shared)
            put("firstDecline", when {
                model.numBoolVars != 0 -> "canonical-boolean-coordinates"
                model.numIntVars == 0 -> "canonical-integer-coordinates-required"
                model.numRealVars == 0 -> "canonical-real-coordinates-required"
                nonBinary != 0 -> "canonical-binary-integers-required"
                !shared -> "accepted-coordinate-compatibility-required"
                get(nominalView, "Objective") == null -> "objective-required"
                else -> null
            })
        }
    }

    private fun shape(view: Any, model: Problem): JsonObject = buildJsonObject {
        put("integers", model.numIntVars)
        put("reals", model.numRealVars)
        put("booleans", model.numBoolVars)
        put("exact", get(view, "Exact") as Boolean)
        put("objectiveScale", get(view, "ObjectiveScale").toString())
    }

    private fun get(owner: Any, name: String): Any? = NumericalResidualProfile.call(owner, "get$name")
}
