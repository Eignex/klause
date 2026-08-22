package com.eignex.klause.formats.mps

import com.eignex.klause.solver.Problem

/** Explicit finite materialization for legacy assertions over fully bounded MPS fixtures. */
internal val MpsCompiled.problem: Problem
    get() = model.materializeFiniteBounds()
