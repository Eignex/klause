package com.eignex.klause.bench.profile

import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.localsearch.movesource.ObjectiveSeed
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.IncrementalObjective
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Cancellation
import java.io.File
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun captureObjectiveProposals(
    view: TrajectoryView,
    source: Sample,
    functional: IncrementalObjective?,
    canonical: LinearObjective?,
    sourceObjective: LinearObjective,
    sourceModel: BakedProblem,
    out: File,
) {
    val bytes = trajectoryThreadBytes()
    val started = System.nanoTime()
    val decline = view.load(source)
    if (decline != null || canonical == null) {
        out.writeText(buildJsonObject { put("decline", decline ?: "canonical-objective") }.toString())
        return
    }
    val state = view.state
    val boundary = trajectoryRandomBoundary(state)
    val raw = MoveSink()
    val sink = view.proposalSink()
    ObjectiveSeed().generate(state, raw)
    ObjectiveSeed().generate(state, sink)
    val stop = Cancellation.after(10.seconds)
    val probes = sink.list.distinct().take(32).map { move ->
        if (stop()) return@map buildJsonObject { put("decline", "probe-time-budget") }
        val inspection = state.inspectMove(move, canonical)
        val point = view.reconstruct(inspection.assignment)
        val agreement = functional?.let {
            Class.forName("com.eignex.klause.localsearch.ObjectiveProposalGeometryKt").methods.single {
                it.name == "objectiveProposalValuesAgree"
            }.invoke(null, state, it, move) as Boolean
        }
        buildJsonObject {
            put("move", move.toString())
            put("source", trajectorySource(point))
            put("canonicalValue", canonical.evaluateExact(inspection.assignment).toString())
            put("sourceExpressionValue", sourceObjective.evaluateExact(point).toString())
            put("completeCandidateValueAgreement", agreement)
            put("netDelta", inspection.netDelta)
            put("objectiveDelta", inspection.objectiveDelta)
            put("nativeSourceAccepted", FiniteConflictSelection.checkSource(sourceModel, point, stop))
            put("independentWitnessCheck", false)
        }
    }
    check(boundary == trajectoryRandomBoundary(state))
    out.writeText(buildJsonObject {
        put("source", trajectorySource(source))
        put("unfilteredTemplates", raw.list.size)
        put("ordinarySinkMoves", sink.list.size)
        put("distinctOrdinarySinkMoves", sink.list.distinct().size)
        put("filterAccounting", "templates precede pins, definitions and owners; channel expansion can add moves")
        put("candidates", JsonArray(probes))
        put("candidateLimit", 32)
        put("cancelled", stop())
        put("diagnosticNanos", System.nanoTime() - started)
        put("diagnosticAllocatedBytes", trajectoryThreadBytes() - bytes)
        put("proposalEquivalenceClaim", false)
        put("globalEquivalenceFromSamples", false)
    }.toString())
}
