package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.lp.bounding.LpTechnique
import com.eignex.klause.portfolio.ArmFamily
import com.eignex.klause.portfolio.EngineMix
import com.eignex.klause.portfolio.Kind
import com.eignex.klause.portfolio.PortfolioIncumbents
import com.eignex.klause.portfolio.PortfolioScenario
import com.eignex.klause.portfolio.PortfolioWorker
import com.eignex.klause.portfolio.SourceWorkerBinding
import com.eignex.klause.portfolio.binaryIntegers
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.difference.DifferenceSystem
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.ResumableSearch
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.solver.incumbent.EngineInvocation
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.LinearOptimizationResult
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation

internal fun finiteNumericalCompanionsAdmitted(problem: BakedProblem, scenario: PortfolioScenario): Boolean =
    scenario.cores == 1 && scenario.kind == Kind.COP && scenario.engine == EngineMix.MIXED &&
        scenario.btPool == null && scenario.lsPool == null && scenario.btEdit == null &&
        scenario.annotationArm == null &&
        problem.numIntVars > 0 && problem.numRealVars > 0 && problem.binaryIntegers &&
        problem.numBoolVars == 0 && problem.numIntVars.toLong() + problem.numRealVars <= 4_096L &&
        problem.factors.size <= 16_384 && scenario.lpCeiling.resolved(LpTechnique.BOUNDING)

internal class FiniteNumericalCompanions private constructor(
    private val model: BakedProblem,
    private val objective: LinearObjective,
    private val context: SourceProofContext,
    private val params: TheoryParams,
    private val cancellation: Cancellation,
) {
    val incumbents = PortfolioIncumbents.exact(objective)

    fun factories(plan: PreparedSourcePlan, firstArm: Int): List<PortfolioArmFactory> {
        val identity = ModelIdentity.of(model, objective)
        val binding = EngineInvocation(plan, identity, objective)
        fun factory(arm: Int, family: ArmFamily, create: (PortfolioWorkerSlot) -> PortfolioWorker) =
            PortfolioArmFactory(plan, arm, family,
                binding = { SourceWorkerBinding(binding, sharingInvocation = binding,
                    provesResults = it.permitsSourceProofs) }, create = create)
        fun local(slot: PortfolioWorkerSlot) = params.copy(
            randomSeed = slot.seed, nodeBudget = slot.nodeBudget,
            cancellation = cancellation or params.cancellation or slot.cancellation,
            sourceLpWorkLimit = params.sourceLpWorkLimit / 2,
            sourceLpAllocationLimit = params.sourceLpAllocationLimit / 2,
        )
        return listOf(
            factory(firstArm, ArmFamily.Backtrack) { slot ->
                PortfolioWorker.ofMinimize("numerical/finite-companion", firstArm, resumable = {
                    BoundedCompanionSearch(numericalSearch(local(slot)), "finiteNumericalCompanion")
                }) { _, _, _, _ -> error("numerical companion runs only by slice") }.bindEvidence(identity)
            },
            factory(firstArm + 1, ArmFamily.LocalSearch) { slot ->
                val limits = local(slot)
                val repair = slot.own(JointLinearRepair(model, context, objective, limits,
                    boundOrdered = true, external = { incumbents.exchange.current()?.assignment }))
                val worker = exactNeighborhoodWorker("ls/finite-joint-companion", firstArm + 1, objective,
                    limits.cancellation, limits.timeout, { repair.exhausted },
                    open = { repair.search(adaptive = false, seed = slot.seed) })
                PortfolioWorker.ofMinimize("ls/finite-joint-companion", firstArm + 1, countsInstructions = true,
                    resumable = {
                        BoundedCompanionSearch(requireNotNull(worker.newResumableSearch(incumbents::bound)),
                            "finiteJointCompanion")
                    }) { _, _, _, _ -> error("joint companion runs only by slice") }
                    .bindEvidence(identity, provesResults = false)
            },
        )
    }

    private fun numericalSearch(limits: TheoryParams): ResumableSearch = object : ResumableSearch {
        private val search = LinearAuthorityInvocation(context, limits, boundOrdered = true,
            external = { incumbents.exchange.current()?.assignment })
        private var result: MinimizeResult? = null
        override val isDone: Boolean get() = result != null
        override val stats get() = search.stats
        override val work: Long get() = search.work

        override fun runSlice(global: Cancellation, sliceMillis: Long, sliceNodes: Long,
            onIncumbent: (MinimizeResult.WithSample) -> Unit): MinimizeResult? {
            result?.let { return it }
            val finished = search.runSlice(global, sliceMillis, sliceNodes) { sample, value ->
                onIncumbent(MinimizeResult.BestFound(sample, value.toDouble(),
                    TerminationReason.BudgetExhausted, stats))
            } ?: return null
            val original = LinearOptimizationResult(context.scope, finished.terminal, finished.bound,
                finished.incumbent, finished.value, finished.reason, finished.stats, context.semantics)
            result = finiteNumericalCompletion(original, context, global or limits.cancellation or limits.timeout)
            return result
        }

        override fun close() = search.close()
    }

    companion object {
        fun prepare(model: BakedProblem, objective: LinearObjective, params: TheoryParams,
            cancellation: Cancellation): FiniteNumericalCompanions? {
            // The propagated difference graph duplicates affine rows and bounds in these coordinates.
            val affine = model.withFactors(model.factors.filterNot { it is DifferenceSystem }.toTypedArray())
            val context = affine.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE, cancellation,
                numericalIndicators = true) ?: return null
            return FiniteNumericalCompanions(model, objective, context, params, cancellation)
        }
    }
}

internal fun finiteNumericalCompletion(result: LinearOptimizationResult, context: SourceProofContext,
    cancellation: Cancellation): MinimizeResult {
    // A verified relaxation proof uses the finite working coordinates and objective; finite admission checks the point.
    if (result.verifyTerminal(context, cancellation) is Verification.Accepted) {
        when (val claim = result.terminal?.claim) {
            is SourceClaim.Optimal -> return MinimizeResult.Optimal(claim.sample,
                requireNotNull((claim.value as? SourceValue.Rational)?.value)
                    .toDouble(), result.stats)
            SourceClaim.Refutation -> return MinimizeResult.Infeasible(stats = result.stats)
            else -> Unit
        }
    }
    val point = result.incumbent
    val value: BigFraction? = result.value
    val reason = if (result.reason == TerminationReason.SearchExhausted) {
        TerminationReason.Unsupported
    } else result.reason
    return if (point == null || value == null) MinimizeResult.Unknown(reason, result.stats)
    else MinimizeResult.BestFound(point, value.toDouble(), reason, result.stats)
}
