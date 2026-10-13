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
import com.eignex.klause.solver.search.SearchLearnedDbParams
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.solver.search.TheoryComponent
import com.eignex.klause.theory.qflra.PreparedExactLiraComponentFactory
import com.eignex.klause.theory.qflra.prepareExactLiraSource
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter

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
    private var exactSource: PreparedExactLiraComponentFactory? = null
    var preparationWork: Long = 0
        private set
    var sourcePrepared: Boolean = false
        private set

    private val clauses = spec.factors.filterIsInstance<Clause>()
    // CP owns its projected cardinalities; a second component would enforce them twice.
    private val cardinalities = spec.factors.indices
        .filter { plan.factorOwner(it) != FactorOwner.CP }
        .mapNotNull { spec.factors[it] as? Cardinality }

    private val selectedRegistrations = run {
        val descriptors = spec.preparedWithComponents(plan).components
        val selected = ArrayList<PreparedSearchRegistration>(3)
        if (projection != null) {
            val descriptor = descriptors.first { it.responsibility == ComponentResponsibility.FINITE }
            selected += PreparedSearchRegistration(spec, descriptor) { opening ->
                val cp = CpSearchComponent(
                    PropagationSession(requireNotNull(propagation), opening.cancellation),
                    IntArray(projection.problem.numIntVars) { local -> projection.sourceId(local) },
                )
                cp.rebase()
                listOf(cp)
            }
        }
        val boolean = descriptors.firstOrNull { it.responsibility == ComponentResponsibility.BOOLEAN }
            ?: ExecutionComponentDescriptor("boolean", ComponentResponsibility.BOOLEAN, emptyList(),
                emptyList(), emptyList())
        selected += PreparedSearchRegistration(spec, boolean) {
            listOf(ClauseSearchComponent(clauses)) + if (cardinalities.isEmpty()) emptyList()
            else listOf(CardinalitySearchComponent(cardinalities))
        }
        plan.theoryComponentFactory(fragment) { exactSource }?.let { factory ->
            val descriptor = descriptors.firstOrNull { it.responsibility == ComponentResponsibility.COMPLETION }
                ?: ExecutionComponentDescriptor("arithmetic", ComponentResponsibility.COMPLETION,
                    emptyList(), emptyList(), emptyList())
            selected += PreparedSearchRegistration(spec, descriptor, factory)
        }
        selected.toList()
    }
    private val opening = PreparedSearchRegistrations(spec, selectedRegistrations)

    val registrations: List<PreparedSearchRegistration> get() = selectedRegistrations.toList()

    fun prepareSource(stop: Cancellation): Boolean {
        if (sourcePrepared) return true
        if (plan.theoryPipeline == ProblemPipeline.EXACT_LRA || plan.theoryPipeline == ProblemPipeline.EXACT_LIRA) {
            val meter = WorkMeter { units ->
                preparationWork += units
                stop.charge(units)
            }
            val preparationStop = object : Cancellation {
                override fun isCancelled(): Boolean = stop()
                override fun deadline() = stop.deadline()
                override fun workMeter(): WorkMeter = meter
            }
            exactSource = prepareExactLiraSource(fragment, preparationStop) ?: return false
        }
        sourcePrepared = true
        return true
    }

    fun open(
        maxChecks: Long = Long.MAX_VALUE,
        cancellation: Cancellation = Cancellation.Never,
        learnedDb: SearchLearnedDbParams = SearchLearnedDbParams(),
        smtStats: SmtStatsSink? = null,
        theorySolveStop: Cancellation? = null,
    ): PlannedSearch = opening.open(maxChecks, cancellation, learnedDb, smtStats, theorySolveStop)
}
