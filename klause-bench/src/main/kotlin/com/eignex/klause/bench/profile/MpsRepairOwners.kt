package com.eignex.klause.bench.profile

import com.eignex.klause.formats.mps.checkMpsWitness
import com.eignex.klause.ir.LinearSourceProblem
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.Term
import com.eignex.klause.propagation.bake
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.pipeline.FinitePipelinePreparation
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.seconds

internal object MpsRepairOwners {
    fun inspect(request: JsonObject, source: LinearSourceProblem, model: Problem, context: SourceProofContext,
        objective: LinearObjective, center: Sample, columns: IntArray, scale: BigFraction, out: File,
    ) {
        val missing = (0 until model.numIntVars).filter {
            !model.intBounds.hasLower(it) || !model.intBounds.hasUpper(it)
        }
        val openInputs = source.variables.filter { it.integer && (it.lower == null || it.upper == null) }
        if (missing.isNotEmpty() || openInputs.isNotEmpty() || model.numIntVars.toLong() + model.numRealVars > 4096) {
            out.resolve("owner-overlap.json").writeText(buildJsonObject {
                put("status", "unavailable")
                put("reason", if (missing.isNotEmpty() || openInputs.isNotEmpty())
                    "finite owners require closed source and working integer bounds"
                    else "finite replay resource limit")
                put("openWorkingIntegers", JsonArray(missing.map(::JsonPrimitive)))
                put("openSourceIntegers", JsonArray(openInputs.map { JsonPrimitive(it.name) }))
                put("finiteDomainsInvented", false)
                put("mixedColdReportsRetained", true)
                put("wholeFamilyInapplicabilityClaim", false)
            }.toString())
            return
        }
        val stop = Cancellation.after(20.seconds)
        val baked = model.bake(stop)
        check(!stop())
        val inputs = columns.indices.filter { source.variables[it].integer }.map { Term.intVar(columns[it]) }
            .toIntArray()
        val backings = columns.indices.filter { !source.variables[it].integer && Term.isInt(columns[it]) }
            .map { Term.intVar(columns[it]) }
        val controls = out.resolve("owner-controls.jsonl")
        controls.bufferedWriter().use { writer ->
            for (index in request.getValue("repairSets").jsonArray.indices) {
                val report = out.resolve("repair-$index.json")
                if (!report.exists()) continue
                val saved = Json.parseToJsonElement(report.readText()).jsonObject
                val freed = saved.getValue("effectiveFreedIntegers").jsonArray
                    .map { it.jsonPrimitive.content.toInt() }
                writer.appendLine(buildJsonObject {
                    put("selector", "captured-mixed-source-freedoms")
                    put("candidateIndex", index)
                    put("center", RepairOwnerReplay.sample(center))
                    put("centerObjective", objective.evaluate(center))
                    put("effectiveInts", JsonArray((freed + backings).distinct().map(::JsonPrimitive)))
                }.toString())
            }
        }
        val prepared = FinitePipelinePreparation(baked, objective, { it }, null)
        val verify: (Sample, Cancellation) -> RepairOwnerReplay.PointCheck = { sample, token ->
            val point = columns.map { column ->
                if (Term.isInt(column)) BigFraction.of(sample.exactIntValue(Term.intVar(column)), BIG_ONE)
                else checkNotNull(sample.exactReals)[Term.realVar(column)]
            }
            val checked = source.checkMpsWitness(point, token)
            RepairOwnerReplay.PointCheck(checked != null, checked?.objective)
        }
        RepairOwnerReplay.replay(controls, baked, objective, prepared, baked, inputs,
            out.resolve("owners.jsonl"), request["seed"]?.jsonPrimitive?.content?.toLong() ?: 1L,
            request["ownerWork"]?.jsonPrimitive?.content?.toLong() ?: 100_000L,
            request["ownerFragmentWork"]?.jsonPrimitive?.content?.toLong() ?: 2_000L,
            verify, context, source.objective?.sense == ObjectiveSense.MAXIMIZE)
        out.resolve("owner-overlap.json").writeText(buildJsonObject {
            put("status", "captured")
            put("sourceSha256", request.getValue("sha256").jsonPrimitive.content)
            put("sourceScope", NumericalResidualProfile.json(context.scope))
            put("sourceSemantics", NumericalResidualProfile.json(source.semantics))
            put("sourceObjectiveScale", scale.toString())
            put("sourceObjectiveSense", source.objective?.sense?.toString())
            put("sourceColumns", JsonArray(columns.indices.map { index -> buildJsonObject {
                val column = columns[index]
                put("name", source.variables[index].name)
                put("declaredInteger", source.variables[index].integer)
                put("workingKind", if (Term.isInt(column)) "integer" else "real")
                put("workingIndex", if (Term.isInt(column)) Term.intVar(column) else Term.realVar(column))
            } }))
            put("freeSourceRealIntegerBackings", JsonArray(backings.map(::JsonPrimitive)))
            put("finiteDomainsInvented", false)
            put("mixedUsesOriginalSourceRows", true)
            put("finiteClosureAuthority", "conditioned finite working model only")
            put("allCandidatesCheckedAgainstOriginalSource", true)
            put("independentWitnessCheck", false)
            put("globalProofClaim", false)
        }.toString())
    }
}
