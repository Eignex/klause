package com.eignex.klause.solver.pipeline

/**
 * Source-bound exact satisfaction execution consumed by the shared strategy dispatcher.
 * Implementations own original-source witness checking and cumulative invocation resources.
 * Heuristic engines have witness authority only; complete engines may publish completed source refutations.
 */
fun interface ExactTheorySatisfactionRequest {
    /** Execute the same source snapshot under the selected engine and invocation controls. */
    fun execute(params: PolynomialTheoryParams, engine: FiniteEngine, cores: Int): PolynomialTheoryExecution
}
