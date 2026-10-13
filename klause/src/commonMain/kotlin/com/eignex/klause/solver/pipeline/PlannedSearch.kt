package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.CpSearchComponent
import com.eignex.klause.propagation.PropagationProblem
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.result.SmtStatsSink
import com.eignex.klause.solver.search.CardinalitySearchComponent
import com.eignex.klause.solver.search.ClauseSearchComponent
import com.eignex.klause.solver.search.SearchAtomRegistry
import com.eignex.klause.solver.search.SearchCandidateHints
import com.eignex.klause.solver.search.SearchComponent
import com.eignex.klause.solver.search.SearchComponentSet
import com.eignex.klause.solver.search.SearchLearnedDbParams
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.solver.search.TheoryComponent
import com.eignex.klause.theory.qflra.ExactLiraSearchComponent
import com.eignex.klause.util.Cancellation

/** Components and session built from a [ComponentPlan]. */
class PlannedSearch internal constructor(
    /** Shared search session. */
    val session: SearchSession,
    /** Finite-domain participant, if selected. */
    val cp: CpSearchComponent?,
    /** Theory participant, if selected. */
    val theory: TheoryComponent?,
) : AutoCloseable {
    override fun close() {
        (theory as? AutoCloseable)?.close()
    }
}

/** Builds the selected search components. */
fun ComponentPlan.search(
    spec: Problem,
    cpDomains: Map<Int, IntDomain>,
    maxChecks: Long = Long.MAX_VALUE,
    cancellation: Cancellation = Cancellation.Never,
    learnedDb: SearchLearnedDbParams = SearchLearnedDbParams(),
): PlannedSearch = search(spec, cpDomains, maxChecks, cancellation, learnedDb, null)

internal fun ComponentPlan.search(
    spec: Problem,
    cpDomains: Map<Int, IntDomain>,
    maxChecks: Long,
    cancellation: Cancellation,
    learnedDb: SearchLearnedDbParams,
    smtStats: SmtStatsSink?,
    theorySolveStop: Cancellation? = null,
): PlannedSearch = PreparedSearchComponents(this, spec, cpDomains).open(
    maxChecks, cancellation, learnedDb, smtStats, theorySolveStop,
)

internal class PreparedSearchComponents(
    private val plan: ComponentPlan,
    spec: Problem,
    cpDomains: Map<Int, IntDomain>,
) {
    private val fragment = plan.theoryFragment(spec)
    private val projection = if (plan.hasCpComponent) plan.cpProjection(spec, cpDomains) else null
    private val propagation = projection?.let { PropagationProblem(it.problem) }
    private val boolCount = spec.numBoolVars
    private val clauses = spec.factors.filterIsInstance<Clause>()
    // CP owns its projected cardinalities; a second component would enforce them twice.
    private val cardinalities = spec.factors.indices
        .filter { plan.factorOwner(it) != FactorOwner.CP }
        .mapNotNull { spec.factors[it] as? Cardinality }

    fun open(
        maxChecks: Long = Long.MAX_VALUE,
        cancellation: Cancellation = Cancellation.Never,
        learnedDb: SearchLearnedDbParams = SearchLearnedDbParams(),
        smtStats: SmtStatsSink? = null,
        theorySolveStop: Cancellation? = null,
    ): PlannedSearch {
        val components = ArrayList<SearchComponent>(3)
        val cp = projection?.let {
            CpSearchComponent(PropagationSession(requireNotNull(propagation), cancellation),
                IntArray(it.problem.numIntVars) { local -> it.sourceId(local) }).also(components::add)
        }
        components += ClauseSearchComponent(clauses)
        if (cardinalities.isNotEmpty()) components += CardinalitySearchComponent(cardinalities)
        val theory = plan.openTheoryComponent(fragment, smtStats, theorySolveStop)
        if (theory != null) components += theory
        cp?.rebase()
        return PlannedSearch(
            SearchComponentSet(components).session(maxChecks, cancellation, learnedDb, SearchAtomRegistry(boolCount)),
            cp,
            theory,
        )
    }
}

internal fun PlannedSearch.pointPhaseHints(caller: SearchCandidateHints): SearchCandidateHints =
    if (caller !== SearchCandidateHints.None) caller
    else (theory as? ExactLiraSearchComponent)?.pointPhaseHints() ?: SearchCandidateHints.None
