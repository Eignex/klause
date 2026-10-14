package com.eignex.klause.solver.search

import com.eignex.klause.ir.Problem

internal interface FiniteFactorEnforcer {
    fun enforcingFiniteFactors(problem: Problem): Set<Int>
}
