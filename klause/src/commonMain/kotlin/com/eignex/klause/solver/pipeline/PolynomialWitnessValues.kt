package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.selector.ValueSelector
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.search.SearchCandidateHints
import com.eignex.klause.solver.search.VarRef
import kotlin.random.Random

internal class PolynomialWitnessValues(
    private val delegate: ValueSelector,
    private val hints: SearchCandidateHints,
) : ValueSelector by delegate {
    override fun fresh(): ValueSelector = PolynomialWitnessValues(delegate.fresh(), hints)

    override fun values(session: PropagationSession, varRef: VarRef, rng: Random): Sequence<Long> {
        val values = delegate.values(session, varRef, rng)
        if (varRef !is VarRef.Bool) return values
        val alternatives = values.toList()
        if (alternatives.size != 2 || alternatives.toSet() != setOf(0L, 1L)) return alternatives.asSequence()
        val preferred = hints.preferredBool(varRef.varId) ?: return alternatives.asSequence()
        return if (alternatives[0] == if (preferred) 1L else 0L) alternatives.asSequence()
        else alternatives.asReversed().asSequence()
    }
}
