package com.eignex.klause.bench.profile

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.pipeline.FinitePipelinePreparation
import com.eignex.klause.solver.result.SampleResult
import com.eignex.klause.util.Cancellation
import java.io.File
import java.util.Base64
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

internal object ObjectivePointReplay {
    fun run(
        options: Map<String, String>,
        out: File,
        source: Problem,
        names: Map<String, Int>,
        prepared: FinitePipelinePreparation,
        originalObjective: LinearObjective,
        objective: LinearObjective,
        containing: Cancellation,
    ) {
        val work = PresolveBudget(5_000_000)
        val stop = work.orSpent(containing or Cancellation.after(5.seconds))
        out.resolve("point-replay.jsonl").bufferedWriter().use { output ->
            var records = 0
            fun publish(record: JsonObject) {
                val encoded = record.toString()
                check(++records <= 16 && encoded.encodeToByteArray().size <= 4096)
                output.appendLine(encoded)
                output.flush()
            }
            try {
                probe(options, source, names, prepared, originalObjective, objective, stop, ::publish)
            } catch (failure: Exception) {
                publish(buildJsonObject {
                    put("event", "declined")
                    put("reason", failure.message?.take(512))
                    put("failure", failure::class.simpleName)
                })
            }
            publish(buildJsonObject {
                put("event", "stop")
                put("work", work.spent())
                put("cancelled", stop())
                put("independentlyChecked", false)
            })
        }
    }

    private fun probe(
        options: Map<String, String>,
        source: Problem,
        names: Map<String, Int>,
        prepared: FinitePipelinePreparation,
        originalObjective: LinearObjective,
        objective: LinearObjective,
        stop: Cancellation,
        publish: (JsonObject) -> Unit,
    ) {
        val encoded = options.getValue("point64")
        require(encoded.length <= 87_384)
        val bytes = Base64.getDecoder().decode(encoded)
        require(bytes.size <= 65_536 && sha256(bytes) == options.getValue("point-sha256"))
        stop.charge(encoded.length.toLong() + bytes.size)
        val values = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        require(values.keys == names.keys)
        val namedPins = names.map { (name, variable) -> variable to values.getValue(name).jsonPrimitive.long }.toMap()
        val sourceResult = BacktrackSolver(source.bake(stop)).sample(
            BacktrackParams(assumptions = Assumptions(ints = namedPins), cancellation = stop, maxDecisions = 64),
        )
        publish(buildJsonObject {
            put("event", "source-completion")
            put("nativeResult", sourceResult::class.simpleName)
            put("pointSha256", sha256(bytes))
            put("namedIntegers", namedPins.size)
            put("sourceCost", (sourceResult as? SampleResult.Found)?.let {
                originalObjective.evaluateExact(it.sample).toString()
            })
        })
        if (sourceResult !is SampleResult.Found || stop()) return
        val point = sourceResult.sample
        check(names.all { (name, variable) -> point.intValue(variable) == values.getValue(name).jsonPrimitive.long })
        val mapping = prepared.javaClass.getDeclaredField("sourceMapping").apply { isAccessible = true }.get(prepared)
        requireNotNull(mapping)
        val mappingSource = mapping.javaClass.getDeclaredField("source").apply { isAccessible = true }.get(mapping)
        val mappingTarget = mapping.javaClass.getDeclaredField("target").apply { isAccessible = true }.get(mapping)
        require(mappingSource === source && mappingTarget === prepared.problem) { "point mapping has a foreign model" }
        val retained = Class.forName("com.eignex.klause.presolve.SourceMappingKt").declaredMethods.single {
            it.name == "retainedCoordinates" && it.parameterCount == 2
        }.invoke(null, mapping, stop) as? VarRemap
        requireNotNull(retained) { "point mapping has no direct-coordinate view" }
        val target = project(point, prepared, retained, objective, stop)
        val reconstructed = prepared.reconstruct(target)
        val matches = point.bools.contentEquals(reconstructed.bools) && point.ints.contentEquals(reconstructed.ints)
        val sourceCost = originalObjective.evaluateExact(point)
        val targetCost = objective.evaluateExact(target)
        publish(buildJsonObject {
            put("event", "transport")
            put("sourceCost", sourceCost.toString())
            put("preparedCost", targetCost.toString())
            put("reconstructionMatches", matches)
        })
        // A direct-coordinate candidate is only a partial inverse; a mismatch proves no loss of a source point.
        require(matches && sourceCost == targetCost) { "direct-coordinate transport declined this point" }
        check(!stop())
        val result = BacktrackSolver(prepared.problem as BakedProblem).sample(
            BacktrackParams(assumptions = Assumptions(
                bools = target.bools.indices.associateWith { target.boolValue(it) },
                ints = target.ints.indices.associateWith { target.intValue(it) },
            ), cancellation = stop, maxDecisions = 64),
        )
        publish(buildJsonObject {
            put("event", "prepared-point-check")
            put("nativeResult", result::class.simpleName)
            put("assignmentMatches", (result as? SampleResult.Found)?.let {
                it.sample.bools.contentEquals(target.bools) && it.sample.ints.contentEquals(target.ints)
            })
            put("sourceCost", sourceCost.toString())
            put("preparedCost", targetCost.toString())
            put("cancelled", stop())
            put("independentlyChecked", false)
        })
    }

    private fun project(
        source: Sample,
        prepared: FinitePipelinePreparation,
        retained: VarRemap,
        objective: LinearObjective,
        stop: Cancellation,
    ): Sample {
        require(retained.boolCount == source.numBoolVars && retained.intCount == source.numIntVars)
        val problem = prepared.problem as BakedProblem
        val roots = PropagationSession(problem, cancellation = stop)
        val boolPins = arrayOfNulls<Boolean>(problem.numBoolVars)
        val intPins = arrayOfNulls<Long>(problem.numIntVars)
        for (variable in 0 until source.numBoolVars) {
            stop.charge(1)
            val target = retained.bool(variable)
            if (target >= 0) boolPins[target] = source.boolValue(variable)
        }
        for (variable in 0 until source.numIntVars) {
            stop.charge(1)
            val target = retained.int(variable)
            if (target >= 0) intPins[target] = source.intValue(variable)
        }
        val boolReads = BooleanArray(problem.numBoolVars)
        val intReads = BooleanArray(problem.numIntVars)
        for (factor in problem.factors) {
            stop.charge(1L + factor.boolVars.size + factor.intVars.size)
            for (variable in factor.boolVars) boolReads[variable] = true
            for (variable in factor.intVars) intReads[variable] = true
            check(!stop())
        }
        val weights = objective.boolWeights
        val coefficients = objective.intCoefficients
        val bools = BooleanArray(problem.numBoolVars) { variable ->
            stop.charge(1)
            boolPins[variable] ?: roots.boolValue(variable) ?: run {
                require(!boolReads[variable] && weights.getOrElse(variable) { 0L } == 0L)
                false
            }
        }
        val ints = LongArray(problem.numIntVars) { variable ->
            stop.charge(1)
            intPins[variable] ?: roots.intDomain(variable).let { domain ->
                require(domain.isFixed || !intReads[variable] && coefficients.getOrElse(variable) { 0L } == 0L)
                domain.min
            }
        }
        return Sample(bools, ints)
    }
}
