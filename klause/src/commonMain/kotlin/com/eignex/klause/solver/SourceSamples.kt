package com.eignex.klause.solver

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.solver.pipeline.OpenTheoryAssignment
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import com.eignex.klause.solver.pipeline.TranscendentalAssignment
import com.eignex.klause.solver.pipeline.algebraicRealValue
import com.eignex.klause.solver.pipeline.toSample
import com.eignex.klause.util.fitsLong
import com.eignex.klause.util.parseBigInt
import com.eignex.klause.util.toLongExact

/** Solve a native source and retain its typed exact witness in the ordinary [Sample] result surface. */
fun SourceStrategy.solveSample(params: BacktrackParams = BacktrackParams()): SolveResult = when (val result = solve(params)) {
    is OpenTheoryResult.Sat -> {
        val assignment = result.assignment
        val sample = when (assignment) {
            is OpenTheoryAssignment.Sampled -> assignment.sample
            is OpenTheoryAssignment.Difference -> assignment.sample
            is TranscendentalAssignment -> assignment.sample
            else -> {
                val algebraic = List(source.numRealVars) { assignment.algebraicRealValue(it) }
                if (algebraic.isNotEmpty() && algebraic.all { it != null }) {
                    val integers = List(source.numIntVars) { parseBigInt(assignment.intValue(it)) }
                    val reals = algebraic.filterNotNull()
                    Sample(BooleanArray(source.numBoolVars) { assignment.boolValue(it) },
                        if (integers.all { it.fitsLong() }) LongArray(integers.size) { integers[it].toLongExact() }
                        else longArrayOf(),
                        DoubleArray(reals.size) { id -> reals[id].rational?.toDouble()
                            ?: (reals[id].lowerBound.toDouble() / 2 + reals[id].upperBound.toDouble() / 2) },
                        exactInts = integers, algebraicReals = reals)
                } else assignment.toSample(source)
            }
        }
        SolveResult.Sat(sample, result.stats)
    }
    is OpenTheoryResult.Unsat -> SolveResult.Unsat(result.stats)
    is OpenTheoryResult.Unknown -> SolveResult.Unknown(result.reason, result.stats)
}
