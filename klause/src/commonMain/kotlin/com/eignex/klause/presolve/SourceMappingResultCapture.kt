package com.eignex.klause.presolve

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceDerivation
import com.eignex.klause.solver.evidence.SourceEvidence
import com.eignex.klause.solver.evidence.SourceEvidenceCheck
import com.eignex.klause.solver.evidence.SourceMappingEvidence
import com.eignex.klause.solver.evidence.SourceResultCapture
import com.eignex.klause.solver.evidence.SourceResultEvidence
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.fitsLong
import com.eignex.klause.util.toLongExact

internal fun SourceMapping.captureSolve(
    reduced: Problem,
    result: SolveResult,
    capture: SourceResultCapture,
    producer: String,
    cancellation: Cancellation = Cancellation.Never,
): SolveResult {
    require(reduced === target) { "result reconstruction belongs to another reduced model" }
    require(capture.belongsTo(source)) { "result capture belongs to another original source" }
    val mapping = SourceMappingEvidence(capture.context.scope, result.evidence, stages.map { it.justification })
    return when (result) {
        is SolveResult.Sat -> {
            val point = sourcePoint(reduced, result.assignment)
            val captured = capture.solve(SolveResult.Sat(point, result.stats), producer)
            SolveResult.Sat(point, result.stats,
                requireNotNull(captured.evidence).withMappings(capture.context.scope, listOf(mapping)))
        }
        is SolveResult.Unsat -> {
            val proof = mapping.project(SourceClaim.Refutation, capture.context, cancellation).derivation
            val candidate = capture.solve(SolveResult.Unsat(stats = result.stats), producer, proof)
            val evidence = requireNotNull(candidate.evidence)
            val checked = evidence.verify(SourceClaim.Refutation, capture.context, cancellation)
            val engineScope = admittedScope(capture, result.evidence, checked)
            SolveResult.Unsat(stats = result.stats, evidence = evidence.withMappings(engineScope, listOf(mapping)))
        }
        is SolveResult.Unknown -> {
            val evidence = result.evidence ?: SourceResultEvidence(capture.context.scope, emptyList())
            result.copy(evidence = evidence.withMappings(result.evidence?.engineScope, listOf(mapping)))
        }
    }
}

internal fun SourceMapping.captureMinimize(
    reduced: Problem,
    result: MinimizeResult,
    capture: SourceResultCapture,
    objective: LinearObjective,
    producer: String,
    cancellation: Cancellation = Cancellation.Never,
): MinimizeResult {
    require(reduced === target) { "result reconstruction belongs to another reduced model" }
    require(capture.belongsTo(source, objective)) { "result capture belongs to another original source objective" }
    val mapping = SourceMappingEvidence(capture.context.scope, result.evidence, stages.map { it.justification })
    return when (result) {
        is MinimizeResult.Optimal -> captureOptimal(
            reduced, result, capture, objective, producer, mapping, cancellation)
        is MinimizeResult.BestFound -> {
            val point = sourcePoint(reduced, result.sample)
            val candidate = MinimizeResult.BestFound(
                point, objective.minimizedTelemetry(point, capture), result.reason, result.stats)
            val captured = capture.minimize(candidate, objective, producer, cancellation = cancellation)
            val evidence = captured.evidence ?: SourceResultEvidence(capture.context.scope, emptyList())
            MinimizeResult.BestFound(point, candidate.objective, result.reason, result.stats,
                evidence.withMappings(capture.context.scope, listOf(mapping)))
        }
        is MinimizeResult.Infeasible -> {
            val solved = captureSolve(reduced, SolveResult.Unsat(stats = result.stats, evidence = result.evidence),
                capture, producer, cancellation)
            MinimizeResult.Infeasible(stats = result.stats, evidence = solved.evidence)
        }
        is MinimizeResult.Unbounded -> {
            val point = sourcePoint(reduced, result.sample)
            val candidate = MinimizeResult.Unbounded(
                point, objective.minimizedTelemetry(point, capture), result.direction, result.stats)
            val captured = capture.minimize(candidate, objective, producer, cancellation = cancellation)
            val evidence = captured.evidence ?: SourceResultEvidence(capture.context.scope, emptyList())
            val ray = evidence.derivations.map { it.claim }.filterIsInstance<SourceClaim.Ray>().firstOrNull()
            val checked = ray?.let { evidence.verify(it, capture.context, cancellation) }
            val engineScope = admittedScope(capture, result.evidence, checked)
            MinimizeResult.Unbounded(point, candidate.objective, result.direction, result.stats,
                evidence.withMappings(engineScope, listOf(mapping)))
        }
        is MinimizeResult.Unknown -> {
            val evidence = result.evidence ?: SourceResultEvidence(capture.context.scope, emptyList())
            result.copy(evidence = evidence.withMappings(result.evidence?.engineScope, listOf(mapping)))
        }
    }
}

private fun SourceMapping.captureOptimal(
    reduced: Problem,
    result: MinimizeResult.Optimal,
    capture: SourceResultCapture,
    objective: LinearObjective,
    producer: String,
    mapping: SourceMappingEvidence,
    cancellation: Cancellation,
): MinimizeResult.Optimal {
    val point = sourcePoint(reduced, result.sample)
    val candidate = MinimizeResult.Optimal(point, objective.minimizedTelemetry(point, capture), result.stats)
    val provisional = capture.minimize(candidate, objective, producer,
        SourceDerivation.Unavailable("mapped objective bound capture unavailable"), cancellation)
    val claim = provisional.evidence?.derivations?.map { it.claim }
        ?.filterIsInstance<SourceClaim.Optimal>()?.firstOrNull()
    val proof = claim?.let { mapping.project(SourceClaim.Bound(it.value), capture.context, cancellation).derivation }
    val captured = if (proof == null) provisional else capture.minimize(candidate, objective, producer,
        SourceDerivation.Optimum(SourceDerivation.SourceWitness, proof), cancellation)
    val evidence = captured.evidence ?: SourceResultEvidence(capture.context.scope, listOf(
        SourceEvidence(capture.context.scope,
            SourceClaim.Witness(point), SourceDerivation.SourceWitness),
    ))
    val checked = claim?.let { evidence.verify(it, capture.context, cancellation) }
    val engineScope = admittedScope(capture, result.evidence, checked)
    return MinimizeResult.Optimal(point, candidate.objective, result.stats,
        evidence.withMappings(engineScope, listOf(mapping)))
}

private fun LinearObjective.minimizedTelemetry(sample: Sample, capture: SourceResultCapture): Double {
    var value = LinearObjective(boolWeights, intCoefficients, constant).evaluateExact(sample).toDouble()
    for ((id, coefficient) in realCoefficients.withIndex()) {
        if (coefficient != 0.0) value += coefficient * sample.approximateRealValue(id)
    }
    return if (capture.context.scope.objective?.sense == ObjectiveSense.MAXIMIZE) -value else value
}

private fun SourceMapping.admittedScope(
    capture: SourceResultCapture,
    original: SourceResultEvidence?,
    checked: SourceEvidenceCheck?,
) = when {
    checked == SourceEvidenceCheck.Checked -> capture.context.scope
    source === target && original == null -> capture.context.scope
    source === target && original?.scope == capture.context.scope && original.engineScope == capture.context.scope ->
        capture.context.scope
    original?.engineScope != capture.context.scope -> original?.engineScope
    else -> null
}

private fun SourceMapping.sourcePoint(reduced: Problem, sample: Sample): Sample {
    val point = reconstructFrom(reduced, sample)
    if (point.numBoolVars == source.numBoolVars && point.numIntVars == source.numIntVars &&
        point.numRealVars == source.numRealVars
    ) return point
    require(point.numBoolVars >= source.numBoolVars && point.numIntVars >= source.numIntVars &&
        point.numRealVars >= source.numRealVars) { "reconstruction lacks original coordinates" }
    val exact = point.exactInts?.take(source.numIntVars)
    val integers = when {
        exact == null -> LongArray(source.numIntVars) { point.intValue(it) }
        exact.all { it.fitsLong() } -> LongArray(exact.size) { exact[it].toLongExact() }
        else -> longArrayOf()
    }
    return point.copy(bools = BooleanArray(source.numBoolVars) { point.boolValue(it) }, ints = integers,
        reals = DoubleArray(source.numRealVars) { point.approximateRealValue(it) }, exactInts = exact,
        exactReals = point.exactReals?.take(source.numRealVars),
        algebraicReals = point.algebraicReals?.take(source.numRealVars),
        symbolicReals = point.symbolicReals?.take(source.numRealVars))
}
