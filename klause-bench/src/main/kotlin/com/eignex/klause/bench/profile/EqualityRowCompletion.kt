package com.eignex.klause.bench.profile

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Cancellation
import java.io.File
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun equalityRowCompletion(
    state: LocalSearchState, objective: LinearObjective, center: Sample, stop: Cancellation, file: File,
) {
    val started = System.nanoTime()
    val allocated = trajectoryThreadBytes()
    val sourceMethod = Class.forName("com.eignex.klause.solver.pipeline.LinearSourceBindingKt").methods
        .single { it.name == "linearAuthorityContext" && it.parameterCount == 5 }
    val source = sourceMethod.invoke(null, state.model.problem, objective, ObjectiveSense.MINIMIZE, stop, false)
    val fundingType = Class.forName("com.eignex.klause.lp.SourceLpFunding")
    val funding = fundingType.getDeclaredConstructor(Long::class.javaPrimitiveType, Long::class.javaPrimitiveType)
        .newInstance(1_000_000L, 4_000_000L)
    fundingType.getMethod("grant", Long::class.javaPrimitiveType).invoke(funding, 1_000_000L)
    val frozen: (Int) -> Boolean = { state.assumptions.isFrozenInt(it) ||
        state.invariants?.isDefinedInt(it) == true || (state.seeding.ownerInt?.get(it) ?: -1) >= 0 }
    val stopped: () -> Boolean = { stop() || LocalSearchTrajectory.getter(funding, "getPaused") == true }
    val type = Class.forName("com.eignex.klause.solver.pipeline.SourceRowCompletion")
    val planner = if (source == null) null else type.constructors.single().newInstance(
        state.model.problem, source, funding, frozen, stopped)
    val seeds = (0 until state.problem.numIntVars).flatMap { listOf(it to -1, it to 1) }
    val supported: (Int) -> Boolean = { false }
    val packets = planner?.let { type.getMethod("complete", Sample::class.java, List::class.java,
        Function1::class.java).invoke(it, center, seeds, supported) as List<*>? }
    file.writeText(buildJsonObject {
        put("elapsedNs", System.nanoTime() - started)
        put("threadBytes", trajectoryThreadBytes() - allocated)
        put("work", LocalSearchTrajectory.getter(funding, "getSpent") as Long)
        put("modeledAllocation", LocalSearchTrajectory.getter(funding, "getAllocated") as Long)
        put("declined", packets == null)
        put("packets", buildJsonArray {
            for (packet in packets.orEmpty().filterNotNull()) add(buildJsonObject {
                put("outcome", LocalSearchTrajectory.getter(packet, "getOutcome").toString())
                put("seed", LocalSearchTrajectory.getter(packet, "getSeed").toString())
                put("members", buildJsonArray {
                    val members = LocalSearchTrajectory.getter(packet, "getMembers") as List<*>
                    for (member in members.filterNotNull()) add(buildJsonObject {
                        val coordinate = LocalSearchTrajectory.getter(member, "getCoordinate") as SourceCoordinate
                        val value = LocalSearchTrajectory.getter(member, "getValue") as BigFraction
                        put("coordinate", coordinate.id)
                        put("value", value.toString())
                        put("row", LocalSearchTrajectory.getter(member, "getRow") as String?)
                    })
                })
                put("role", "requested freedoms; incomplete packets are not source transitions")
            })
        })
    }.toString())
}
