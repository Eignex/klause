package com.eignex.klause.factor.table

import com.eignex.klause.propagation.PropagationProblem
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.solver.Problem

internal val Problem.propagators: Array<out Propagator> get() = PropagationProblem(this).propagators
