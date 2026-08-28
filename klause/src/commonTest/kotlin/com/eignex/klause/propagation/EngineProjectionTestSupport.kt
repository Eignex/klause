package com.eignex.klause.propagation

import com.eignex.klause.ir.Problem

internal val Problem.clauseArena: ClauseArena get() = PropagationProblem(this).clauseArena
