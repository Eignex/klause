package com.eignex.klause.theory

import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.Term
import com.eignex.klause.lp.ExactComparison
import com.eignex.klause.lp.ExactWitness
import com.eignex.klause.lp.SourceLpFunding
import com.eignex.klause.lp.engine.CertifiedLpResult
import com.eignex.klause.lp.engine.ExactBasisMetrics
import com.eignex.klause.lp.engine.ExactLpBounds
import com.eignex.klause.lp.engine.ExactLpColumn
import com.eignex.klause.lp.engine.ExactLpEntry
import com.eignex.klause.lp.engine.ExactLpModel
import com.eignex.klause.lp.engine.ExactLpNumber
import com.eignex.klause.lp.engine.ExactLpObjective
import com.eignex.klause.lp.engine.ExactLpRow
import com.eignex.klause.lp.engine.ExactLpSide
import com.eignex.klause.lp.engine.LpCertificationGrant
import com.eignex.klause.lp.engine.LpCertificationObserver
import com.eignex.klause.lp.engine.LpCertifier
import com.eignex.klause.lp.engine.LpCertifierCost
import com.eignex.klause.lp.engine.LpClaim
import com.eignex.klause.lp.engine.LpExactOptimization
import com.eignex.klause.lp.engine.LpExactState
import com.eignex.klause.lp.engine.LpFloatAllowance
import com.eignex.klause.lp.engine.LpRefinementLimits
import com.eignex.klause.lp.engine.LpRefinementRequest
import com.eignex.klause.lp.engine.LpScopedSolver
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.lp.engine.LpSolveCursor
import com.eignex.klause.lp.engine.LpSolveMetrics
import com.eignex.klause.lp.engine.LpSolveStep
import com.eignex.klause.lp.engine.LpVerdict
import com.eignex.klause.lp.engine.certifyLpResult
import com.eignex.klause.lp.engine.optimizeFeasibleExactLp
import com.eignex.klause.lp.exactComparison
import com.eignex.klause.simplex.exact.ContinuationDecline
import com.eignex.klause.simplex.exact.ContinuationStop
import com.eignex.klause.simplex.exact.ExactContinuationLimits
import com.eignex.klause.simplex.exact.ExactContinuationMetrics
import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.pipeline.ExactSearchRow
import com.eignex.klause.solver.validation.RealCompletionOptimization
import com.eignex.klause.solver.validation.RealOptimizationResult
import com.eignex.klause.solver.validation.RealOptimizationStep
import com.eignex.klause.solver.validation.RealOptimizationStop
import com.eignex.klause.util.ExecutionControl
import com.eignex.klause.util.counting
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.saturatedAdd
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.TimeSource

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
// Slice pauses retain a round's search, cutoff and cell. A caller stop retires the unfinished round without a proof.
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
    private val spent: Long get() = work
    private val tolerant = search.readsTolerance
    private var encoded = false
    private var encoding: Problem? = null
    private var incumbent: List<BigFraction>? = null
    private var value: BigFraction? = null

    // The least infimum a polyhedron did not attain, and a point of the model below every incumbent.
    private var infimum: BigFraction? = null
    private var nearest: List<BigFraction>? = null
    private var decided: RealOptimizationResult? = null
    private var pending: LpSolveCursor<RealOptimizationResult>? = null
    private var invocation = ExecutionControl.Never
    private var caller = ExecutionControl.Never
    private val limits = context.exactContinuationLimits ?: CELL_CONTINUATION.copy(
        startArbitraryPrecision = context.startArbitraryPrecision,
        preserveOverflowCheckpoint = context.preserveOverflowCheckpoint,
    )
    private val funding = SourceLpFunding(limits.maxWork, limits.maxAllocation)
    private var exactTimeNs = 0L
    private var spentPivots = 0
    private val remainingPivots get() = (limits.maxPivots - spentPivots).coerceAtLeast(0)
    private val remainingWork get() = minOf(funding.remainingWork, caller.admission?.quotaRemaining ?: Long.MAX_VALUE)
    private var closed = false
    private val cancellation = object : ExecutionControl() {
        override fun isCancelled(): Boolean = invocation()
        override fun deadline() = invocation.deadline()
        override val accounting get() = invocation.accounting
        override val admission get() = admissionFrom(invocation)
        override val workScheduled get() = caller.workScheduled
        override val stopPolling get() = caller.stopPolling
    }

    override fun advance(
        cancellation: ExecutionControl,
        workLimit: Long,
        shouldPause: () -> Boolean,
    ): RealOptimizationStep {
        require(workLimit >= 0L)
        decided?.let { return RealOptimizationStep.Completed(it) }
        check(!closed) { "real optimization is closed" }
        caller = cancellation
        invocation = cancellation.counting { units ->
            work = if (units > Long.MAX_VALUE - work) Long.MAX_VALUE else work + units
        }
        val cursor = pending ?: LpSolveCursor<RealOptimizationResult> {
            while (decided == null) {
                if (this@RealLeafOptimization.cancellation()) {
                    return@LpSolveCursor undecided(RealOptimizationStop.CANCELLED)
                }
                round()
                if (decided == null && !this@RealLeafOptimization.cancellation()) checkpoint(spent)
            }
            checkNotNull(decided)
        }.also { pending = it }
        return try {
            when (val step = cursor.advance(workLimit, shouldPause)) {
                LpSolveStep.Paused -> RealOptimizationStep.Paused
                is LpSolveStep.Completed -> {
                    pending = null
                    cursor.close()
                    RealOptimizationStep.Completed(step.value)
                }
            }
        } finally {
            invocation = ExecutionControl.Never
            caller = ExecutionControl.Never
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        val cursor = pending
        pending = null
        cursor?.close()
    }

    private suspend fun LpSolveCursor<RealOptimizationResult>.round() {
        if (!encoded) {
            encoding = search.encoding(sample, cancellation)
            if (cancellation()) return
            encoded = true
            checkpoint(spent)
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
        val found = search.openSearch(sample, model, cutoff, cancellation, solveContext = context).use { cursor ->
            var step = cursor.advance(shouldPause = { shouldPause(spent) })
            while (step is LpSolveStep.Paused) {
                pauseAt(spent)
                if (cancellation()) return
                step = cursor.advance(shouldPause = { shouldPause(spent) })
            }
            (step as LpSolveStep.Completed).value
        }
        if (cancellation()) return
        if (found is AbsRealCompletion.Result.Witness) nearest = found.reals
        checkpoint(spent)
        if (cancellation()) return
        when (found) {
            // A refutation that finished as the stop fired is not trusted; the round runs again.
            AbsRealCompletion.Result.Refuted -> if (!cancellation()) {
                decide(
                    when {
                        floor != null -> RealOptimizationResult.Undecided(bestPoint(), tolerant, floor)
                        attained != null -> RealOptimizationResult.Optimum(checkNotNull(incumbent), attained, tolerant)
                        else -> RealOptimizationResult.Refuted(tolerant)
                    },
                )
            }
            AbsRealCompletion.Result.Undecided -> if (!cancellation()) decide(undecided())
            is AbsRealCompletion.Result.Witness -> {
                val cell = cellOptimum(model, found.reals, cancellation)
                if (cancellation()) return
                when (cell) {
                    is CellOptimum.Attained -> {
                        incumbent = cell.reals
                        value = cell.value
                    }
                    is CellOptimum.Infimum -> if (floor == null || cell.value < floor) infimum = cell.value
                    is CellOptimum.Unbounded ->
                        decide(RealOptimizationResult.Unbounded(cell.reals, cell.direction, tolerant))
                    is CellOptimum.Undecided -> decide(undecided(cell.stop))
                }
            }
        }
    }

    private fun bestPoint() = nearest?.takeIf { value?.let { best -> objectiveAt(it) < best } != false } ?: incumbent

    private fun undecided(stop: RealOptimizationStop? = null) =
        RealOptimizationResult.Undecided(bestPoint(), tolerant, stop = stop)

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
        class Undecided(val stop: RealOptimizationStop? = null) : CellOptimum
    }

    // The exact optimum over the polyhedron of [model] that [point] selects, which contains [point].
    private suspend fun LpSolveCursor<RealOptimizationResult>.cellOptimum(
        model: Problem,
        point: List<BigFraction>,
        cancellation: ExecutionControl,
    ): CellOptimum {
        val captured = try {
            captureCell(model, point)
        } catch (stopped: ContinuationStop) {
            return CellOptimum.Undecided(stopped.reason.realStop())
        } ?: return CellOptimum.Undecided()
        val (rows, bounds, lp) = captured
        checkpoint(spent)
        if (cancellation()) return CellOptimum.Undecided(RealOptimizationStop.CANCELLED)
        solveFast(lp, rows, bounds, point, cancellation)?.let { return it }
        checkpoint(spent)
        if (cancellation()) return CellOptimum.Undecided(RealOptimizationStop.CANCELLED)
        val remaining = limits.copy(
            maxWork = remainingWork,
            maxAllocation = funding.remainingAllocation,
            maxTimeNs = limits.maxTimeNs?.let { (it - exactTimeNs).coerceAtLeast(0L) },
            maxPivots = remainingPivots,
        )
        val exact = optimizeFeasibleExactLp(
            lp, remaining, cancellation, context.certificationPolicy,
            observe = { work, allocation, elapsed, pivots ->
                chargeCell(work, allocation)
                exactTimeNs = saturatedAdd(exactTimeNs, elapsed)
                chargePivots(pivots)
            },
            remainingWork = { remainingWork },
        ) { checkpoint(spent) }
        checkpoint(spent)
        if (cancellation()) return CellOptimum.Undecided(RealOptimizationStop.CANCELLED)
        val columns = bounds.size
        return when (exact) {
            is LpExactOptimization.Optimum -> attain(exact.point.take(columns), rows, bounds, point)
                ?: CellOptimum.Undecided()
            is LpExactOptimization.Infimum -> {
                val endpoint = exact.endpoint.take(columns)
                if (endpoint.size == columns && holds(endpoint, rows, bounds, closure = true)) {
                    CellOptimum.Infimum(objectiveAt(endpoint))
                } else CellOptimum.Undecided()
            }
            is LpExactOptimization.Unbounded -> {
                val direction = exact.direction.take(columns)
                if (direction.size == columns && recedes(direction, rows, bounds)) {
                    CellOptimum.Unbounded(point, direction)
                } else CellOptimum.Undecided()
            }
            is LpExactOptimization.Declined -> CellOptimum.Undecided(exact.reason.realStop())
        }
    }

    private data class CapturedCell(
        val rows: List<ExactRationalInequality>,
        val bounds: List<ExactLpBounds>,
        val lp: ExactLpModel,
    )

    private fun captureCell(model: Problem, point: List<BigFraction>): CapturedCell? {
        val started = TimeSource.Monotonic.markNow()
        val grant = limits.maxTimeNs?.let { (it - exactTimeNs).coerceAtLeast(0L).nanoseconds }
        fun admit(work: Long, allocation: Long) {
            if (cancellation()) throw ContinuationStop(ContinuationDecline.CANCELLED)
            if (grant != null && started.elapsedNow() >= grant) throw ContinuationStop(ContinuationDecline.TIME)
            if (work > remainingWork) throw ContinuationStop(ContinuationDecline.WORK)
            if (allocation > funding.remainingAllocation) throw ContinuationStop(ContinuationDecline.ALLOCATION)
            chargeCell(work, allocation)
        }
        return try {
            val rows = cellRows(model, point, ::admit) ?: return null
            val columns = model.numRealVars
            val variables = columns.toLong() + rows.size
            if (variables + rows.size >= Int.MAX_VALUE) throw ContinuationStop(ContinuationDecline.DIMENSION)
            admit(variables + rows.size, variables * 160L + rows.size * 96L)
            fun side(bound: Double) = BigFraction.ofDouble(bound)?.let { ExactLpSide(ExactLpNumber.of(it)) }
            val bounds = List(columns) { real ->
                ExactLpBounds(side(model.realLower[real]), side(model.realUpper[real]))
            }
            if (!holds(point, rows, bounds)) return null
            CapturedCell(rows, bounds, cellModel(rows, columns, bounds, ::admit))
        } finally {
            exactTimeNs = saturatedAdd(exactTimeNs, started.elapsedNow().inWholeNanoseconds)
        }
    }

    private fun solveFast(
        lp: ExactLpModel,
        rows: List<ExactRationalInequality>,
        bounds: List<ExactLpBounds>,
        point: List<BigFraction>,
        cancellation: ExecutionControl,
    ): CellOptimum? {
        if (remainingWork == 0L || funding.remainingAllocation == 0L || remainingPivots == 0) return null
        val started = TimeSource.Monotonic.markNow()
        val activeNs = limits.maxTimeNs?.let { (it - exactTimeNs).coerceAtLeast(0L) }
        val token = activeNs?.let { cancellation or ExecutionControl.until(started + it.nanoseconds) } ?: cancellation
        val certified = try {
            // Numerical and proof metrics also settle failed work, so the backend must not debit the caller directly.
            proposeAndCheck(lp, token.withAccounting(ExecutionControl.Never))
        } finally {
            exactTimeNs = saturatedAdd(exactTimeNs, started.elapsedNow().inWholeNanoseconds)
        }
        if (certified == null || token()) return null
        val columns = bounds.size
        return when (certified.verdict) {
            LpVerdict.ATTAINED_OPTIMUM -> certified.exactPrimal?.take(columns)?.let { attain(it, rows, bounds, point) }
            LpVerdict.UNBOUNDED -> {
                val unboundedness = checkNotNull(certified.unboundedness)
                val start = unboundedness.witness.primal.take(columns)
                val direction = unboundedness.direction.take(columns)
                if (start.size == columns && direction.size == columns && holds(start, rows, bounds) &&
                    recedes(direction, rows, bounds)
                ) CellOptimum.Unbounded(start, direction) else null
            }
            else -> null
        }
    }

    private fun proposeAndCheck(lp: ExactLpModel, token: ExecutionControl): CertifiedLpResult? {
        val state = LpExactState(lp)
        val iterationLimit = remainingPivots.takeUnless { it == Int.MAX_VALUE } ?: 0
        return LpScopedSolver(
            state, token, context, workLimit = remainingWork, iterationLimit = iterationLimit,
            preparationAllocationLimit = funding.remainingAllocation,
        ).use { solver ->
            val ready = try {
                solver.prepare(token)
            } finally {
                val prepared = solver.metrics
                chargeCell(prepared.measuredPreparationWork, prepared.preparationAllocation)
            }
            if (!ready || remainingWork == 0L || funding.remainingAllocation == 0L || token()) return@use null
            val proposal = try {
                solver.solveFloat(token = token, allowance = LpFloatAllowance(remainingWork, iterationLimit))
            } finally {
                val used = solver.lastMetrics
                chargeCell(saturatedAdd(used.workOps, used.preparationOps), 0L)
                chargePivots(used.pivots)
            }
            if (proposal == null || token() || remainingWork == 0L || funding.remainingAllocation == 0L ||
                spentPivots > limits.maxPivots
            ) {
                return@use null
            }
            val refinement = LpRefinementRequest(
                solver, solver.refinementCache, CELL_REFINEMENT.copy(
                    maxWork = remainingWork, maxAllocation = funding.remainingAllocation, maxPivots = remainingPivots,
                ),
                onBasisVerification = context.onRefinementBasisVerification,
            )
            val result = try {
                certifyLpResult(
                    checkNotNull(state.ownerWorkingModel()), proposal.first, proposal.second, token,
                    observer = Metered(::chargeCell), policy = context.certificationPolicy, refinement = refinement,
                    claim = LpClaim.ATTAINED_OPTIMUM, reconstructionLimits = context.reconstructionLimits,
                    basisLimits = context.basisLimits,
                    certificationGrant = { LpCertificationGrant(remainingWork, funding.remainingAllocation) },
                )
            } finally {
                chargePivots(solver.refinementCache.pivots)
            }
            result.takeUnless {
                funding.spent > limits.maxWork || funding.allocated > limits.maxAllocation ||
                    spentPivots > limits.maxPivots
            }
        }
    }

    private fun attain(
        optimum: List<BigFraction>,
        rows: List<ExactRationalInequality>,
        bounds: List<ExactLpBounds>,
        point: List<BigFraction>,
    ): CellOptimum.Attained? {
        if (optimum.size != bounds.size || !holds(optimum, rows, bounds)) return null
        val value = objectiveAt(optimum)
        return CellOptimum.Attained(optimum, value).takeIf { value <= objectiveAt(point) }
    }

    private fun chargeCell(work: Long, allocation: Long) {
        funding.charge(work, allocation)
        cancellation.charge(work)
    }

    private fun chargePivots(pivots: Int) {
        spentPivots = (spentPivots.toLong() + pivots).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    // The rows over the real columns of the polyhedron [point] selects: every row of a conjunction, the first disjunct
    // [point] holds, and each disequality on the side [point] takes, with the discrete coordinates at their values.
    // Null when a relation has no linear form.
    private fun cellRows(
        model: Problem,
        point: List<BigFraction>,
        admit: (Long, Long) -> Unit,
    ): List<ExactRationalInequality>? {
        admit(1L, 128L)
        val columns = model.numRealVars
        val witness = object : ExactWitness {
            override fun at(column: Int): BigFraction = if (column < columns) point[column]
            else BigFraction.of(sample.exactIntValue(column - columns), BIG_ONE)

            override fun truth(boolVar: Int): Boolean = sample.boolValue(boolVar)
        }
        val mixed = ArrayList<ExactRationalInequality>()
        for (factor in model.factors) {
            admit(1L, 0L)
            fun comparison(row: LinearRow): ExactComparison {
                admit(row.size + 1L, row.size * 128L + 256L)
                return row.exactComparison(columns,
                    row.activator == LinearRow.ALWAYS || witness.truth(row.activator), witness::truth)
            }
            when (val form = factor.linearForm) {
                is LinearForm.Conjunction -> for (row in form.rows) comparison(row).rowsInto(mixed, witness)
                is LinearForm.Disjunction -> {
                    val selected = form.rows.firstNotNullOfOrNull { row ->
                        comparison(row).takeIf { it.holdsAt(witness) }
                    } ?: return null
                    selected.rowsInto(mixed, witness)
                }
                else -> if (factor.variables.reals.isNotEmpty()) return null
            }
        }
        admit(1L, mixed.size * 8L)
        val rows = ArrayList<ExactRationalInequality>(mixed.size)
        for (row in mixed) {
            admit(row.columns.size + 1L, row.columns.size * 32L + 64L)
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
        admit: (Long, Long) -> Unit,
    ): ExactLpModel {
        val entries = List(columns) { ArrayList<ExactLpEntry>() }
        for ((index, row) in rows.withIndex()) {
            admit(row.columns.size + 1L, row.columns.size * 64L)
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

    private fun holds(
        point: List<BigFraction>,
        rows: List<ExactRationalInequality>,
        bounds: List<ExactLpBounds>,
        closure: Boolean = false,
    ) =
        point.indices.all { real ->
            val bound = bounds[real]
            bound.lower?.let { point[real] >= it.number.value } != false &&
                bound.upper?.let { point[real] <= it.number.value } != false
        } && rows.all { row ->
            val activity = activity(row, point)
            if (row.strict && !closure) activity < row.rhs else activity <= row.rhs
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

    private class Metered(private val charge: (Long, Long) -> Unit) : LpCertificationObserver {
        override fun observe(certifier: LpCertifier, success: Boolean, cost: LpCertifierCost) {
            if (cost is LpCertifierCost.Metered) charge(cost.work, cost.allocation)
        }

        override fun observeExactInput(accepted: Boolean) = Unit

        // The solve's own work is read from the solver once it returns.
        override fun observeSolve(metrics: LpSolveMetrics, component: Boolean) = Unit

        override fun observeBasisVerification(metrics: ExactBasisMetrics) = charge(metrics.work, metrics.allocation)

        override fun observeContinuation(metrics: ExactContinuationMetrics) = charge(metrics.work, metrics.allocation)
    }
}

private fun ContinuationDecline.realStop(): RealOptimizationStop? = when (this) {
    ContinuationDecline.WORK -> RealOptimizationStop.WORK
    ContinuationDecline.ALLOCATION -> RealOptimizationStop.ALLOCATION
    ContinuationDecline.DIMENSION -> RealOptimizationStop.DIMENSION
    ContinuationDecline.BITS -> RealOptimizationStop.SCALAR
    ContinuationDecline.PIVOTS, ContinuationDecline.IMPORT_LIMIT -> RealOptimizationStop.PIVOTS
    ContinuationDecline.TIME -> RealOptimizationStop.TIME
    ContinuationDecline.CANCELLED -> RealOptimizationStop.CANCELLED
    else -> null
}

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
