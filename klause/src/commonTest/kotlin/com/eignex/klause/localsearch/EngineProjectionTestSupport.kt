package com.eignex.klause.localsearch

import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.PropagationProblem

internal val Problem.boolOccurrences: Array<IntArray> get() = PropagationProblem(this).boolOccurrences
internal val Problem.invariants: Array<out Invariant> get() = LocalSearchProblem(this).invariants
