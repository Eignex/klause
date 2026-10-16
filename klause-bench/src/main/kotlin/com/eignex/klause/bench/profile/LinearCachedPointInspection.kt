package com.eignex.klause.bench.profile

import com.eignex.klause.formats.smtlib.SmtLibProblem
import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.pipeline.PlannedSearch
import com.eignex.klause.solver.result.OpenTheoryWorkSink
import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object LinearCachedPointInspection {
    fun inspect(
        compiled: SmtLibProblem,
        run: Any?,
        planned: PlannedSearch?,
        work: OpenTheoryWorkSink,
        sourceHash: String,
        out: File,
    ) {
        val observation = try {
            capture(compiled, run, planned, work)
        } catch (missing: NoSuchFieldException) {
            unavailable("layout-field-unavailable: ${missing.message}")
        }
        out.resolve("cached-root-point.json").writeText(buildJsonObject {
            put("schema", 1)
            put("sourceSha256", sourceHash)
            put("entryPoint", "direct-library-linear-root-opening")
            put("sourceVerdictClaim", false)
            put("candidatePublished", false)
            put("additionalSolveOrTraversal", false)
            put("diagnosticAfterOpening", true)
            put("observation", observation)
        }.toString() + "\n")
    }

    private fun capture(
        compiled: SmtLibProblem,
        run: Any?,
        planned: PlannedSearch?,
        work: OpenTheoryWorkSink,
    ): JsonObject {
        if (run == null || planned == null) return unavailable("no-live-search-run")
        val component = planned.theory ?: return unavailable("no-live-theory-component")
        if (component.javaClass.name != "com.eignex.klause.theory.qflra.ExactLiraSearchComponent") {
            return unavailable("different-theory-component")
        }
        val lpLazy = field(component, "lpDelegate") as Lazy<*>
        if (!lpLazy.isInitialized()) return unavailable("lp-not-initialized")
        val systemLazy = field(component, "system\$delegate") as Lazy<*>
        if (!systemLazy.isInitialized()) return unavailable("source-system-not-initialized")
        val lp = requireNotNull(lpLazy.value)
        val system = requireNotNull(systemLazy.value)
        val owner = field(lp, "owner") ?: return unavailable("no-existing-lp-owner")
        val result = field(owner, "lastResult") ?: return unavailable("no-cached-certified-result")
        val float = field(result, "float") ?: return unavailable("no-cached-float-result")
        val primal = field(float, "primal") as DoubleArray
        if (primal.size > MAX_COORDINATES) return unavailable("diagnostic-vector-limit")
        val model = field(component, "model") as Problem
        if (compiled.model.numBoolVars > MAX_COORDINATES) return unavailable("diagnostic-boolean-limit")
        val prepared = requireNotNull(field(run, "prepared"))
        val preparedSource = field(prepared, "source")
        val preparedProblem = field(prepared, "problem")
        val trail = requireNotNull(field(owner, "trail"))
        val state = requireNotNull(field(trail, "state"))
        val stateModel = requireNotNull(field(state, "model"))
        val structuralColumns = (field(stateModel, "matrix") as List<*>).size
        val logicalRows = (field(stateModel, "rows") as List<*>).size
        val totalColumns = (field(stateModel, "columns") as List<*>).size
        val prefix = model.numRealVars + model.numIntVars
        val rawBits = LongArray(primal.size) { primal[it].toRawBits() }
        val theoryBools = (field(component, "bools") as IntArray).copyOf()
        val session = planned.session
        val sessionBools = Array(compiled.model.numBoolVars) { session.boolValue(it) }
        val checks = field(session, "checks")
        val level = session.decisionLevel
        val counters = work.snapshot()
        val stateRevisions = revisions(state)
        val lpClosed = field(lp, "closed")
        val lpInvalidated = field(lp, "invalidated")
        val ownerClosed = field(owner, "closed")
        val ownerWorking = field(owner, "workingActive")
        val componentContextMatches = field(component, "context") === session
        val modelKeyMatches = field(lp, "modelKey") === model
        val systemSourceMatches = field(system, "source") === model
        val floatStateMatches = field(float, "exactState") === state
        val sourceRealCount = field(component, "sourceRealCount") as Int
        val nameRanges = compiled.boolVarNames.values.all { it in 0 until compiled.model.numBoolVars } &&
            compiled.intVarNames.values.all { it in 0 until compiled.model.numIntVars } &&
            compiled.realVarNames.values.all { it in 0 until compiled.model.numRealVars }
        val mapping = preparedSource === compiled.model && preparedProblem === compiled.model &&
            model.numIntVars == compiled.model.numIntVars && model.numBoolVars == compiled.model.numBoolVars &&
            sourceRealCount == compiled.model.numRealVars && nameRanges &&
            modelKeyMatches && systemSourceMatches && floatStateMatches &&
            componentContextMatches &&
            lpClosed == false && lpInvalidated == false && ownerClosed == false && ownerWorking == false &&
            level == 0 && (field(state, "scopeMarks") as List<*>).isEmpty() &&
            structuralColumns == prefix && primal.size == structuralColumns
        val observation = buildJsonObject {
            put("status", "CAPTURED")
            put("coordinateMapping", if (mapping) "VERIFIED_UNCHANGED_PREPARATION_AND_PINNED_LAYOUT" else "UNVERIFIED")
            put("mappingIsFeasibilityOrWitnessAuthority", false)
            put("layoutContract", "LiveQfLraSystem.install: real then integer source prefix, followed by expression columns")
            put("preparedSourceIsCompiled", preparedSource === compiled.model)
            put("preparedProblemIsCompiled", preparedProblem === compiled.model)
            put("componentModelClass", model.javaClass.name)
            put("modelKeyIsComponentModel", modelKeyMatches)
            put("systemSourceIsComponentModel", systemSourceMatches)
            put("cachedFloatStateIsCurrent", floatStateMatches)
            put("componentContextIsSession", componentContextMatches)
            put("sourceNameIdsInRange", nameRanges)
            put("sourceRealCount", sourceRealCount)
            put("componentReals", model.numRealVars)
            put("componentIntegers", model.numIntVars)
            put("componentBooleans", model.numBoolVars)
            put("sourcePrefixLength", prefix)
            put("installedStructuralColumns", structuralColumns)
            put("installedLogicalRows", logicalRows)
            put("installedTotalColumns", totalColumns)
            put("floatVectorLength", primal.size)
            put("sessionDecisionLevel", level)
            put("stateDecisionLevel", (field(state, "scopeMarks") as List<*>).size)
            put("lpClosed", lpClosed.toString())
            put("lpInvalidated", lpInvalidated.toString())
            put("ownerClosed", ownerClosed.toString())
            put("ownerWorkingScopeActive", ownerWorking.toString())
            put("stateRevisions", stateRevisions)
            put("cachedVerdict", field(result, "verdict").toString())
            put("floatOptimal", field(float, "optimal") as Boolean)
            put("exactWitnessPresent", field(result, "witness") != null)
            put("declaredBooleans", buildJsonObject {
                compiled.boolVarNames.toSortedMap().forEach { (name, id) ->
                    put(name, buildJsonObject {
                        put("id", id)
                        put("value", if (mapping) sessionBools.getOrNull(id)?.toString() ?: "UNASSIGNED" else "UNMAPPED")
                    })
                }
            })
            put("declaredBooleanCoverage", when {
                !mapping -> "UNMAPPED"
                compiled.boolVarNames.values.all { sessionBools[it] != null } -> "COMPLETE"
                else -> "INCOMPLETE"
            })
            put("declaredIntegers", names(compiled.intVarNames))
            put("declaredReals", names(compiled.realVarNames))
            put("rawSessionBooleanPrefix", buildJsonArray {
                sessionBools.forEach { add(JsonPrimitive(it?.toString() ?: "UNASSIGNED")) }
            })
            put("componentBooleanValues", buildJsonArray { theoryBools.forEach { add(JsonPrimitive(it)) } })
            put("floatPrimalIeee754Bits", buildJsonArray {
                rawBits.forEach { add(JsonPrimitive(java.lang.Long.toUnsignedString(it, 16).padStart(16, '0'))) }
            })
        }
        check(lpLazy.value === lp && systemLazy.value === system && field(lp, "owner") === owner)
        check(field(owner, "lastResult") === result && field(result, "float") === float)
        check(field(float, "primal") === primal && rawBits.indices.all { rawBits[it] == primal[it].toRawBits() })
        check(field(owner, "trail") === trail && field(trail, "state") === state && field(state, "model") === stateModel)
        check(revisions(state) == stateRevisions && field(lp, "closed") == lpClosed)
        check(field(lp, "invalidated") == lpInvalidated && field(owner, "closed") == ownerClosed)
        check(field(owner, "workingActive") == ownerWorking)
        check((field(lp, "modelKey") === model) == modelKeyMatches)
        check((field(system, "source") === model) == systemSourceMatches)
        check((field(float, "exactState") === state) == floatStateMatches)
        check((field(component, "context") === session) == componentContextMatches)
        check((field(component, "bools") as IntArray).contentEquals(theoryBools))
        check(sessionBools.indices.all { session.boolValue(it) == sessionBools[it] })
        check(session.decisionLevel == level && field(session, "checks") == checks && work.snapshot() == counters)
        return buildJsonObject {
            observation.forEach { (key, value) -> put(key, value) }
            put("snapshotStateAndValuesUnchanged", true)
        }
    }

    private fun names(names: Map<String, Int>): JsonObject = buildJsonObject {
        names.toSortedMap().forEach { (name, id) -> put(name, id) }
    }

    private fun revisions(state: Any): JsonObject = buildJsonObject {
        for (name in listOf("matrixRevision", "boundRevision", "objectiveRevision", "popRevision", "rowRevision")) {
            put(name, field(state, name) as Long)
        }
    }

    private fun unavailable(reason: String): JsonObject = buildJsonObject {
        put("status", "UNAVAILABLE")
        put("reason", reason)
        put("coordinateMapping", "UNVERIFIED")
    }

    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(owner)

    private const val MAX_COORDINATES = 65_536
}
