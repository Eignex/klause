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
): List<SearchDecision>? {
    val selected = linkedSetOf<SearchDecision>()
    for ((id, value) in support.bools) {
        if (opening.bools[id] == value) continue
        selected += assertions.firstOrNull {
            it is SearchDecision.Bool && it.literal == Lit.make(id, value)
        } ?: return null
    }
    val excluded = (conclusion.payload as? SourceBoundPredicate)?.sourceBound ?: return null
    val excludedCoordinate = (excluded.terms.singleOrNull()?.source as? SearchIntValue)?.variable ?: return null
    for ((upper, bounds) in listOf(false to support.lower, true to support.upper)) {
        for ((coordinate, value) in bounds) {
            if (coordinate.kind == SourceCoordinate.Kind.Integer && coordinate.id == excludedCoordinate &&
                upper != excluded.upper) continue
            if (opening.implies(SourceCondition().bound(coordinate, value, upper))) continue
            selected += assertions.lastOrNull { decision ->
                val registered = (decision as? SearchDecision.Theory)?.decision as? RegisteredTheoryDecision
                val atom = (registered?.payload as? SourceBoundPredicate)?.sourceBound
                val term = atom?.terms?.singleOrNull()
                coordinate.kind == SourceCoordinate.Kind.Integer && atom != null && !atom.strict &&
                    term?.source == SearchIntValue(coordinate.id) && term.coefficient == BigFraction.ONE &&
                    atom.upper == upper && if (upper) atom.threshold <= value else atom.threshold >= value
            } ?: return null
        }
    }
    return selected.toList()
}

