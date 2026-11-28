package com.eignex.klause.propagation.difference

import com.eignex.klause.arithmetic.difference.DifferenceEdge
import com.eignex.klause.arithmetic.difference.DifferenceFragment
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.withAppendedFactor
import com.eignex.klause.solver.differenceFragmentOf

// Posted once, after the presolve fixpoint: a factor pins every variable it mentions, so a system posted
// between rounds would block affine elimination.
//
// Edges whose weights cannot be summed safely in Long are omitted; CP still enforces them, so this only
// weakens a redundant propagator.
//
// A system needs a guarded edge, since unconditional rows already propagate exactly through their own
// Linear factors, and an edge between two unfixed root columns, since edges to the zero vertex or a fixed
// column are unary bounds the original factors already propagate.
internal fun BakedProblem.withDifferenceSystem(): BakedProblem {
    val fragment = differenceFragmentOf(factors, numIntVars, intBounds)?.withoutOverHeavyDomainEdges() ?: return this
    if (fragment.edges.none { it.guard != DifferenceEdge.ALWAYS }) return this
    if (fragment.edges.none { edge ->
            !edge.domainBound && edge.source != DifferenceFragment.ZERO && edge.target != DifferenceFragment.ZERO &&
                !rootIntDomain(edge.source).isFixed && !rootIntDomain(edge.target).isFixed
        }) return this
    // Redundant with the rows it reads, so withAppendedFactor can reuse the base fold's root deductions.
    return withAppendedFactor(DifferenceSystem(fragment.edges))
}
