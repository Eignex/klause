package com.eignex.klause.propagation.difference

import com.eignex.klause.arithmetic.difference.DifferenceEdge
import com.eignex.klause.arithmetic.difference.differenceFragmentOf
import com.eignex.klause.solver.BakedProblem
import com.eignex.klause.solver.Problem

/**
 * This [Problem] with a [DifferenceSystem] over its difference rows appended, or the problem unchanged
 * when it carries none the joint propagator could act on.
 *
 * Posted exactly once, after the presolve fixpoint — never as a round pass. A factor pins every variable
 * it mentions into the model, so a system posted between rounds keeps a variable alive that affine
 * elimination would otherwise substitute away, trading a whole class of reductions for the deduction.
 * After the fixpoint no pass can be blocked, and the search sees the same system either way.
 *
 * The second gate is arithmetic. A system whose weights cannot be summed once per vertex inside `Long`
 * holds no potential, so its propagator returns at its first line on every fire — 320,000 times on one
 * `nec-smt` instance, for nothing. Declining to post it was worth ~1.2x there.
 *
 * The family that motivated the gate is gone: open columns once reached CP as an invented ±2^62 clamp,
 * and that clamp no longer exists — an open column is owned by a theory and never materialized as a CP
 * range. What still trips the gate is a model that genuinely declares bounds that large, which is a
 * smaller and more legitimate set than the one measured.
 *
 * The gate is arithmetic, not a cost cap: the graph could not hold a potential, so the factor could not
 * deduce whatever it was scheduled to do. What is *not* settled is whether those models should be carried
 * at all. Dropping only the over-heavy edges does make the system usable, and that was measured a loss:
 * 5-10x fewer nodes in a fixed budget, deciding nothing extra over a 10-instance `nec-smt` sample.
 *
 * That measurement was taken when the refutation sweep ran inside the CP fixpoint and searched buckets of
 * open edges on every fire, and the sweep was the whole cost — the same instrumentation put 320,000 fires
 * on one instance, with the sweeps switched off costing only ~1.2x. The sweep has since moved to at most
 * one per decision, so the measurement no longer applies and dropping the over-heavy edges is worth
 * re-testing. Re-running it needs the `nec-smt` sample, which is not in the fetched corpora; the
 * fixed-node comparison itself is `--param node-limit=N`. Lift this gate on that evidence rather than on
 * a guess.
 *
 * The first gate is a *guarded* edge — a reified difference row. Unconditional difference rows already
 * propagate exactly through their own [com.eignex.klause.factor.arithmetic.Linear] factors, so a system
 * built only from those repeats work the model already does; it is the reified ones, whose truth the
 * Boolean layer decides, that the joint graph can refute ahead of a decision.
 */
internal fun Problem.withDifferenceSystem(): Problem {
    val fragment = differenceFragmentOf(factors, numIntVars, intBounds) ?: return this
    if (fragment.edges.none { it.guard != DifferenceEdge.ALWAYS }) return this
    if (!fragment.carriesAPotential()) return this
    // The system is redundant with the rows it reads, so it changes nothing the base fold derived:
    // `alreadyFolded` reuses that fold and `seedDeductions` carries the proven deductions forward.
    return BakedProblem(
        numBoolVars = numBoolVars,
        numIntVars = numIntVars,
        intDomains = requireFiniteIntDomains(),
        factors = factors + DifferenceSystem(fragment.edges),
        seedDeductions = baked,
        cancellation = cancellation,
        impliedFactorMask = impliedFactorMask?.let { it + false },
        hasSymmetryBreaking = hasSymmetryBreaking,
        alreadyFolded = true,
    )
}
