package com.eignex.klause.lp

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.lp.engine.LpCertificationObserver
import com.eignex.klause.lp.engine.LpClaim
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.lp.engine.LpVerdict
import com.eignex.klause.lp.engine.Sense
import com.eignex.klause.lp.engine.solveAndCertify
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.CheckedLongOverflowException

// The columns carry their true ranges, so a verdict refutes the unbounded model, not just a search clamp.
// Interval propagation cannot move a bound when every column in a row is open, so `x + y ≤ 5` with
// `x + y ≥ 10` is refutable only by a Farkas ray here. A float ray is only a candidate, which
// [solveAndCertify] settles in exact rationals.
//
// `false` means "no conclusion". A skipped row only relaxes the system, so a refutation without it holds.
internal fun openLpInfeasible(
    openBounds: Array<OpenIntBounds>,
    constraints: List<Linear>,
    cancellation: ExecutionControl = ExecutionControl.Never,
    observer: LpCertificationObserver? = null,
    context: LpSolveContext = LpSolveContext.Production,
): Boolean {
    if (constraints.isEmpty()) return false
    // Nothing open: the ordinary search already decides the real model.
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
    } catch (_: CheckedLongOverflowException) {
        return false
    }
    if (model.n == 0 || cancellation()) return false
    cancellation.charge(LP_CERTIFY_WORK_WEIGHT * certifyUnits(model))
    return solveAndCertify(
        model,
        cancellation = cancellation,
        observer = observer,
        context = context,
        claim = LpClaim.CONTRADICTION,
    ).let { result ->
        (result.verdict == LpVerdict.INFEASIBLE).also { refuted ->
            if (refuted) LpClaim.CONTRADICTION.consumed(observer)
        }
    }
}
