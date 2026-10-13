package com.eignex.klause.solver.evidence

import com.eignex.klause.ir.NonlinearIntegerProblem
import com.eignex.klause.ir.PolynomialRealProblem
import com.eignex.klause.ir.Problem

internal sealed interface QuadraticEngineSource {
    data class ExactIr(val problem: Problem) : QuadraticEngineSource
    data class Polynomial(val problem: PolynomialRealProblem) : QuadraticEngineSource
    data class NonlinearInteger(val problem: NonlinearIntegerProblem) : QuadraticEngineSource
}
