package com.eignex.klause.solver.search

import com.eignex.klause.ir.Problem

internal class FiniteFactorEnforcement(
    private val source: Problem,
    factors: Set<Int>,
) : FiniteFactorEnforcer {
    private val factors = factors.toSet()

    init { require(factors.all { it in source.factors.indices }) }

    override fun enforcingFiniteFactors(problem: Problem): Set<Int> =
        if (problem.numBoolVars == source.numBoolVars && problem.numIntVars == source.numIntVars &&
            problem.numFactors >= source.numFactors &&
            source.factors.indices.all { problem.factors[it] === source.factors[it] }
        ) factors else emptySet()
}
