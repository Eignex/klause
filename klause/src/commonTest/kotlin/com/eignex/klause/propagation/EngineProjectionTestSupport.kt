package com.eignex.klause.propagation

import com.eignex.klause.solver.Problem

internal val Problem.clauseArena: ClauseArena get() = PropagationProblem(this).clauseArena
