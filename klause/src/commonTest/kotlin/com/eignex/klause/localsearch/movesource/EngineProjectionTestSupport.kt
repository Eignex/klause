package com.eignex.klause.localsearch.movesource

import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.PropagationProblem

internal val Problem.boolOccurrences: Array<IntArray> get() = PropagationProblem(this).boolOccurrences
internal val Problem.intOccurrences: Array<IntArray> get() = PropagationProblem(this).intOccurrences
