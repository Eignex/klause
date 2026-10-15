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
        for (column in definitions.values) {
            if (stop()) break
            require(column is Int)
            if (activeSide.invoke(state, column, false) != null || activeSide.invoke(state, column, true) != null) {
                bounded++
            } else {
                free++
            }
            visited++
        }
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
