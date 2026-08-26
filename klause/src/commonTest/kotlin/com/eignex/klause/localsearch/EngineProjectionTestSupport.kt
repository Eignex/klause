package com.eignex.klause.localsearch

import com.eignex.klause.propagation.PropagationProblem
import com.eignex.klause.solver.Problem

internal val Problem.boolOccurrences: Array<IntArray> get() = PropagationProblem(this).boolOccurrences
internal val Problem.invariants: Array<out Invariant> get() = LocalSearchProblem(this).invariants
