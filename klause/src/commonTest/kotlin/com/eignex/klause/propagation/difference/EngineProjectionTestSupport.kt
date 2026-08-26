package com.eignex.klause.propagation.difference

import com.eignex.klause.propagation.PropagationProblem
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.solver.Problem

internal val Problem.propagators: Array<out Propagator> get() = PropagationProblem(this).propagators
