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
import com.eignex.klause.solver.incumbent.EvidenceKind
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
) : ExactNeighborhoodCompletion {
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
        return when (verifyOpenWitness(source, result.sample)) {
            is Verification.Accepted -> {
                result.sample.witnessCertificate = EvidenceCertificate.verified(
                    ModelIdentity.of(source), EvidenceKind.Witness,
                )
                result
            }
            is Verification.Rejected -> ExactNeighborhoodResult.Refuted
            is Verification.Indeterminate -> ExactNeighborhoodResult.Undecided
        }
    }

    fun search(adaptive: Boolean, seed: Long): ExactNeighborhood = adapter.search(adaptive, seed, completion = this,
        maximumImprovementMoves = if (adaptive) params.alnsIterations.toLong() else null)
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
    val adapter = FiniteExactNeighborhood(source, fixed, params, objective)
    return exactNeighborhoodWorker(if (adaptive) "alns/exact-neighborhood" else "ls/exact-neighborhood",
        armId, objective, Cancellation.Never, exhausted = { adapter.exhausted },
        open = { adapter.search(adaptive, seed) })
        .bindEvidence(ModelIdentity.of(source, objective), provesResults = false)
}
