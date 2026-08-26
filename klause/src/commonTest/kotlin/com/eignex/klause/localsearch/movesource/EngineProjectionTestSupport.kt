package com.eignex.klause.localsearch.movesource

import com.eignex.klause.propagation.PropagationProblem
import com.eignex.klause.solver.Problem

internal val Problem.boolOccurrences: Array<IntArray> get() = PropagationProblem(this).boolOccurrences
internal val Problem.intOccurrences: Array<IntArray> get() = PropagationProblem(this).intOccurrences
