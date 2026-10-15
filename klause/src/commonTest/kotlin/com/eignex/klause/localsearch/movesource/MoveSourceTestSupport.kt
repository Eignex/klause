package com.eignex.klause.localsearch.movesource

import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.propagation.bake
import kotlin.random.Random

internal fun freshState(problem: Problem, seed: Long): LocalSearchState =
    LocalSearchState(problem.bake(), Random(seed)).also { it.recompute() }
