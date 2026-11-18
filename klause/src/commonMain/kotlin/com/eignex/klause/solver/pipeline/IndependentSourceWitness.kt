package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.PreparedFactorProjections
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.util.ExecutionControl
import com.eignex.klause.util.ScanMeter
import com.eignex.klause.util.includingStop
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.bigIntOf
import com.eignex.util.numbers.parseBigInt
import kotlin.coroutines.cancellation.CancellationException

internal fun solveIndependentFinite(projections: PreparedFactorProjections, params: TheoryParams): OpenTheoryExecution {
    val stop = params.cancellation.includingStop(params.timeout)
    val result = try {
        if (stop()) return OpenTheoryExecution.Satisfy(
            OpenTheoryResult.Unknown(params.preparationStopReason(stop), SolveStats.EMPTY))
        BacktrackSolver(projections.problem.bake(projections, stop)).solve(BacktrackParams(
            maxDecisions = params.maxDecisions,
            maxInstructions = params.maxInstructions,
            nodeBudget = params.nodeBudget,
            randomSeed = params.randomSeed,
            cancellation = stop,
        ))
    } catch (_: CancellationException) {
        return OpenTheoryExecution.Satisfy(
            OpenTheoryResult.Unknown(params.preparationStopReason(stop), SolveStats.EMPTY))
    }
    return OpenTheoryExecution.Satisfy(when (result) {
        is SolveResult.Sat -> OpenTheoryResult.Sat(OpenTheoryAssignment.Sampled(result.assignment), result.stats)
        is SolveResult.Unsat -> OpenTheoryResult.Unsat(result.stats)
        is SolveResult.Unknown -> OpenTheoryResult.Unknown(result.reason, result.stats)
    })
}

internal fun joinIndependentWitnesses(
    source: Problem,
    points: List<Pair<IndependentSourceProjection, OpenTheoryAssignment>>,
    stop: ExecutionControl,
): OpenTheoryAssignment? {
    require(points.all { it.first.source === source })
    ScanMeter(stop).use { scan ->
        if (!scan.visit(source.numBoolVars.toLong() + source.numIntVars + source.numRealVars)) return null
        val bools = BooleanArray(source.numBoolVars)
        val ints = Array(source.numIntVars) { id ->
            if (!scan.visit(1)) return null
            val bounds = source.intBounds
            if (bounds.hasLower(id) && bounds.hasUpper(id) && bounds.lower(id) > bounds.upper(id)) return null
            bigIntOf(source.intDomainOrNull(id)?.min ?: when {
                bounds.hasLower(id) -> bounds.lower(id)
                bounds.hasUpper(id) -> bounds.upper(id)
                else -> 0L
            })
        }
        val reals = MutableList<SourceRealCoordinate>(source.numRealVars) { id ->
            if (!scan.visit(1)) return null
            if (source.realLower[id] > source.realUpper[id]) return null
            SourceRealCoordinate.Rational(
                BigFraction.ofDouble(source.realLower[id]) ?: BigFraction.ofDouble(source.realUpper[id])
                    ?: BigFraction.ZERO,
            )
        }
        val coveredFactors = BooleanArray(source.numFactors)
        val coveredBools = BooleanArray(source.numBoolVars)
        val coveredInts = BooleanArray(source.numIntVars)
        val coveredReals = BooleanArray(source.numRealVars)
        for ((projection, assignment) in points) {
            for (index in projection.factors) {
                if (!scan.visit(1) || coveredFactors[index]) return null
                coveredFactors[index] = true
            }
            for ((local, id) in projection.bools.withIndex()) {
                if (!scan.visit(1) || coveredBools[id]) return null
                coveredBools[id] = true
                bools[id] = assignment.boolValue(local)
            }
            for ((local, id) in projection.ints.withIndex()) {
                if (!scan.visit(1) || coveredInts[id]) return null
                coveredInts[id] = true
                ints[id] = parseBigInt(assignment.intValue(local))
            }
            for ((local, id) in projection.reals.withIndex()) {
                if (!scan.visit(1) || coveredReals[id]) return null
                coveredReals[id] = true
                reals[id] = assignment.symbolicRealValue(local)?.let { SourceRealCoordinate.Expression(it) }
                    ?: assignment.algebraicRealValue(local)?.let { SourceRealCoordinate.Algebraic(it) }
                    ?: SourceRealCoordinate.Rational(TheoryCompletion.parseRational(assignment.realValue(local)))
            }
        }
        if (!coveredFactors.all { it } || !scan.check()) return null
        return SourceCoordinateWitness(bools, ints, reals)
    }
}
