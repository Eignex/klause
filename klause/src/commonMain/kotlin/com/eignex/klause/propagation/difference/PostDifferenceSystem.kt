package com.eignex.klause.propagation.difference

import com.eignex.klause.arithmetic.difference.DifferenceEdge
import com.eignex.klause.propagation.withAppendedFactor
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.differenceFragmentOf

/**
 * This [Problem] with a [DifferenceSystem] over its difference rows appended, or the problem unchanged
 * when it carries none the joint propagator could act on.
 *
 * Posted exactly once, after the presolve fixpoint — never as a round pass. A factor pins every variable
 * it mentions into the model, so a system posted between rounds keeps a variable alive that affine
 * elimination would otherwise substitute away, trading a whole class of reductions for the deduction.
 * After the fixpoint no pass can be blocked, and the search sees the same system either way.
 *
 * The graph holds only weights it can sum safely in `Long`. Declared-range edges outside that range stay
 * enforced by CP but are omitted here, which weakens this redundant propagator without weakening the
 * model. This makes guarded refutations available on a large finite domain without reviving the invented
 * open-model clamp that used to produce those ranges.
 *
 * The first gate is a *guarded* edge — a reified difference row. Unconditional difference rows already
 * propagate exactly through their own [com.eignex.klause.factor.arithmetic.Linear] factors, so a system
 * built only from those repeats work the model already does; it is the reified ones, whose truth the
 * Boolean layer decides, that the joint graph can refute ahead of a decision.
 */
internal fun Problem.withDifferenceSystem(): Problem {
    val fragment = differenceFragmentOf(factors, numIntVars, intBounds)?.withoutOverHeavyDomainEdges() ?: return this
    if (fragment.edges.none { it.guard != DifferenceEdge.ALWAYS }) return this
    // The system is redundant with the rows it reads, so it changes nothing the base fold derived:
    // `alreadyFolded` reuses that fold and `seedDeductions` carries the proven deductions forward.
    return withAppendedFactor(DifferenceSystem(fragment.edges))
}
