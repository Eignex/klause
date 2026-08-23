package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.propagation.CpSearchComponent
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.ComponentPlan
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.ProblemSpec
import com.eignex.klause.solver.search.ClauseSearchComponent
import com.eignex.klause.solver.search.SearchComponent
import com.eignex.klause.solver.search.SearchComponentSet
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.solver.search.TheoryComponent
import com.eignex.klause.theory.theoryComponent

/** Components and single session built from one immutable [ComponentPlan]. */
class PlannedSearch internal constructor(
    /** The session that owns every decision level and component lifecycle. */
    val session: SearchSession,
    /** Finite-domain participant, when the plan selected one. */
    val cp: CpSearchComponent?,
    /** Theory participant, when the plan selected one. */
    val theory: TheoryComponent?,
)

/** Build the selected CP, shared-clause, and theory components exactly once. */
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
