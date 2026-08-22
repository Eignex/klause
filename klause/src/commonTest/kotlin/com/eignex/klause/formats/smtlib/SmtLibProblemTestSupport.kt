package com.eignex.klause.formats.smtlib

import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.Problem

/** Explicit finite materialization for assertions that exercise the CP backend. */
internal val SmtLibProblem.problem: Problem
    get() = deferredBounds?.run(Cancellation.Never)?.let { model.materialize(it.domains) }
        ?: model.materializeFiniteBounds()
