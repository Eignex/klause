package com.eignex.klause.theory

import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.Term
import com.eignex.klause.lp.ExactWitness
import com.eignex.klause.lp.engine.ExactBasisMetrics
import com.eignex.klause.lp.engine.ExactLpBounds
import com.eignex.klause.lp.engine.ExactLpColumn
import com.eignex.klause.lp.engine.ExactLpEntry
import com.eignex.klause.lp.engine.ExactLpModel
import com.eignex.klause.lp.engine.ExactLpNumber
import com.eignex.klause.lp.engine.ExactLpObjective
import com.eignex.klause.lp.engine.ExactLpRow
import com.eignex.klause.lp.engine.ExactLpSide
import com.eignex.klause.lp.engine.LpCertificationObserver
import com.eignex.klause.lp.engine.LpCertifier
import com.eignex.klause.lp.engine.LpCertifierCost
import com.eignex.klause.lp.engine.LpClaim
import com.eignex.klause.lp.engine.LpExactState
import com.eignex.klause.lp.engine.LpRefinementLimits
import com.eignex.klause.lp.engine.LpScopedSolver
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.lp.engine.LpSolveMetrics
import com.eignex.klause.lp.engine.LpVerdict
import com.eignex.klause.lp.exactComparison
import com.eignex.klause.simplex.exact.ExactContinuationLimits
import com.eignex.klause.simplex.exact.ExactContinuationMetrics
import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.pipeline.ExactSearchRow
import com.eignex.klause.solver.validation.RealCompletionOptimization
import com.eignex.klause.solver.validation.RealOptimizationResult
import com.eignex.klause.solver.validation.RealOptimizationStep
import com.eignex.klause.util.ExecutionControl
import com.eignex.klause.util.counting
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BigFraction

// Minimizes `sum(costs(r) * x(r))` over the real coordinates of [sample]'s discrete point by a branch and bound over
// the choices of the completion [search]: the disjunct of each disjunction, such as the sign of an absolute value, the
// operand an extremum selects or the member of a finite membership, and the side of each disequality.
//
// Each round asks the search for a point of its model that beats the incumbent. The incumbent's value enters as the
// strict row `sum(costs * x) < value`, so the search refutes every partial choice whose relaxation cannot go below it.
// The choices that point takes leave a polyhedron that contains it and lies inside the model, and the exact LP over
// that polyhedron gives the round's incumbent. A later point beats that optimum, so it lies in another polyhedron, and
// the rounds end after at most one per combination of choices. A refutation proves the incumbent optimal, or the
// discrete point infeasible when there is none.
//
// A polyhedron with a strict row can have an infimum it does not attain. The least such infimum enters as the row
// `sum(costs * x) <= infimum` instead, which only another polyhedron can meet; a refutation then leaves the minimum
// unattained, and an attained value at or below the infimum is the optimum.
//
// A round runs to completion under the caller's stop: a stopped round decides nothing and runs again on the next call.
internal class RealLeafOptimization(
    private val search: AbsRealCompletion,
    private val sample: Sample,
    private val costs: List<BigFraction>,
    private val context: LpSolveContext,
) : RealCompletionOptimization {
    init {
        require(costs.size == search.realCount) { "objective frame differs from its source" }
    }

    override var work: Long = 0L
        private set
    private val tolerant = search.readsTolerance
    private var encoded = false
    private var encoding: Problem? = null
    private var incumbent: List<BigFraction>? = null
    private var value: BigFraction? = null

    // The least infimum a polyhedron did not attain, and a point of the model below every incumbent.
    private var infimum: BigFraction? = null
    private var nearest: List<BigFraction>? = null
    private var decided: RealOptimizationResult? = null

    override fun advance(
        cancellation: ExecutionControl,
        workLimit: Long,
        shouldPause: () -> Boolean,
    ): RealOptimizationStep {
        require(workLimit >= 0L)
        val start = work
        val counted = cancellation.counting { units ->
            work = if (units > Long.MAX_VALUE - work) Long.MAX_VALUE else work + units
        }
        var ran = false
        while (true) {
            decided?.let { return RealOptimizationStep.Completed(it) }
            if (ran && (shouldPause() || (workLimit > 0L && work - start >= workLimit))) {
                return RealOptimizationStep.Paused
            }
            if (counted()) return RealOptimizationStep.Completed(undecided())
            ran = true
            round(counted)
        }
    }

    private fun round(cancellation: ExecutionControl) {
        if (!encoded) {
            encoding = search.encoding(sample, cancellation)
            if (cancellation()) return
            encoded = true
        }

        // A model with coordinates of its own has points the search does not report in full.
        val model = encoding?.takeIf { it.numRealVars == search.realCount } ?: return decide(undecided())
        val attained = value
        val floor = infimum?.takeIf { attained == null || it < attained }
        val cutoff = when {
            floor != null -> listOf(objectiveRow(floor, strict = false))
            attained != null -> listOf(objectiveRow(attained, strict = true))
            else -> emptyList()
        }
        when (val found = search.search(sample, model, cutoff, cancellation, solveContext = context)) {
            // A refutation that finished as the stop fired is not trusted; the round runs again.
            AbsRealCompletion.Result.Refuted -> if (!cancellation()) {
                decide(
                    when {
                        floor != null -> RealOptimizationResult.Undecided(incumbent ?: nearest, tolerant, floor)
                        attained != null -> RealOptimizationResult.Optimum(checkNotNull(incumbent), attained, tolerant)
                        else -> RealOptimizationResult.Refuted(tolerant)
                    },
                )
            }
            AbsRealCompletion.Result.Undecided -> if (!cancellation()) decide(undecided())
            is AbsRealCompletion.Result.Witness -> when (val cell = cellOptimum(model, found.reals, cancellation)) {
                is CellOptimum.Attained -> {
                    incumbent = cell.reals
                    value = cell.value
                }
                is CellOptimum.Infimum -> {
                    if (floor == null || cell.value < floor) infimum = cell.value
                    nearest = found.reals
                }
                is CellOptimum.Unbounded ->
                    decide(RealOptimizationResult.Unbounded(cell.reals, cell.direction, tolerant))
                CellOptimum.Undecided -> if (!cancellation()) {
                    nearest = found.reals
                    decide(undecided())
                }
            }
        }
    }

    private fun undecided() = RealOptimizationResult.Undecided(incumbent ?: nearest, tolerant)

    private fun decide(result: RealOptimizationResult) {
        decided = result
    }

    // `sum(costs * x) < bound`, or `<=` when not [strict].
    private fun objectiveRow(bound: BigFraction, strict: Boolean): ExactSearchRow {
        val reals = costs.indices.filter { !costs[it].isZero }
        return ExactSearchRow.of(IntArray(reals.size) { Term.ofRealVar(reals[it]) }, reals.map { costs[it] },
            LinearOp.LE, bound, strict)
    }

    private fun objectiveAt(point: List<BigFraction>): BigFraction {
        var total = BigFraction.ZERO
        for (real in costs.indices) if (!costs[real].isZero) total += costs[real] * point[real]
        return total
    }

    private sealed interface CellOptimum {
        class Attained(val reals: List<BigFraction>, val value: BigFraction) : CellOptimum

        // The polyhedron's infimum, which a strict row keeps it from attaining.
        class Infimum(val value: BigFraction) : CellOptimum
        class Unbounded(val reals: List<BigFraction>, val direction: List<BigFraction>) : CellOptimum
        data object Undecided : CellOptimum
    }

    // The exact optimum over the polyhedron of [model] that [point] selects, which contains [point]. A polyhedron with
    // a strict row and no certified optimum is read through its closure, whose optimum is the polyhedron's infimum.
    private fun cellOptimum(model: Problem, point: List<BigFraction>, cancellation: ExecutionControl): CellOptimum {
        val rows = cellRows(model, point, cancellation) ?: return CellOptimum.Undecided
        val columns = model.numRealVars
        fun side(bound: Double) = BigFraction.ofDouble(bound)?.let { ExactLpSide(ExactLpNumber.of(it)) }

        val bounds = List(columns) { real -> ExactLpBounds(side(model.realLower[real]), side(model.realUpper[real])) }
        val open = solve(rows, columns, bounds, point, cancellation)
        if (open != CellOptimum.Undecided || rows.none { it.strict } || cancellation()) return open
        val closure = rows.map { ExactRationalInequality(it.columns, it.coefficients, it.rhs) }
        return when (val closed = solve(closure, columns, bounds, point, cancellation)) {
            is CellOptimum.Attained ->
                if (holds(closed.reals, rows, bounds)) closed else CellOptimum.Infimum(closed.value)
            // The model's point holds every strict row, so it moves along the closure's ray inside the polyhedron.
            is CellOptimum.Unbounded ->
                if (recedes(closed.direction, rows, bounds)) CellOptimum.Unbounded(point, closed.direction)
                else CellOptimum.Undecided
            else -> closed
        }
    }

    private fun solve(
        rows: List<ExactRationalInequality>,
        columns: Int,
        bounds: List<ExactLpBounds>,
        point: List<BigFraction>,
        cancellation: ExecutionControl,
    ): CellOptimum {
        val lp = cellModel(rows, columns, bounds, cancellation) ?: return CellOptimum.Undecided
        val certified = LpScopedSolver(LpExactState(lp), cancellation, context).use { solver ->
            try {
                solver.solve(token = cancellation, observer = Metered(cancellation), refinementLimits = CELL_REFINEMENT,
                    continuationLimits = CELL_CONTINUATION, fullContinuation = true, claim = LpClaim.ATTAINED_OPTIMUM)
            } finally {
                cancellation.charge(solver.lastMetrics.workOps + solver.lastMetrics.preparationOps)
            }
        }
        if (certified == null || cancellation()) return CellOptimum.Undecided
        cancellation.charge((certified.refinement?.work ?: 0L) + (certified.reconstruction?.work ?: 0L))
        return when (certified.verdict) {
            LpVerdict.ATTAINED_OPTIMUM -> {
                val optimum = certified.exactPrimal?.take(columns) ?: return CellOptimum.Undecided
                val optimal = objectiveAt(optimum)
                if (optimum.size != columns || !holds(optimum, rows, bounds) || optimal > objectiveAt(point)) {
                    CellOptimum.Undecided
                } else CellOptimum.Attained(optimum, optimal)
            }
            LpVerdict.UNBOUNDED -> {
                val unboundedness = checkNotNull(certified.unboundedness)
                val start = unboundedness.witness.primal.take(columns)
                val direction = unboundedness.direction.take(columns)
                if (start.size == columns && direction.size == columns && holds(start, rows, bounds) &&
                    recedes(direction, rows, bounds)
                ) CellOptimum.Unbounded(start, direction) else CellOptimum.Undecided
            }
            else -> CellOptimum.Undecided
        }
    }

    // The rows over the real columns of the polyhedron [point] selects: every row of a conjunction, the first disjunct
    // [point] holds, and each disequality on the side [point] takes, with the discrete coordinates at their values.
    // Null when a relation has no linear form.
    private fun cellRows(
        model: Problem,
        point: List<BigFraction>,
        cancellation: ExecutionControl,
    ): List<ExactRationalInequality>? {
        val columns = model.numRealVars
        val witness = object : ExactWitness {
            override fun at(column: Int): BigFraction = if (column < columns) point[column]
            else BigFraction.of(sample.exactIntValue(column - columns), BIG_ONE)

            override fun truth(boolVar: Int): Boolean = sample.boolValue(boolVar)
        }
        val mixed = ArrayList<ExactRationalInequality>()
        for (factor in model.factors) {
            cancellation.charge(1)
            if (cancellation()) return null
            fun comparison(row: LinearRow) = row.exactComparison(columns,
                row.activator == LinearRow.ALWAYS || witness.truth(row.activator), witness::truth)
            when (val form = factor.linearForm) {
                is LinearForm.Conjunction -> for (row in form.rows) comparison(row).rowsInto(mixed, witness)
                is LinearForm.Disjunction ->
                    (form.rows.map(::comparison).firstOrNull { it.holdsAt(witness) } ?: return null)
                        .rowsInto(mixed, witness)
                else -> if (factor.variables.reals.isNotEmpty()) return null
            }
        }
        val rows = ArrayList<ExactRationalInequality>(mixed.size)
        for (row in mixed) {
            cancellation.charge(row.columns.size.toLong() + 1L)
            if (cancellation()) return null
            var rhs = row.rhs
            val kept = ArrayList<Int>()
            for (index in row.columns.indices) {
                if (row.columns[index] < columns) kept += index
                else rhs -= row.coefficients[index] * witness.at(row.columns[index])
            }
            if (kept.isEmpty()) {
                if (if (row.strict) rhs <= BigFraction.ZERO else rhs < BigFraction.ZERO) return null
                continue
            }
            rows += ExactRationalInequality(IntArray(kept.size) { row.columns[kept[it]] },
                kept.map { row.coefficients[it] }, rhs, row.strict)
        }
        return rows
    }

    // `rows(i) + s(i) = rhs(i)` with a slack `s(i) >= 0`, strict for a strict row, over free or bounded real columns.
    private fun cellModel(
        rows: List<ExactRationalInequality>,
        columns: Int,
        bounds: List<ExactLpBounds>,
        cancellation: ExecutionControl,
    ): ExactLpModel? {
        val entries = List(columns) { ArrayList<ExactLpEntry>() }
        for ((index, row) in rows.withIndex()) {
            cancellation.charge(row.columns.size.toLong() + 1L)
            if (cancellation()) return null
            for (k in row.columns.indices) {
                entries[row.columns[k]] += ExactLpEntry(index, ExactLpNumber.of(row.coefficients[k]))
            }
        }
        val zero = ExactLpNumber.of(0L)
        return ExactLpModel(
            entries,
            rows.map { ExactLpNumber.of(it.rhs) },
            List(columns) { ExactLpColumn(bounds[it], integral = false) } +
                rows.map { ExactLpColumn(ExactLpBounds(ExactLpSide(zero, it.strict)), integral = false) },
            rows.map { ExactLpRow(strict = it.strict) },
            ExactLpObjective(List(columns + rows.size) { if (it < columns) ExactLpNumber.of(costs[it]) else zero }),
        )
    }

    private fun holds(point: List<BigFraction>, rows: List<ExactRationalInequality>, bounds: List<ExactLpBounds>) =
        point.indices.all { real ->
            val bound = bounds[real]
            bound.lower?.let { point[real] >= it.number.value } != false &&
                bound.upper?.let { point[real] <= it.number.value } != false
        } && rows.all { row ->
            val activity = activity(row, point)
            if (row.strict) activity < row.rhs else activity <= row.rhs
        }

    // Whether moving along [direction] keeps every row and bound and lowers the objective.
    private fun recedes(
        direction: List<BigFraction>,
        rows: List<ExactRationalInequality>,
        bounds: List<ExactLpBounds>,
    ): Boolean {
        val bounded = direction.indices.all { real ->
            val sign = direction[real].signum()
            (sign >= 0 || bounds[real].lower == null) && (sign <= 0 || bounds[real].upper == null)
        }
        return bounded && objectiveAt(direction) < BigFraction.ZERO &&
            rows.all { activity(it, direction) <= BigFraction.ZERO }
    }

    private fun activity(row: ExactRationalInequality, point: List<BigFraction>): BigFraction {
        var total = BigFraction.ZERO
        for (k in row.columns.indices) total += row.coefficients[k] * point[row.columns[k]]
        return total
    }

    // Charges the exact LP's reported work to [cancellation].
    private class Metered(private val cancellation: ExecutionControl) : LpCertificationObserver {
        override fun observe(certifier: LpCertifier, success: Boolean, cost: LpCertifierCost) {
            if (cost is LpCertifierCost.Metered) cancellation.charge(cost.work)
        }

        override fun observeExactInput(accepted: Boolean) = Unit

        // The solve's own work is read from the solver once it returns.
        override fun observeSolve(metrics: LpSolveMetrics, component: Boolean) = Unit

        override fun observeBasisVerification(metrics: ExactBasisMetrics) = cancellation.charge(metrics.work)

        override fun observeContinuation(metrics: ExactContinuationMetrics) = cancellation.charge(metrics.work)
    }
}

// Refinement seeks an optimum under the caller's stop. Feasibility continuation can recover a missing point,
// but neither that point nor exhausted refinement rounds establish attainment.
private const val CELL_EXACT_ALLOCATION = 256L * 1024L * 1024L

private val CELL_REFINEMENT = LpRefinementLimits(
    maxRounds = 64,
    maxCoordinates = Int.MAX_VALUE,
    maxEntries = Int.MAX_VALUE,
    maxBits = Int.MAX_VALUE,
    maxWork = Long.MAX_VALUE,
    maxAllocation = CELL_EXACT_ALLOCATION,
    maxPivots = Int.MAX_VALUE,
    maxBasisDimension = Int.MAX_VALUE,
    maxBasisFill = Int.MAX_VALUE,
)

private val CELL_CONTINUATION = ExactContinuationLimits(
    maxColumns = Int.MAX_VALUE,
    maxCells = Int.MAX_VALUE,
    maxBits = Int.MAX_VALUE,
    maxWork = Long.MAX_VALUE,
    maxAllocation = CELL_EXACT_ALLOCATION,
    maxImportPivots = Int.MAX_VALUE,
    maxPivots = Int.MAX_VALUE,
)
