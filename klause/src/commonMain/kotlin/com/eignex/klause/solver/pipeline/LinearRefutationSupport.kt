package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.Lit
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceCondition
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.search.RegisteredTheoryDecision
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchIntValue
import com.eignex.klause.solver.search.SourceBoundPredicate

internal fun linearRefutationSupport(
    opening: SourceCondition,
    assertions: List<SearchDecision>, support: SourceCondition, conclusion: RegisteredTheoryDecision,
    visit: () -> Boolean = { true },
): List<SearchDecision>? {
    if (!visit()) return null
    val selected = linkedSetOf<SearchDecision>()
    val bools = opening.bools
    val lower = opening.lower
    val upper = opening.upper
    for ((id, value) in support.bools) {
        if (!visit()) return null
        if (bools[id] == value) continue
        var found: SearchDecision? = null
        for (decision in assertions) {
            if (!visit()) return null
            if (decision is SearchDecision.Bool && decision.literal == Lit.make(id, value)) {
                found = decision
                break
            }
        }
        selected += found ?: return null
    }
    val excluded = (conclusion.payload as? SourceBoundPredicate)?.sourceBound ?: return null
    val excludedCoordinate = (excluded.terms.singleOrNull()?.source as? SearchIntValue)?.variable ?: return null
    for ((isUpper, bounds) in listOf(false to support.lower, true to support.upper)) {
        for ((coordinate, value) in bounds) {
            if (!visit()) return null
            if (coordinate.kind == SourceCoordinate.Kind.Integer && coordinate.id == excludedCoordinate &&
                isUpper != excluded.upper) continue
            val rootBound = (if (isUpper) upper else lower)[coordinate]
            if (rootBound != null && if (isUpper) rootBound <= value else rootBound >= value) continue
            var found: SearchDecision? = null
            for (index in assertions.indices.reversed()) {
                if (!visit()) return null
                val decision = assertions[index]
                val registered = (decision as? SearchDecision.Theory)?.decision as? RegisteredTheoryDecision
                val atom = (registered?.payload as? SourceBoundPredicate)?.sourceBound
                val term = atom?.terms?.singleOrNull()
                if (coordinate.kind == SourceCoordinate.Kind.Integer && atom != null && !atom.strict &&
                    term?.source == SearchIntValue(coordinate.id) && term.coefficient == BigFraction.ONE &&
                    atom.upper == isUpper && if (isUpper) atom.threshold <= value else atom.threshold >= value
                ) {
                    found = decision
                    break
                }
            }
            selected += found ?: return null
        }
    }
    return selected.toList()
}
