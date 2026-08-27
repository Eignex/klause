package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.propagation.CpSearchComponent
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.solver.ProblemSpec
import com.eignex.klause.solver.search.ClauseSearchComponent
import com.eignex.klause.solver.search.SearchComponent
import com.eignex.klause.solver.search.SearchComponentSet
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.solver.search.TheoryComponent
import com.eignex.klause.theory.theoryComponent
import com.eignex.klause.util.Cancellation

/** Components and session built from a [ComponentPlan]. */
class PlannedSearch internal constructor(
    /** Shared search session. */
    val session: SearchSession,
    /** Finite-domain participant, if selected. */
    val cp: CpSearchComponent?,
    /** Theory participant, if selected. */
    val theory: TheoryComponent?,
)

/** Builds the selected search components. */
fun ComponentPlan.search(
    spec: ProblemSpec,
    cpDomains: Map<Int, IntDomain>,
    maxChecks: Long = Long.MAX_VALUE,
    cancellation: Cancellation = Cancellation.Never,
): PlannedSearch {
    val components = ArrayList<SearchComponent>(3)
    val cp = if (hasCpComponent) {
        val projection = cpProjection(spec, cpDomains)
        CpSearchComponent(
            PropagationSession(projection.problem.bake(), cancellation),
            IntArray(projection.problem.numIntVars) { projection.sourceId(it) },
        ).also(components::add)
    } else {
        null
    }
    components += ClauseSearchComponent(spec.factors.filterIsInstance<Clause>())
    val theory = theoryComponent(spec)
    if (theory != null) components += theory
    cp?.rebase()
    return PlannedSearch(SearchComponentSet(components).session(maxChecks, cancellation), cp, theory)
}
