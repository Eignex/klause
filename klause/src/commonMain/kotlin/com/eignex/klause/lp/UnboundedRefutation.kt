package com.eignex.klause.lp

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.solver.Cancellation

/**
 * Whether the constraint system is unsatisfiable **over its genuinely unbounded domains** — a refutation
 * that owes nothing to the finite box the search is given.
 *
 * A model with an open integer side is searched inside an invented clamp, so exhausting that box proves
 * nothing about the model itself and the verdict has to soften to `unknown`. This asks the same question
 * without a box: the relaxation is built over the true, open ranges, and a Farkas certificate there
 * refutes the unbounded model outright.
 *
 * Deliberately NOT the double-bounded reduction ([boundedRowMask]). Discarding the unbounded rows only
 * relaxes the system, so refuting what is left is strictly harder than refuting the whole — the reduction
 * earns its keep by making a *search* terminate, not by making a refutation easier. It also cannot even be
 * run here: on an infeasible system the boundedness probes find no feasible point, so every row would
 * classify as unbounded.
 *
 * Only the refuting direction is usable: `false` means "no conclusion", never "satisfiable". Every
 * inconclusive path — an unexpressible relaxation, a solve that finds a point, a termination with no ray,
 * a ray the exact certification declines, a spent budget — answers `false`, and the caller keeps the
 * clamped search it would have run anyway.
 */
internal fun unboundedlyInfeasible(
    openBounds: Array<OpenIntBounds>,
    constraints: List<Linear>,
    cancellation: Cancellation = Cancellation.Never,
): Boolean {
    if (constraints.isEmpty()) return false
    // Nothing is open ⇒ the ordinary search is already deciding the real model; no need for this at all.
    if (openBounds.none { it.lo == null || it.hi == null }) return false
    // Exact interval propagation first: it is cheap, and it reaches the systems whose forced values are
    // too large for the relaxation to even build.
    if (exactBoundsInfeasible(openBounds, constraints, cancellation)) return true
    return rootLpInfeasibleOverOpenDomains(openBounds, constraints, cancellation)
}

/**
 * Whether the relaxation of [constraints] over the genuinely open [openBounds] is certifiably infeasible.
 * The columns carry their true ranges, so a verdict here refutes the unbounded model — unlike a
 * refutation over the search clamp, which only rules out points inside an invented box.
 *
 * Decided by [solveAndCertify] rather than by reading the simplex's ray directly: a dual-unbounded
 * termination is only a *candidate* infeasibility, and when its float ray does not survive exact Farkas
 * certification that routine still settles the question exactly in rationals. Reading the ray alone gives
 * up at that point, which is silent on every model whose terminating basis is not the certifying one.
 *
 * Every inconclusive path answers `false`: an unexpressible relaxation, a feasible point, or an
 * indeterminate verdict.
 */
private fun rootLpInfeasibleOverOpenDomains(
    openBounds: Array<OpenIntBounds>,
    constraints: List<Linear>,
    cancellation: Cancellation,
): Boolean {
    val cb = openColumns(builderOf(), openBounds)
    for (f in constraints) {
        val rel = relationOfLinear(f) ?: continue
        val (cols, vals) = splitTerms(f, cb.posCol, cb.negCol)
        cb.builder.addRow(cols, vals, rel, f.bound)
    }
    val model = try {
        cb.builder.build(Sense.MINIMIZE)
    } catch (_: LpOverflowException) {
        return false
    }
    if (model.n == 0 || cancellation()) return false
    return solveAndCertify(model, cancellation = cancellation).verdict == LpVerdict.INFEASIBLE
}
