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
import kotlinx.serialization.json.JsonObject
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
): Boolean {
    val bytes = trajectoryThreadBytes()
    val started = System.nanoTime()
    val decline = view.load(source)
    if (decline != null || canonical == null) {
        out.writeText(buildJsonObject { put("decline", decline ?: "canonical-objective") }.toString())
        return false
    }
    val state = view.state
    val boundary = trajectoryRandomBoundary(state)
    val raw = MoveSink()
    val sink = view.proposalSink()
    ObjectiveSeed().generate(state, raw)
    ObjectiveSeed().generate(state, sink)
    val stop = Cancellation.after(10.seconds)
    val candidates = sink.list.distinct()
    val probes = ArrayList<JsonObject>()
    var visited = 0
    var disagreements = 0
    for (move in candidates) {
        if (stop()) break
        val inspection = state.inspectMove(move, canonical)
        val point = view.reconstruct(inspection.assignment)
        val agreement = functional?.let { gradient ->
            Class.forName("com.eignex.klause.localsearch.ObjectiveProposalGeometryKt").methods.single {
                it.name == "objectiveProposalValuesAgree"
            }.invoke(null, state, gradient, move) as Boolean
        }
        val canonicalValue = canonical.evaluateExact(inspection.assignment)
        val sourceValue = sourceObjective.evaluateExact(point)
        val sourceAgreement = canonicalValue == sourceValue
        visited++
        if (agreement != true || !sourceAgreement) disagreements++
        if (probes.size < 32) probes += buildJsonObject {
            put("move", move.toString())
            put("source", trajectorySource(point))
            put("canonicalValue", canonicalValue.toString())
            put("sourceExpressionValue", sourceValue.toString())
            put("completeCandidateValueAgreement", agreement == true && sourceAgreement)
            put("netDelta", inspection.netDelta)
            put("objectiveDelta", inspection.objectiveDelta)
            put("nativeSourceAccepted", FiniteConflictSelection.checkSource(sourceModel, point, stop))
            put("independentWitnessCheck", false)
        }
    }
    val complete = visited == candidates.size && disagreements == 0 && functional != null
    check(boundary == trajectoryRandomBoundary(state))
    out.writeText(buildJsonObject {
        put("source", trajectorySource(source))
        put("unfilteredTemplates", raw.list.size)
        put("ordinarySinkMoves", sink.list.size)
        put("distinctOrdinarySinkMoves", sink.list.distinct().size)
        put("completeCandidateValuesChecked", visited)
        put("candidateValueDisagreements", disagreements)
        put("candidateValueQualification", complete)
        if (!complete) put("decline", when {
            functional == null -> "no-functional-objective"
            disagreements > 0 -> "candidate-value-disagreement"
            else -> "incomplete-candidate-value-scan"
        })
        put("filterAccounting", "templates precede pins, definitions and owners; channel expansion can add moves")
        put("candidates", JsonArray(probes))
        put("candidateLimit", 32)
        put("cancelled", stop())
        put("diagnosticNanos", System.nanoTime() - started)
        put("diagnosticAllocatedBytes", trajectoryThreadBytes() - bytes)
        put("proposalEquivalenceClaim", false)
        put("globalEquivalenceFromSamples", false)
    }.toString())
    return complete
}
