package com.eignex.klause.meta.alns

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.BoolFoldDefinition
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.AcceptanceCriterion
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.localsearch.RepairEndpoint
import com.eignex.klause.localsearch.RepairTrace
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.DeducedRestrictions
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.IncumbentBootstrapSearch
import com.eignex.klause.solver.InstructionSlicedSearch
import com.eignex.klause.solver.Optimizer
import com.eignex.klause.solver.RepairSearch
import com.eignex.klause.solver.RepairSearchStep
import com.eignex.klause.solver.ResumableOptimizer
import com.eignex.klause.solver.ResumableSearch
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.Candidate
import com.eignex.klause.solver.incumbent.IncumbentExchange
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.LocalSearchStats
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.SearchStats
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.solver.validation.finiteWitnessVerifier
import com.eignex.klause.util.ExecutionControl
import com.eignex.kumulant.stat.summary.SumResult
import com.eignex.util.numbers.BigFraction
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

internal object AlnsTestFixtures {
    // The production deep arm's flipsOverride replaces maxFlips rather than being capped by it.
    val scaledRepairOperators: List<RepairOperator> = listOf(
        InnerLsRepair(label = "standard"),
        InnerLsRepair(label = "quick", flipsOverride = 50L),
        InnerLsRepair(label = "deep", flipsOverride = 250L),
        GreedyConstructionRepair(),
        GreedyConstructionRepair(noise = 0.1),
    )

    fun selectOptimum(): Sample {
        val cheapest = SELECT_WEIGHTS.indices.sortedBy { SELECT_WEIGHTS[it] }.take(SELECT_MIN).toSet()
        return Sample(BooleanArray(SELECT_WEIGHTS.size) { it in cheapest }, LongArray(0))
    }

    fun selectProblem(): BakedProblem = Problem(
        numBoolVars = SELECT_WEIGHTS.size,
        numIntVars = 0,
        intDomains = emptyArray(),
        factors = arrayOf<Factor>(
            Cardinality(
                IntArray(SELECT_WEIGHTS.size) { Lit.make(it, true) },
                min = SELECT_MIN,
                max = SELECT_WEIGHTS.size,
            ),
        ),
    ).bake()

    fun selectAlns(exchange: IncumbentExchange<Sample, Double>) = Alns(
        inner = LocalSearchSolver(selectProblem()),
        repairOperators = scaledRepairOperators,
        minDestroyFraction = 0.5,
        maxDestroyFraction = 0.5,
        maxIterations = 4,
        flipsPerIteration = 50L,
        acceptance = AcceptanceCriterion.BetterOrEqual,
        pooledIncumbents = exchange,
    )

    val selectObjective = LinearObjective(boolWeights = SELECT_WEIGHTS)

    fun selectExchangeHolding(sample: Sample) = IncumbentExchange.minimizing<Sample>()
        .apply { offer(sample, selectObjective.evaluate(sample)) }

    // All-on with a single flip makes the bootstrap the worst feasible selection, so adoption is visible.
    fun starvedBootstrap() = LocalSearchParams(
        maxFlips = 1L,
        randomSeed = 1L,
        initialAssignment = Sample(BooleanArray(SELECT_WEIGHTS.size) { true }, LongArray(0)),
    )


    fun bootstrapAt(problem: BakedProblem, sample: Sample) =
        object : Optimizer<LocalSearchParams> by NoFeasibleLs(problem) {
            override fun minimize(objective: LinearObjective, params: LocalSearchParams): MinimizeResult =
                MinimizeResult.BestFound(sample, objective.evaluate(sample), TerminationReason.BudgetExhausted)
        }

    fun chainProblem(): BakedProblem = Problem(
        3,
        0,
        emptyArray(),
        listOf(
            Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
            Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false))),
            Clause(intArrayOf(Lit.make(1, true), Lit.make(2, true))),
            Clause(intArrayOf(Lit.make(1, false), Lit.make(2, false))),
        ),
    ).bake()

    fun chainSweep(problem: BakedProblem): DefinitionalSweep = assertNotNull(
        DefinitionalSweep.infer(
            problem,
            boolFolds = listOf(
                BoolFoldDefinition(1, intArrayOf(Lit.make(0, false)), true),
                BoolFoldDefinition(2, intArrayOf(Lit.make(1, false)), false),
            ),
        ),
    )

    class NoFeasibleLs(override val problem: BakedProblem) : Optimizer<LocalSearchParams> {
        override fun minimize(objective: LinearObjective, params: LocalSearchParams): MinimizeResult =
            MinimizeResult.Unknown(TerminationReason.BudgetExhausted)

        override fun solve(params: LocalSearchParams) = error("unused")
        override fun samples(params: LocalSearchParams) = error("unused")
        override fun enumerate(params: LocalSearchParams) = error("unused")
    }


    val SELECT_WEIGHTS = longArrayOf(7L, 3L, 5L, 9L, 1L, 8L, 2L, 6L, 12L, 4L, 11L, 10L)
    const val SELECT_MIN = 4

    val SELECT_DEAREST = listOf(8, 10, 11, 3)
}
