package com.eignex.klause.lp.relaxation

import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.BakedProblem

internal sealed interface LpPreparationState

internal class LpPreparationNamespace : LpPreparationState

internal class LpPreparationAdmission(
    private val namespace: Any,
    problem: Problem,
    val paidUnits: Long,
    val requiredUnits: Long = minimumWork(problem),
) : LpPreparationState {
    private val semantics = problem.semantics
    private val finite = problem is BakedProblem
    private val integers = problem.numIntVars
    private val booleans = problem.numBoolVars
    private val reals = problem.numRealVars

    fun matches(namespace: Any, problem: Problem): Boolean = this.namespace === namespace &&
        semantics == problem.semantics && finite == (problem is BakedProblem) &&
        integers == problem.numIntVars && booleans == problem.numBoolVars && reals == problem.numRealVars

    fun afterFailure(namespace: Any, problem: Problem): LpPreparationAdmission = LpPreparationAdmission(
        namespace, problem, paidUnits, paidUnits + minOf(paidUnits.coerceAtLeast(1L), Long.MAX_VALUE - paidUnits),
    )

    companion object {
        const val STORAGE_UNITS = 16L

        private fun minimumWork(problem: Problem): Long =
            (problem.numFactors.toLong() + problem.numIntVars + problem.numBoolVars)
                .coerceIn(1L, LpPreparationTemplates.MAX_UNITS)
    }
}
