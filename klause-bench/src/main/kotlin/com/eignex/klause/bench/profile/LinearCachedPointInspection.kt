package com.eignex.klause.bench.profile

import com.eignex.klause.formats.smtlib.SmtLibProblem
import com.eignex.klause.ir.Problem
import com.eignex.klause.simplex.exact.BigFraction
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
            put("schema", 2)
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
        val witness = field(result, "witness")
        val exactPrimal = witness?.let { field(it, "primal") as List<*> }
        val exactLength = exactPrimal?.size
        val exactValues = exactPrimal?.takeIf { it.size <= MAX_COORDINATES }?.toList()
        val model = field(component, "model") as Problem
        if (compiled.model.numBoolVars > MAX_COORDINATES) return unavailable("diagnostic-boolean-limit")
        val prepared = requireNotNull(field(run, "prepared"))
        val preparedSource = field(prepared, "source")
        val preparedProblem = field(prepared, "problem")
        val sourceMappingMetadata = mappingMetadata(prepared, compiled)
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
            put("sourceMappingMetadata", sourceMappingMetadata)
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
            put("exactWitnessPresent", witness != null)
            put("exactPointSnapshot", exactPoint(exactValues, exactLength))
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
        check(field(result, "witness") === witness)
        check(witness == null || field(witness, "primal") === exactPrimal)
        check(exactValues == null || exactValues.indices.all { exactPrimal?.get(it) === exactValues[it] })
        check(exactPrimal?.size == exactLength)
        check(field(float, "primal") === primal && rawBits.indices.all { rawBits[it] == primal[it].toRawBits() })
        check(field(owner, "trail") === trail && field(trail, "state") === state && field(state, "model") === stateModel)
        check(revisions(state) == stateRevisions && field(lp, "closed") == lpClosed)
        check(field(lp, "invalidated") == lpInvalidated && field(owner, "closed") == ownerClosed)
        check(field(owner, "workingActive") == ownerWorking)
        check((field(lp, "modelKey") === model) == modelKeyMatches)
        check((field(system, "source") === model) == systemSourceMatches)
        check((field(float, "exactState") === state) == floatStateMatches)
        check((field(component, "context") === session) == componentContextMatches)
        check(mappingMetadata(prepared, compiled) == sourceMappingMetadata)
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

    private fun exactPoint(values: List<*>?, length: Int?): JsonObject {
        if (length == null) return unavailable("no-cached-exact-witness")
        if (values == null) return unavailable("diagnostic-exact-vector-limit")
        val copied = ArrayList<Pair<String, String>>(values.size)
        var remaining = MAX_EXACT_TEXT_CHARACTERS
        for (raw in values) {
            val value = raw as BigFraction
            val numerator = value.num.toString()
            val denominator = value.den.toString()
            if (numerator.length > remaining) return unavailable("diagnostic-exact-text-limit")
            remaining -= numerator.length
            if (denominator.length > remaining) return unavailable("diagnostic-exact-text-limit")
            remaining -= denominator.length
            copied += numerator to denominator
        }
        return buildJsonObject {
            put("status", "CAPTURED")
            put("authority", "CACHED_CONTINUOUS_LP_POINT_ONLY")
            put("vectorLength", length)
            put("values", buildJsonArray {
                copied.forEach { (numerator, denominator) ->
                    add(buildJsonObject {
                        put("numerator", numerator)
                        put("denominator", denominator)
                    })
                }
            })
        }
    }

    private fun mappingMetadata(prepared: Any, compiled: SmtLibProblem): JsonObject {
        val mapping = requireNotNull(field(prepared, "mapping"))
        val rebuild = requireNotNull(field(prepared, "rebuild"))
        val steps = field(rebuild, "steps") as List<*>
        if (steps.size > MAX_COORDINATES) return unavailable("diagnostic-rebuild-step-limit")
        val writes = steps.map { raw ->
            val step = requireNotNull(raw)
            val kind = step.javaClass.simpleName
            val space = when (kind) {
                "CopyLiteral", "SatisfyClauses", "RepairClause" -> "BOOLEAN"
                "AffineValue", "QuotientValue" -> "INTEGER"
                else -> "UNKNOWN"
            }
            val variable = when (kind) {
                "RepairClause" -> (field(step, "literal") as Int) ushr 1
                "CopyLiteral", "SatisfyClauses", "AffineValue", "QuotientValue" -> field(step, "variable") as Int
                else -> null
            }
            buildJsonObject {
                put("kind", step.javaClass.name)
                put("space", space)
                put("mayWriteId", variable)
                put("writesDeclaredCoordinate", when (space) {
                    "BOOLEAN" -> variable in compiled.boolVarNames.values
                    "INTEGER" -> variable in compiled.intVarNames.values
                    else -> null
                })
            }
        }
        val source = field(prepared, "source") as Problem
        val target = field(prepared, "problem") as Problem
        return buildJsonObject {
            put("status", "CAPTURED")
            put("reconstructionInvoked", false)
            put("coordinateMappingPolicyChanged", false)
            put("mappingSourceIsPreparedSource", field(mapping, "source") === source)
            put("mappingTargetIsPreparedProblem", field(mapping, "target") === target)
            put("mappingRebuildIsPreparedRebuild", field(mapping, "rebuild") === rebuild)
            put("capturedMappingChainLength", (field(mapping, "capturedChain") as List<*>).size)
            put("sourceBooleans", source.numBoolVars)
            put("sourceIntegers", source.numIntVars)
            put("sourceReals", source.numRealVars)
            put("targetBooleans", target.numBoolVars)
            put("targetIntegers", target.numIntVars)
            put("targetReals", target.numRealVars)
            put("sourcePassesFired", buildJsonArray {
                (field(prepared, "passesFired") as List<*>).forEach { pass ->
                    add(JsonPrimitive((pass as Enum<*>).name))
                }
            })
            put("rebuildSteps", buildJsonArray { writes.forEach { add(it) } })
        }
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
    private const val MAX_EXACT_TEXT_CHARACTERS = 8 * 1024 * 1024
}
