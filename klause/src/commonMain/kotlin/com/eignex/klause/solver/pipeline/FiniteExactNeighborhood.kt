package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.baked
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.CompletedEnginePublication
import com.eignex.klause.solver.incumbent.EvidenceCertificate
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.theory.finiteLinearModel
import com.eignex.klause.theory.rebindEncoding
import com.eignex.klause.util.Cancellation

internal fun BakedProblem.fixedDiscreteSample(): Sample? {
    val root = baked as? PropagationResult.Implied ?: return null
    if (rootIntDomainsInPlace.any { it.min != it.max } ||
        (0 until numBoolVars).any { root.boolValueOrNull(it) == null }
    ) return null
    return Sample(BooleanArray(numBoolVars) { requireNotNull(root.boolValueOrNull(it)) },
        LongArray(numIntVars) { rootIntDomainsInPlace[it].min })
}

internal class FiniteExactNeighborhood(
    private val source: BakedProblem,
    private val fixed: Sample,
    private val params: TheoryParams,
    private val objective: LinearObjective?,
    private val certify: (Sample) -> Unit,
) : ExactNeighborhoodCompletion, AutoCloseable {
    private val geometry = OpenExactNeighborhood(source.withIntDomains(source.rootIntDomainsInPlace), params, objective)
    private var adapter: OpenExactNeighborhood? = null
    val exhausted: Boolean get() = adapter?.exhausted == true

    private fun prepare(cancellation: Cancellation): OpenExactNeighborhood? {
        adapter?.let { return it }
        val stop = params.cancellation or params.timeout or cancellation
        if (stop()) return null
        val selected = source.factorProjections ?: FactorProjectionProviders.Default.prepare(source)
        val encoded = selected.finiteLinearModel(fixed, stop) ?: return null
        val narrowed = source.withIntDomains(source.rootIntDomainsInPlace)
        val pins = Array(source.numBoolVars) { id -> Clause(intArrayOf(Lit.make(id, fixed.boolValue(id)))) }
        val canonical = narrowed.withFactors(narrowed.factors + pins)
        val projections = selected.forEngineView(narrowed).withAppendedFactors(canonical)
        val owners = Array(canonical.numFactors) { index ->
            when {
                canonical.factors[index].variables.reals.isNotEmpty() -> FactorOwner.THEORY
                canonical.factors[index] is Clause && projections.usesBuiltInPropagation(index) -> FactorOwner.SHARED
                else -> FactorOwner.CP
            }
        }
        val fragment = encoded.rebindEncoding(canonical, encoded.factors + pins, null)
        val plan = ComponentPlan(canonical, Array(canonical.numIntVars) { IntVariableOwner.CP }, owners,
            if (canonical.numIntVars == 0) ProblemPipeline.EXACT_LRA else ProblemPipeline.EXACT_LIRA,
            factorProjections = projections, installedFragment = fragment)
        if (stop()) return null
        return OpenExactNeighborhood(canonical, params, objective,
            preparedSource = canonical.preparedWithComponents(plan, objective = objective)).also { adapter = it }
    }

    override fun check(
        condition: ExactNeighborhoodCondition,
        cancellation: Cancellation,
    ): ExactNeighborhoodResult {
        val result = prepare(cancellation)?.check(condition, cancellation)
            ?: return ExactNeighborhoodResult.Undecided
        if (result !is ExactNeighborhoodResult.Witness) return result
        return when (verifyOpenWitness(source, result.sample, cancellation)) {
            is Verification.Accepted -> {
                certify(result.sample)
                result
            }
            is Verification.Rejected -> ExactNeighborhoodResult.Undecided
            is Verification.Indeterminate -> ExactNeighborhoodResult.Undecided
        }
    }

    fun search(adaptive: Boolean, seed: Long): ExactNeighborhood = geometry.search(adaptive, seed, completion = this,
        maximumImprovementMoves = if (adaptive) params.alnsIterations.toLong() else null,
        nativeWork = { adapter?.work ?: 0 }, completionStats = { adapter?.stats ?: SolveStats.EMPTY },
        onClose = ::close)

    override fun close() {
        adapter?.close()
        geometry.close()
    }
}

internal fun finiteExactNeighborhoodWorker(
    source: BakedProblem,
    fixed: Sample,
    objective: LinearObjective?,
    nodeBudget: com.eignex.klause.backtrack.NodeBudget?,
    adaptive: Boolean,
    seed: Long,
    armId: Int,
): com.eignex.klause.portfolio.PortfolioWorker {
    val params = TheoryParams(nodeBudget = nodeBudget)
    lateinit var worker: com.eignex.klause.portfolio.PortfolioWorker
    var retained: FiniteExactNeighborhood? = null
    fun adapter(): FiniteExactNeighborhood {
        retained?.let { return it }
        val created = FiniteExactNeighborhood(source, fixed, params, objective) { sample ->
            sample.witnessCertificate = worker.evidenceInvocation?.let { binding ->
                val value = binding.executionObjective?.evaluateExact(sample)?.toDouble()
                EvidenceCertificate.published(CompletedEnginePublication.Witness(binding, sample, value))
            }
        }
        retained = created
        return created
    }
    worker = exactNeighborhoodWorker(if (adaptive) "alns/exact-neighborhood" else "ls/exact-neighborhood",
        armId, objective, Cancellation.Never, exhausted = { retained?.exhausted == true },
        open = { adapter().search(adaptive, seed) })
        .bindEvidence(ModelIdentity.of(source, objective), provesResults = false)
    return worker
}
