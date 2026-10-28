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
    val reuseSourceCells: Boolean = false,
    val steerSourceCells: Boolean = false,
    val admitIntegerRoots: Boolean = false,
    val residueRefutation: Boolean = false,
) {
    init {
        require(maximumCandidates >= 0)
        require(!steerSourceCells || reuseSourceCells) { "integer cell steering requires cell reuse" }
    }
}

internal data class NonlinearIntegerStats(
    val candidates: Long,
    val moves: Long,
    val repairs: Long,
    val checks: Long,
    val polynomial: QfNraStats,
    val cells: NonlinearIntegerCellStats = NonlinearIntegerCellStats(),
    val roots: NonlinearIntegerRootCounts = NonlinearIntegerRootCounts(),
    val residues: NonlinearIntegerResidueCounts = NonlinearIntegerResidueCounts(),
)

internal data class NonlinearIntegerExecution(val result: QfNraResult, val stats: NonlinearIntegerStats)

internal fun solveNonlinearIntegers(
    problem: NonlinearIntegerProblem,
    params: NonlinearIntegerParams = NonlinearIntegerParams(),
    condition: PolynomialCondition = PolynomialCondition(),
    roots: NonlinearIntegerRootStore? = null,
): NonlinearIntegerExecution = NonlinearIntegerCursor(problem, params, condition, roots).use {
    requireNotNull(it.runSlice())
}
