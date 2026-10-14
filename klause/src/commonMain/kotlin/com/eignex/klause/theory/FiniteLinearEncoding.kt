package com.eignex.klause.theory

import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SourceBoolId
import com.eignex.klause.ir.SourceIntId
import com.eignex.klause.solver.PreparedFactorProjections
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.Cancellation

internal fun PreparedFactorProjections.finiteLinearModel(
    sample: Sample,
    cancellation: Cancellation,
): Problem? = linearTheoryModel(EncodingTarget.LINEAR,
    problem.factors.indices.filter { problem.factors[it].variables.reals.isNotEmpty() },
    (0 until problem.numIntVars).associate { SourceIntId(it) to sample.exactIntValue(it) }, cancellation,
    (0 until problem.numBoolVars).associate { SourceBoolId(it) to sample.boolValue(it) })
