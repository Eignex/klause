package com.eignex.klause.solver.search

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.solver.Lit

/**
 * Boolean clauses hosted by the shared session rather than by a frontend-specific search loop.
 *
 * The component is intentionally small: CP's watched-clause implementation remains its specialised
 * finite-domain mechanism, while open and hybrid component sets can use this representation before a
 * SAT component is installed. Its clause is both the propagation and conflict explanation.
 */
class ClauseSearchComponent(clauses: Iterable<Clause>) : SearchComponent {
    private val clauses = clauses.map(Clause::literals)

    override fun propagate(context: SearchContext): ComponentResult {
        for (clause in clauses) {
            var unit = -1
            var unresolved = 0
            var satisfied = false
            for (literal in clause) {
                when (context.boolValue(Lit.variable(literal))) {
                    if (Lit.isPositive(literal)) true else false -> {
                        satisfied = true
                        break
                    }

                    null -> {
                        unresolved++
                        unit = literal
                    }

                    else -> Unit
                }
            }
            if (satisfied) continue
            val explanation = SearchExplanation(clause.copyOf())
            if (unresolved == 0) return ComponentResult.Conflict(explanation)
            if (unresolved == 1) {
                val result = context.imply(unit, explanation)
                if (result !is ComponentResult.Consistent) return result
            }
        }
        return ComponentResult.Consistent
    }
}
