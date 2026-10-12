package com.eignex.klause.theory.qfnia

import com.eignex.klause.ir.NonlinearIntegerProblem
import com.eignex.klause.theory.qfnra.PolynomialCondition
import com.eignex.klause.theory.qfnra.QfNraParams
import com.eignex.klause.theory.qfnra.QfNraResult
import com.eignex.klause.theory.qfnra.QfNraStats

internal enum class NonlinearIntegerStrategy { SEARCH, LOCAL_SEARCH, ALNS, MIXED }

internal data class NonlinearIntegerParams(
    val arithmetic: QfNraParams = QfNraParams(),
    val maximumCandidates: Long = 1024,
    val strategy: NonlinearIntegerStrategy = NonlinearIntegerStrategy.MIXED,
    val seed: Long = 1,
) {
    init { require(maximumCandidates >= 0) }
}

internal data class NonlinearIntegerStats(
    val candidates: Long,
    val moves: Long,
    val repairs: Long,
    val checks: Long,
    val polynomial: QfNraStats,
)

internal data class NonlinearIntegerExecution(val result: QfNraResult, val stats: NonlinearIntegerStats)

internal fun solveNonlinearIntegers(
    problem: NonlinearIntegerProblem,
    params: NonlinearIntegerParams = NonlinearIntegerParams(),
    condition: PolynomialCondition = PolynomialCondition(),
): NonlinearIntegerExecution = NonlinearIntegerCursor(problem, params, condition).use {
    requireNotNull(it.runSlice())
}
