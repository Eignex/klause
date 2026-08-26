package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.ProblemPipeline
import com.eignex.klause.solver.ProblemSpec
import com.eignex.klause.solver.componentPlan
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.theory.TheoryParams
import com.ionspin.kotlin.bignum.integer.BigInteger

/**
 * What an optimizing open-model route answers.
 *
 * An integer route states an [Optimal] because an integer optimum, where one exists over a feasible
 * bounded-below model, is attained. A route over the reals needs a case this does not carry: an
 * objective may approach a greatest lower bound it never reaches, and reporting that as optimal would
 * name a value no assignment achieves.
 */
sealed interface OpenTheoryOptimum {

    /** Statistics collected while optimizing. */
    val stats: SolveStats

    /** An assignment, and a proof that nothing feasible lies below [value]. */
    data class Optimal(
        /** The optimal assignment. */
        val assignment: OpenTheoryAssignment,
        /** Its objective value. */
        val value: BigInteger,
        override val stats: SolveStats,
    ) : OpenTheoryOptimum

    /** The model has no feasible assignment, at any objective value. */
    data class Infeasible(override val stats: SolveStats) : OpenTheoryOptimum

    /**
     * The budget stopped the descent. [incumbent] is the best assignment proved feasible, so the optimum
     * is at or below [value]; no bound under it was refuted. Both are null when nothing was proved
     * feasible, which says only that the run ran out — not that the model is infeasible.
     */
    data class Bounded(
        /** Best assignment proved feasible, or null when none was. */
        val incumbent: OpenTheoryAssignment?,
        /** Objective value of [incumbent], or null when there is none. */
        val value: BigInteger?,
        override val stats: SolveStats,
    ) : OpenTheoryOptimum
}

/**
 * Minimizes a linear objective over an open source model by refuting bounds below the incumbent.
 *
 * Each round asks the model's own theory route to decide the model plus one row stating that the
 * objective beats the incumbent. A satisfying answer tightens the row; a refutation proves the incumbent
 * optimal. So the descent needs no optimizing simplex — the route's feasibility answer carries it, and
 * the objective enters as a constraint the route already reasons about.
 *
 * The route and the ownership plan are selected once, from the model carrying an inactive bound row.
 * Neither depends on that row's constant, so re-bounding reuses both instead of re-reading every factor
 * per improvement.
 */
class OpenTheoryMinimizer(model: ProblemSpec, objective: LinearObjective) {

    private val objective = objective
    private val terms: IntArray
    private val coefficients: LongArray
    private val source: ProblemSpec
    private val base: ProblemSpec
    private val route: ProblemPipeline

    init {
        require(objective.realCoefficients.isEmpty()) {
            "an open integer route cannot minimize an objective over continuous columns"
        }
        require(objective.boolWeights.all { it == 0L }) {
            "an open integer route cannot minimize an objective weighting Boolean columns"
        }
        val present = objective.intCoefficients.indices.filter { objective.intCoefficients[it] != 0L }
        terms = present.toIntArray()
        coefficients = LongArray(present.size) { objective.intCoefficients[present[it]] }
        source = model
        base = model.withRow(boundRow(null))
        route = base.componentPlan().theoryPipeline
        // The bound row is a general linear one, so a model whose rows were all differences leaves that
        // fragment by being optimized at all. Say so here rather than at the first round's engine build.
        require(route != ProblemPipeline.UNSUPPORTED_OPEN && route != ProblemPipeline.FINITE_CP) {
            "objective row leaves the model outside every complete open theory"
        }
    }

    /** The route this minimizer drives, so a caller can decline before starting. */
    val theoryPipeline: ProblemPipeline get() = route

    /** Minimizes the objective, tightening the bound until a round refutes it. */
    fun minimize(params: TheoryParams = TheoryParams()): OpenTheoryOptimum {
        val plan = base.componentPlan()
        var incumbent: OpenTheoryAssignment? = null
        var best: BigInteger? = null
        var spec = base
        while (true) {
            val result = OpenTheoryEngine(spec, route, plan).solve(params)
            when (result) {
                is OpenTheoryResult.Sat -> {
                    val value = objective.valueOf(result.assignment)
                    // A round that does not improve would repeat forever on the same bound.
                    if (best != null && value >= best) {
                        return OpenTheoryOptimum.Optimal(incumbent!!, best, result.stats)
                    }
                    incumbent = result.assignment
                    best = value
                    spec = source.withRow(boundRow(value - BigInteger.ONE))
                }

                is OpenTheoryResult.Unsat ->
                    return if (incumbent == null) {
                        OpenTheoryOptimum.Infeasible(result.stats)
                    } else {
                        OpenTheoryOptimum.Optimal(incumbent, best!!, result.stats)
                    }

                is OpenTheoryResult.Unknown -> return OpenTheoryOptimum.Bounded(incumbent, best, result.stats)
            }
        }
    }

    /**
     * The row `Σ c(i)·x(i) ≤ bound − constant`, or an inactive one when [bound] is null.
     *
     * Carried wide because the descent subtracts one per improvement without knowing how far it will go,
     * and a bound that leaves 64 bits would otherwise wrap into a row admitting what it excludes.
     */
    private fun boundRow(bound: BigInteger?): Factor {
        val wideCoeffs = Array(coefficients.size) { BigInteger.fromLong(coefficients[it]) }
        val rhs = (bound ?: INACTIVE) - BigInteger.fromLong(objective.constant)
        return Linear(terms, wideCoeffs, LinearOp.LE, rhs)
    }

    private fun ProblemSpec.withRow(row: Factor): ProblemSpec = ProblemSpec(
        numBoolVars = numBoolVars,
        intBounds = intBounds,
        factors = factors + row,
        numRealVars = numRealVars,
        realLower = realLower,
        realUpper = realUpper,
    )

    private fun LinearObjective.valueOf(assignment: OpenTheoryAssignment): BigInteger {
        var total = BigInteger.fromLong(constant)
        when (assignment) {
            is OpenTheoryAssignment.Difference -> for (i in terms.indices) {
                total += BigInteger.fromLong(coefficients[i]) * BigInteger.fromLong(assignment.sample.ints[terms[i]])
            }

            is OpenTheoryAssignment.GeneralLia -> for (i in terms.indices) {
                total += BigInteger.fromLong(coefficients[i]) * assignment.assignment.ints[terms[i]]
            }

            else -> error("an open integer route answers with an integer assignment")
        }
        return total
    }

    private companion object {
        /** A right-hand side no objective reaches, so the first round runs unconstrained. */
        val INACTIVE: BigInteger = BigInteger.fromLong(Long.MAX_VALUE)
    }
}
