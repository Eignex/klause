package com.eignex.klause.lp

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.lp.engine.LpOverflowException
import com.eignex.klause.lp.engine.LpVerdict
import com.eignex.klause.lp.engine.Sense
import com.eignex.klause.lp.engine.solveAndCertify
import com.eignex.klause.util.Cancellation

/**
 * Whether the relaxation of [constraints] over the genuinely open [openBounds] is certifiably infeasible.
 *
 * The columns carry their true ranges, so a verdict here refutes the *unbounded* model — unlike a
 * refutation over a search clamp, which only rules out points inside an invented box.
 *
 * This is the refutation that reaches a system no bound ever crosses. Interval propagation cannot move a
 * bound when every column in a row is open, and the divisibility rule next door reads equalities only, so
 * a pair like `x + y ≤ 5` with `x + y ≥ 10` is refutable by a single Farkas ray and by nothing else in
 * this phase.
 *
 * Decided by [solveAndCertify] rather than by reading the simplex's ray directly: a dual-unbounded
 * termination is only a *candidate* infeasibility, and when its float ray does not survive exact Farkas
 * certification that routine still settles the question in exact rationals.
 *
 * Only the refuting direction is usable. Every inconclusive path answers `false` — a row outside the
 * `Long` fragment, an unexpressible relaxation, a feasible point, an indeterminate verdict, a spent
 * budget — and a skipped row only relaxes the system, so an infeasibility proved without it still holds
 * of the whole.
 */
internal fun openLpInfeasible(
    openBounds: Array<OpenIntBounds>,
    constraints: List<Linear>,
    cancellation: Cancellation = Cancellation.Never,
): Boolean {
    if (constraints.isEmpty()) return false
    // Nothing open ⇒ the ordinary search already decides the real model; this has nothing to add.
    if (openBounds.none { it.lo == null || it.hi == null }) return false
    val cb = openColumns(builderOf(), openBounds)
    for (f in constraints) {
        val rel = relationOfLinear(f) ?: continue
        val constants = f.integerConstants ?: continue
        val (cols, vals) = splitTerms(f, cb.posCol, cb.negCol) ?: continue
        cb.builder.addRow(cols, vals, rel, constants.bound)
    }
    val model = try {
        cb.builder.build(Sense.MINIMIZE)
    } catch (_: LpOverflowException) {
        return false
    }
    if (model.n == 0 || cancellation()) return false
    return solveAndCertify(model, cancellation = cancellation).verdict == LpVerdict.INFEASIBLE
}
