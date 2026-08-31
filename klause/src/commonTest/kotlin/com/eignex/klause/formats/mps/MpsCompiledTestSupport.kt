package com.eignex.klause.formats.mps

import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.bakeFiniteBounds

/** Explicit finite materialization for legacy assertions over fully bounded MPS fixtures. */
internal val MpsCompiled.problem: Problem
    get() = model.bakeFiniteBounds()
