package com.eignex.klause.bench.profile

import com.eignex.klause.ir.Problem
import com.eignex.klause.util.Cancellation
import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object LinearLpShapeInspection {
    fun inspect(component: Any?, stop: Cancellation, out: File) {
        val started = System.nanoTime()
        if (stop()) return skipped(out, "original-deadline-spent", stop)
        if (component == null) return skipped(out, "running-theory-unavailable", stop)
        val lpDelegate = field(component, "lpDelegate") as? Lazy<*>
        val systemDelegate = field(component, "system\$delegate") as? Lazy<*>
        if (lpDelegate?.isInitialized() != true || systemDelegate?.isInitialized() != true) {
            return skipped(out, "live-linear-owners-uninitialized", stop)
        }
        val lp = lpDelegate.value ?: return skipped(out, "live-lp-unavailable", stop)
        val system = systemDelegate.value ?: return skipped(out, "live-system-unavailable", stop)
        val state = member(lp, "getState") ?: return skipped(out, "live-exact-state-unavailable", stop)
        val model = member(state, "getModel") ?: return skipped(out, "live-model-unavailable", stop)
        val source = field(component, "model") as? Problem
            ?: return skipped(out, "component-source-unavailable", stop)
        val definitions = field(system, "definitions") as? Map<*, *>
            ?: return skipped(out, "live-definitions-unavailable", stop)
        val activeSide = state.javaClass.methods.singleOrNull {
            it.name.substringBefore('$') == "activeSide" && it.parameterCount == 2
        } ?: return skipped(out, "active-bound-access-unavailable", stop)
        var visited = 0
        var bounded = 0
        var free = 0
        val structuralColumns = (member(model, "getN") as? Number)?.toInt()
            ?: return skipped(out, "live-structural-dimensions-unavailable", stop)
        val boundedRows = HashSet<Int>()
        for (column in definitions.values) {
            if (stop()) break
            require(column is Int)
            if (activeSide.invoke(state, column, false) != null || activeSide.invoke(state, column, true) != null) {
                bounded++
                boundedRows += column - structuralColumns
            } else {
                free++
            }
            visited++
        }
        val sourceColumns = inspectSourceColumns(
            state, model, structuralColumns, boundedRows, activeSide, stop,
        )
        val stopped = stop()
        write(out, buildJsonObject {
            put("mode", "live-lp-shape")
            put("status", "measured")
            put("owner", component.javaClass.name)
            put("observation", "returned-running-root-before-close")
            put("sourceVerdictClaim", false)
            put("traversalAdvanced", false)
            put("forcedOwnerInitialization", false)
            put("newNumericalOperation", false)
            put("deadlineRenewed", false)
            put("theoryBooleans", source.numBoolVars)
            put("theoryIntegers", source.numIntVars)
            put("theoryReals", source.numRealVars)
            put("theoryFactors", source.factors.size)
            put("sourceStructuralColumns", source.numRealVars + source.numIntVars)
            put("declaredFixedSourceColumns", (field(system, "declaredFixed") as? Map<*, *>)?.size)
            put("sourceColumnInspection", sourceColumns)
            put("lpStructuralColumns", member(model, "getN") as? Number)
            put("lpRows", member(model, "getM") as? Number)
            put("lpTotalColumns", member(model, "getNumVars") as? Number)
            put("lpScopeDepth", member(state, "getDepth") as? Number)
            put("preparedLayouts", (field(system, "preparedTerms") as? Map<*, *>)?.size)
            put("normalizedLayouts", (field(system, "normalized") as? Map<*, *>)?.size)
            put("initialPreparedDefinitions", (field(system, "terms") as? Collection<*>)?.size)
            put("installedDefinitions", definitions.size)
            put("definitionsVisited", visited)
            put("boundedDefinitions", bounded)
            put("freeDefinitions", free)
            put("definitionCountsComplete", visited == definitions.size && !stopped)
            put("assertedOrdinarySourceRows", (field(component, "sourceAssertionLevels") as? IntArray)
                ?.count { it >= 0 })
            put("stoppedAtReturn", stopped)
            put("elapsedNs", System.nanoTime() - started)
        })
    }

    private fun inspectSourceColumns(
        state: Any,
        model: Any,
        structuralColumns: Int,
        boundedRows: Set<Int>,
        activeSide: java.lang.reflect.Method,
        stop: Cancellation,
    ): JsonObject {
        val matrix = field(model, "matrix") as? List<*>
            ?: return buildJsonObject { put("status", "matrix-metadata-unavailable") }
        require(matrix.size == structuralColumns)
        val getters = HashMap<Pair<Class<*>, String>, java.lang.reflect.Method?>()
        fun get(owner: Any?, name: String): Any? {
            if (owner == null) return null
            val method = getters.getOrPut(owner.javaClass to name) {
                owner.javaClass.methods.singleOrNull {
                    it.name.substringBefore('$') == name && it.parameterCount == 0
                }
            }
            return method?.invoke(owner)
        }
        var visited = 0
        var rootFixed = 0
        var anyDefinition = 0
        var boundedDefinition = 0
        var unfixedBoundedDefinition = 0
        var freeWithoutBoundedDefinition = 0
        for (column in matrix.indices) {
            if (stop()) break
            val entries = matrix[column] as? List<*> ?: error("missing exact column metadata")
            val lower = activeSide.invoke(state, column, false)
            val upper = activeSide.invoke(state, column, true)
            val lowerSide = get(lower, "getSide")
            val upperSide = get(upper, "getSide")
            val lowerValue = get(get(lowerSide, "getNumber"), "getValue")
            val upperValue = get(get(upperSide, "getNumber"), "getValue")
            val fixed = get(lower, "getDepth") == 0 && get(upper, "getDepth") == 0 &&
                get(lowerSide, "getStrict") == false && get(upperSide, "getStrict") == false &&
                lowerValue != null && lowerValue == upperValue &&
                get(lowerSide, "getPremises") == null && get(upperSide, "getPremises") == null
            var used = false
            for (entry in entries) {
                if (stop()) break
                val row = (get(entry, "getRow") as? Number)?.toInt() ?: error("missing exact row metadata")
                if (row in boundedRows) used = true
            }
            if (stop()) break
            if (fixed) rootFixed++
            if (entries.isNotEmpty()) anyDefinition++
            if (used) boundedDefinition++
            if (used && !fixed) unfixedBoundedDefinition++
            if (lower == null && upper == null && !used) freeWithoutBoundedDefinition++
            visited++
        }
        return buildJsonObject {
            put("status", "measured")
            put("visited", visited)
            put("complete", visited == structuralColumns && !stop())
            put("rootFixed", rootFixed)
            put("withAnyDefinition", anyDefinition)
            put("withBoundedDefinition", boundedDefinition)
            put("unfixedWithBoundedDefinition", unfixedBoundedDefinition)
            put("freeWithoutBoundedDefinition", freeWithoutBoundedDefinition)
        }
    }

    private fun skipped(out: File, reason: String, stop: Cancellation) = write(out, buildJsonObject {
        put("mode", "live-lp-shape")
        put("status", "skipped")
        put("reason", reason)
        put("sourceVerdictClaim", false)
        put("forcedOwnerInitialization", false)
        put("deadlineRenewed", false)
        put("stoppedAtReturn", stop())
    })

    private fun write(out: File, result: JsonObject) {
        out.resolve("lp-shape.json").writeText(result.toString() + "\n")
        println(result)
    }

    private fun member(owner: Any, name: String): Any? = owner.javaClass.methods
        .singleOrNull { it.name.substringBefore('$') == name && it.parameterCount == 0 }?.invoke(owner)

    private fun field(owner: Any, name: String): Any? = owner.javaClass.declaredFields
        .singleOrNull { it.name == name }?.apply { isAccessible = true }?.get(owner)
}
