package com.eignex.klause.portfolio

import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.validation.verifyRealCoordinates as validateRealCoordinates

internal fun verifyRealCoordinates(model: Problem, sample: Sample): Verification<Sample, Unit> =
    validateRealCoordinates(model, sample)
