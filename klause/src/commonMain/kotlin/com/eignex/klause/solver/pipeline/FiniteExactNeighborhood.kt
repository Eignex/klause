package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.RealProduct
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Term
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.baked
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.EvidenceCertificate
import com.eignex.klause.solver.incumbent.CompletedEnginePublication
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.objective.LinearObjective
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
    fixed: Sample,
    private val params: TheoryParams,
    objective: LinearObjective?,
    private val certify: (Sample) -> Unit,
) : ExactNeighborhoodCompletion, AutoCloseable {
    private val linear = source.withIntDomains(source.rootIntDomainsInPlace).withFactors(
        source.factors.map { factor ->
            if (factor is RealProduct) ExactSearchRow.of(
                intArrayOf(Term.ofRealVar(factor.result), Term.ofRealVar(factor.realOperand)),
                listOf(BigFraction.ONE, BigFraction.ZERO - BigFraction.ofLong(fixed.intValue(factor.intOperand))),
                LinearOp.EQ, BigFraction.ZERO,
            ) else factor
        }.toTypedArray() + Array(source.numBoolVars) { id -> Clause(intArrayOf(Lit.make(id, fixed.boolValue(id)))) },
        source.impliedFactorMask?.let { it + BooleanArray(source.numBoolVars) },
    )
    private val adapter = OpenExactNeighborhood(linear, params, objective)
    val exhausted: Boolean get() = adapter.exhausted

    override fun check(
        condition: ExactNeighborhoodCondition,
        cancellation: Cancellation,
    ): ExactNeighborhoodResult {
        val result = adapter.check(condition, cancellation)
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

    fun search(adaptive: Boolean, seed: Long): ExactNeighborhood = adapter.search(adaptive, seed, completion = this,
        maximumImprovementMoves = if (adaptive) params.alnsIterations.toLong() else null)

    override fun close() = adapter.close()
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
