package com.eignex.klause.solver.integration

import com.eignex.klause.propagation.PropagationProblem
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.solver.Problem

internal val Problem.propagators: Array<out Propagator>
    get() = PropagationProblem(this).propagators

internal val Problem.boolOccurrences: Array<IntArray>
    get() = PropagationProblem(this).boolOccurrences

internal val Problem.intOccurrences: Array<IntArray>
    get() = PropagationProblem(this).intOccurrences

internal val Problem.nonIntEventWatcherIntOccurrences: Array<IntArray>
    get() = PropagationProblem(this).nonIntEventWatcherIntOccurrences
