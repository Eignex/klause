package com.eignex.klause.cli

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult

/**
 * The part of [problem] that no invented bound reaches: every factor whose integer variables all carry
 * bounds the model itself stated or the bounding derived, dropping every factor that touches a side the
 * search box invented (`Problem.openIntLo` / `openIntHi`).
 *
 * The result is a subset of the model's constraints over sound bounds, so it is a relaxation of the
 * unbounded model: refuting it refutes the model outright, with none of the clamp's caveat. Null when
 * there is nothing to gain — no side was invented, or every factor reaches one.
 */
internal fun boundExactResidual(problem: Problem): Problem? {
    val openLo = problem.openIntLo
    val openHi = problem.openIntHi
    if (openLo == null && openHi == null) return null
    val open = BooleanArray(problem.numIntVars) { openLo?.get(it) == true || openHi?.get(it) == true }
    if (open.none { it }) return null
    val kept = problem.factors.filter { f -> f.intVars.none { open[it] } }
    if (kept.isEmpty()) return null
    // Only the *integer* namespace can be clamped: booleans are two-valued and a real column carries the
    // bounds the model declared (an undeclared one is open, which the simplex reasons over exactly), so
    // neither can smuggle the box into the residual.
    //
    // Every domain is carried over as it stands, including the clamped ones. Narrowing the variables the
    // retained factors no longer mention would be tempting (their domains are as wide as the box) but it
    // rests on every factor reporting its whole integer scope, and a refutation must not rest on that:
    // dropping a factor can only ever make the residual easier to satisfy, whereas narrowing a domain a
    // factor turns out to constrain would refute the model on the strength of the narrowing.
    return Problem(
        numBoolVars = problem.numBoolVars,
        numIntVars = problem.numIntVars,
        intDomains = problem.intDomains,
        factors = kept,
        numRealVars = problem.numRealVars,
        realLower = problem.realLower,
        realUpper = problem.realUpper,
    )
}

/**
 * Whether a refutation of [problem] holds without the invented box — re-derived over
 * [boundExactResidual], where no bound the box invented is available to lean on.
 *
 * False means "no conclusion", never "the model is satisfiable": the residual drops every factor that
 * touches a clamped side, so a model whose refutation genuinely needs one answers false, and so does one
 * whose residual [cancellation] cuts short. The caller then reports the clamped verdict it would have
 * reported anyway.
 */
internal fun refutationIsBoxFree(problem: Problem, cancellation: Cancellation): Boolean {
    val residual = boundExactResidual(problem) ?: return false
    // Nothing at all constrains a clamped side, so the clamped variables are free and the model's
    // satisfiability never depended on their domains — no search needed to say so.
    if (residual.numFactors == problem.numFactors) return true
    if (cancellation()) return false
    // `keepRecurringPremises` is what makes the refutation trustworthy: the default analysis drops a
    // resolved Boolean premise that recurs, which can learn a nogood stronger than what was derived and
    // refute a satisfiable model (#1540). The flag is off in general search because the nogoods it keeps
    // never assert; here a refutation the weaker nogoods do not reach costs only this certificate, and the
    // caller reports the clamped verdict it would have reported anyway.
    val params = BacktrackParams(cancellation = cancellation, keepRecurringPremises = true)
    val result = BacktrackSolver(residual.bake(cancellation)).solve(params)
    return result is SolveResult.Unsat
}
