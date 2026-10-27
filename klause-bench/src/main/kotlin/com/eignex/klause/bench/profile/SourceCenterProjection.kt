package com.eignex.klause.bench.profile

import com.eignex.klause.ir.VarRemap
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.localsearch.LocalSearchModel
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.solver.Sample
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import kotlin.random.Random
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

internal fun trajectoryThreadBytes(): Long = (ManagementFactory.getThreadMXBean() as ThreadMXBean).run {
    check(isThreadAllocatedMemorySupported)
    isThreadAllocatedMemoryEnabled = true
    getThreadAllocatedBytes(Thread.currentThread().threadId())
}

internal fun sourceCenter(encoded: String): Sample {
    val value = Json.parseToJsonElement(encoded).jsonObject
    require(value.keys == setOf("bools", "ints"))
    return Sample(value.getValue("bools").jsonArray.map { it.jsonPrimitive.boolean }.toBooleanArray(),
        value.getValue("ints").jsonArray.map { it.jsonPrimitive.long }.toLongArray())
}

internal fun loadSourceCenter(
    source: Sample,
    state: LocalSearchState,
    coordinates: VarRemap,
    reconstruct: (Sample) -> Sample,
    synchronize: () -> Unit,
): String? {
    if (source.numRealVars != 0 || source.numBoolVars != coordinates.boolCount ||
        source.numIntVars != coordinates.intCount
    ) return "source-dimensions"
    val bools = arrayOfNulls<Boolean>(state.problem.numBoolVars)
    val ints = arrayOfNulls<Long>(state.problem.numIntVars)
    for (id in 0 until coordinates.boolCount) {
        val target = coordinates.bool(id)
        if (target < 0) continue
        if (target !in bools.indices) return "boolean-map"
        val value = source.boolValue(id)
        if (bools[target]?.let { it != value } == true) return "boolean-alias"
        bools[target] = value
    }
    for (id in 0 until coordinates.intCount) {
        val target = coordinates.int(id)
        if (target < 0) continue
        if (target !in ints.indices) return "integer-map"
        val value = source.intValue(id)
        if (value !in state.rootDomains[target]) return "target-domain"
        if (ints[target]?.let { it != value } == true) return "integer-alias"
        ints[target] = value
    }
    for (id in bools.indices) {
        if (bools[id] == null && state.invariants?.isDefinedBool(id) != true &&
            !state.assumptions.isFrozenBool(id)
        ) return "unmapped-searched-boolean"
        state.assignment.setBool(id, bools[id] ?: state.assumptions.boolValueOrNull(id) ?: false)
    }
    for (id in ints.indices) {
        if (ints[id] == null && state.invariants?.isDefinedInt(id) != true &&
            state.rootDomains[id].min != state.rootDomains[id].max && !state.assumptions.isFrozenInt(id)
        ) return "unmapped-searched-integer"
        state.assignment.setInt(id, ints[id] ?: state.assumptions.intValueOrNull(id) ?: state.rootDomains[id].min)
    }
    synchronize()
    for (id in ints.indices) if (state.assignment.intValue(id) !in state.rootDomains[id]) return "definition-domain"
    var pinMismatch = false
    state.assumptions.forEachBool { id, value -> if (state.assignment.boolValue(id) != value) pinMismatch = true }
    state.assumptions.forEachInt { id, value -> if (state.assignment.intValue(id) != value) pinMismatch = true }
    if (pinMismatch) return "target-pin"
    if (!sameSource(source, reconstruct(state.assignment.snapshot()))) return "source-round-trip"
    state.recompute()
    return null
}

internal fun projectedSourceCenter(
    source: Sample,
    problem: BakedProblem,
    coordinates: VarRemap,
    sweep: DefinitionalSweep?,
    reconstruct: (Sample) -> Sample,
): Pair<Sample?, String?> {
    val model = LocalSearchModel.of(problem)
    val pins = model.pinsUnder(Assumptions.None) ?: return null to "working-root-infeasible"
    val state = LocalSearchState(model, Random(0), pins)
    state.invariants = sweep?.network(problem.numIntVars, problem.numBoolVars)
    val decline = loadSourceCenter(source, state, coordinates, reconstruct) {
        sweep?.let {
            it.javaClass.methods.single { method -> method.name.substringBefore('$') == "sweepPinned" }
                .invoke(it, state.assignment, state.rootDomains, problem.factors, state.assumptions)
        }
    }
    return if (decline == null && state.cost != 0L) null to "working-infeasible"
    else state.assignment.snapshot().takeIf { decline == null } to decline
}
