package com.eignex.klause.propagation.difference

import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.PropagationProblem
import com.eignex.klause.propagation.Propagator

internal val Problem.propagators: Array<out Propagator> get() = PropagationProblem(this).propagators
