package com.eignex.klause.lp

import com.eignex.klause.lp.engine.CertifiedLpResult
import com.eignex.klause.lp.engine.ExactLpBounds
import com.eignex.klause.lp.engine.ExactLpColumn
import com.eignex.klause.lp.engine.ExactLpEntry
import com.eignex.klause.lp.engine.ExactLpModel
import com.eignex.klause.lp.engine.ExactLpNumber
import com.eignex.klause.lp.engine.ExactLpObjective
import com.eignex.klause.lp.engine.ExactLpRow
import com.eignex.klause.lp.engine.ExactLpSide
import com.eignex.klause.lp.engine.LpBoundAssertion
import com.eignex.klause.lp.engine.LpBoundBatchResult
import com.eignex.klause.lp.engine.LpCertificationObserver
import com.eignex.klause.lp.engine.LpCertifier
import com.eignex.klause.lp.engine.LpExactState
import com.eignex.klause.lp.engine.LpScopedRow
import com.eignex.klause.lp.engine.LpScopedSolver
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.lp.engine.LpSolveMetrics
import com.eignex.klause.lp.engine.LpVerdict
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ExactContinuationMetrics
import com.eignex.klause.simplex.exact.ExactDoubleBoundedRow
import com.eignex.klause.simplex.exact.ExactDoubleBoundedSplit
import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.klause.solver.result.SourceLpWorkStats
import com.eignex.klause.util.Cancellation
import kotlin.time.TimeMark
import kotlin.time.TimeSource.Monotonic

internal class SourceLpBudget(
    private val maxOperations: Int = 256,
    private val maxActiveNanos: Long = 5_000_000_000L,
    private val solveContext: () -> LpSolveContext = { LpSolveContext.Production },
    private val onWork: (SourceLpWorkStats) -> Unit = {},
) {
    val context: LpSolveContext get() = solveContext()

    var operations: Int = 0
        private set
    var reservedWork: Long = 0L
        private set
    var reservedAllocation: Long = 0L
        private set
    var activeNanos: Long = 0L
        private set

    var measuredPreparationWork: Long = 0L
        private set
    var measuredFloatWork: Long = 0L
        private set
    var measuredContinuationWork: Long = 0L
        private set
    var measuredRefinementWork: Long = 0L
        private set

    private var reported = SourceLpWorkStats()

    fun observe(preparation: Long = 0L, floatWork: Long = 0L, continuation: Long = 0L, refinement: Long = 0L) {
        measuredPreparationWork += minOf(preparation, Long.MAX_VALUE - measuredPreparationWork)
        measuredFloatWork += minOf(floatWork, Long.MAX_VALUE - measuredFloatWork)
        measuredContinuationWork += minOf(continuation, Long.MAX_VALUE - measuredContinuationWork)
        measuredRefinementWork += minOf(refinement, Long.MAX_VALUE - measuredRefinementWork)
        if (activeStart == null) publish()
    }

    private fun publish() {
        val current = SourceLpWorkStats(
            operations.toLong(),
            reservedWork,
            reservedAllocation,
            activeNanos,
            measuredPreparationWork,
            measuredFloatWork,
            measuredContinuationWork,
            measuredRefinementWork,
        )
        val previous = reported
        reported = current
        val delta = SourceLpWorkStats(
            current.operations - previous.operations,
            current.modeledWork - previous.modeledWork,
            current.modeledAllocation - previous.modeledAllocation,
            current.activeNs - previous.activeNs,
            current.preparationWork - previous.preparationWork,
            current.floatWork - previous.floatWork,
            current.continuationWork - previous.continuationWork,
            current.refinementWork - previous.refinementWork,
        )
        onWork(delta)
    }

    fun closeOwner(owner: AutoCloseable?) {
        val start = Monotonic.markNow()
        try {
            owner?.close()
        } finally {
            if (activeStart == null) {
                activeNanos += minOf(
                    start.elapsedNow().inWholeNanoseconds,
                    Long.MAX_VALUE - activeNanos,
                )
                publish()
            }
        }
    }

    private var activeStart: TimeMark? = null

    fun <T> run(
        rows: List<ExactRationalInequality>,
        variables: Int,
        cancellation: Cancellation,
        additional: List<List<ExactRationalInequality>> = emptyList(),
        rhs: List<BigFraction> = emptyList(),
        block: (Cancellation) -> T?,
    ): T? {
        if (cancellation() || activeNanos >= maxActiveNanos || operations >= maxOperations) return null
        operations++
        reservedWork += 1024L
        val outermost = activeStart == null
        if (outermost) activeStart = Monotonic.markNow()
        val start = checkNotNull(activeStart)
        val token = Cancellation {
            cancellation() || start.elapsedNow().inWholeNanoseconds >= maxActiveNanos - activeNanos
        }
        try {
            val groups = listOf(rows) + additional
            val rowCount = groups.sumOf { it.size.toLong() }
            if (variables < 0 || variables.toLong() + rowCount > 128L || rhs.size > 128) return null
            var entries = 0L
            var bits = 0L
            fun admit(number: BigFraction): Boolean {
                if (token()) return false
                val numerator = number.num.bitLength()
                val denominator = number.den.bitLength()
                if (numerator > 4096 || denominator > 4096) return false
                bits += numerator.toLong() + denominator
                return bits <= 8192L
            }
            for (group in groups) {
                for (row in group) {
                    if (token()) return null
                    entries += row.columns.size
                    if (entries > 512L || row.columns.any { it !in 0 until variables }) return null
                    if (!admit(row.rhs) || row.coefficients.any { !admit(it) }) return null
                }
            }
            if (rhs.any { !admit(it) }) return null
            val width = bits + 4096L * (variables + rowCount) + 64L
            val visits = 32L * (entries + variables + rowCount * 2L + 1L)
            val words = (width + 63L) / 64L
            val work = 400_000_000L + visits * words * words
            val allocation = 1_073_741_824L + visits * (64L + 16L * width)
            if (work > 1_000_000_000_000L || allocation > 2_147_483_648L) return null
            reservedWork += work
            reservedAllocation += allocation
            return block(token).takeUnless { token() }
        } finally {
            if (outermost) {
                activeNanos += minOf(start.elapsedNow().inWholeNanoseconds, Long.MAX_VALUE - activeNanos)
                activeStart = null
                publish()
            }
        }
    }
}

internal class SourceLp(
    private val inputRows: List<ExactRationalInequality>,
    private val variables: Int,
    private val budget: SourceLpBudget,
    private val cone: Boolean = false,
) : AutoCloseable {
    private var frozenRows: List<ExactRationalInequality>? = null
    private val rows: List<ExactRationalInequality> get() = checkNotNull(frozenRows)
    private var owner: LpScopedSolver? = null
    private var currentToken = Cancellation.Never
    private val definitions = LinkedHashMap<List<Pair<Int, BigFraction>>, Int>()
    private val definitionRows = ArrayList<ExactRationalInequality>()
    private var installedRhs: List<BigFraction>? = null
    private var witness = 0L
    private var recordedPreparation = 0L
    private val observer = object : LpCertificationObserver {
        override fun observe(certifier: LpCertifier, success: Boolean) = Unit
        override fun observeExactInput(accepted: Boolean) = Unit
        override fun observeSolve(metrics: LpSolveMetrics, component: Boolean) = Unit
        override fun observeContinuation(metrics: ExactContinuationMetrics) {
            budget.observe(continuation = metrics.work)
        }
    }
    private var failed = false
    private var closed = false

    @Suppress("TooGenericExceptionCaught")
    fun solve(
        cancellation: Cancellation,
        activity: Int? = null,
        rhs: List<BigFraction>? = null,
        branches: List<ExactRationalInequality> = emptyList(),
    ): CertifiedLpResult? {
        if (failed || closed) return null
        val admittedRows = frozenRows ?: inputRows
        require(activity == null || activity in admittedRows.indices)
        require(rhs == null || rhs.size == admittedRows.size)
        require(rhs == null || (branches.isEmpty() && definitions.isEmpty()))
        try {
            return budget.run(
                admittedRows,
                variables,
                cancellation,
                listOf(definitionRows, branches),
                rhs ?: emptyList(),
            ) { token ->
                currentToken = token
                if (frozenRows == null) {
                    frozenRows = admittedRows.map {
                        ExactRationalInequality(it.columns.copyOf(), it.coefficients.toList(), it.rhs, it.strict)
                    }
                }
                val requestedRhs = rhs?.toList() ?: rows.map { it.rhs }
                val currentRows = rows.mapIndexed { i, row ->
                    ExactRationalInequality(row.columns, row.coefficients, requestedRhs[i], row.strict)
                }
                val current = owner ?: LpScopedSolver(
                    LpExactState(model()),
                    Cancellation { currentToken() },
                    context = budget.context,
                    iterationLimit = 1024,
                    workLimit = 2_000_000L,
                    maxRetainedRows = 128,
                ).also {
                    owner = it
                    installedRhs = rows.map { row -> row.rhs }
                }
                if (installedRhs != requestedRhs) {
                    if (definitions.isNotEmpty()) return@run null
                    if (!current.resetRoot(
                            LpExactState(current.state.baseModel.copy(rhs = requestedRhs.map(ExactLpNumber::of))),
                            token,
                        )
                    ) {
                        return@run null
                    }
                    installedRhs = requestedRhs
                }
                for (row in branches) {
                    if (row.columns.size == 1 && !row.coefficients.single().isZero) continue
                    val terms = row.columns.indices.map { row.columns[it] to row.coefficients[it] }
                    if (terms in definitions) continue
                    if (current.state.model.numVars >= 128) return@run null
                    val column = current.state.model.numVars
                    val appended = budget.run(
                        rows,
                        variables,
                        token,
                        listOf(definitionRows, listOf(row)),
                    ) { appendToken ->
                        current.append(
                            LpScopedRow(
                                current.state.rows.entries().maxOfOrNull { it.id }?.plus(1L) ?: 0L,
                                terms.map { it.first to ExactLpNumber.of(it.second) },
                                ExactLpNumber.of(0L),
                                ExactLpColumn(ExactLpBounds(), integral = false),
                            ),
                            false,
                            appendToken,
                        )
                    }
                    if (appended != true) return@run null
                    definitions[terms] = column
                    definitionRows += ExactRationalInequality(
                        row.columns.copyOf(),
                        row.coefficients.toList(),
                        BigFraction.ZERO,
                    )
                }
                val objective = ExactLpObjective(
                    List(current.state.model.numVars) { j ->
                        ExactLpNumber.of(if (activity != null && j == variables + activity) -1L else 0L)
                    },
                    ExactLpNumber.of(activity?.let { currentRows[it].rhs } ?: BigFraction.ZERO),
                )
                if (!current.replaceObjective(objective, token)) return@run null
                if (branches.isNotEmpty() && !current.push(token)) return@run null
                AutoCloseable {
                    if (branches.isNotEmpty() && !current.pop(0, Cancellation.Never)) failed = true
                }.use {
                    val assertions = ArrayList<LpBoundAssertion>(branches.size)
                    for (row in branches) {
                        val singleton = row.columns.size == 1 && !row.coefficients.single().isZero
                        val column = if (singleton) {
                            row.columns.single()
                        } else {
                            definitions.getValue(
                                row.columns.indices.map { row.columns[it] to row.coefficients[it] },
                            )
                        }
                        val side = if (singleton) {
                            row.rhs * row.coefficients.single().reciprocal()
                        } else {
                            row.rhs.negated()
                        }
                        if (!listOf(side).admittedSourcePoint()) return@run null
                        assertions += LpBoundAssertion(
                            column,
                            singleton && row.coefficients.single() > BigFraction.ZERO,
                            ExactLpSide(ExactLpNumber.of(side), row.strict),
                            witness++,
                            current.state.depth,
                        )
                    }
                    if (current.assertBounds(assertions, token) is LpBoundBatchResult.Declined) return@run null
                    val refinementBefore = current.refinementCache.work
                    val solved = try {
                        current.solve(token = token, observer = observer)
                    } finally {
                        budget.observe(
                            floatWork = current.lastMetrics.workOps,
                            refinement = (current.refinementCache.work - refinementBefore).coerceAtLeast(0L),
                        )
                    }
                    val result = solved ?: return@run null
                    val point = result.exactPrimal?.take(variables)
                    if (point != null && (
                            !point.admittedSourcePoint() || !point.satisfiesSourceRows(currentRows, token) ||
                                !point.satisfiesSourceRows(branches, token)
                            )
                    ) {
                        return@run null
                    }
                    if (result.lowerBound?.let { !listOf(it).admittedSourcePoint() } == true) return@run null
                    result
                }
            }.also { if (it == null) failed = true }
        } catch (failure: Throwable) {
            failed = true
            try {
                close()
            } catch (cleanup: Throwable) {
                failure.addSuppressed(cleanup)
            }
            throw failure
        } finally {
            recordPreparation()
        }
    }

    private fun recordPreparation() {
        val preparation = owner?.metrics?.preparationWork ?: return
        budget.observe(preparation = (preparation - recordedPreparation).coerceAtLeast(0L))
        recordedPreparation = preparation
    }

    private fun model(): ExactLpModel {
        val zero = ExactLpNumber.of(0L)
        val columns = List(variables) { column ->
            rows.mapIndexedNotNull { index, row ->
                row.columns.indexOf(column).takeIf { it >= 0 }?.let {
                    ExactLpEntry(index, ExactLpNumber.of(row.coefficients[it]))
                }
            }
        }
        return ExactLpModel(
            columns,
            rows.map { ExactLpNumber.of(it.rhs) },
            List(variables) {
                ExactLpColumn(
                    if (cone) {
                        ExactLpBounds(
                            ExactLpSide(ExactLpNumber.of(-1L)),
                            ExactLpSide(ExactLpNumber.of(1L)),
                        )
                    } else {
                        ExactLpBounds()
                    },
                    integral = false,
                )
            } + rows.map { ExactLpColumn(ExactLpBounds(ExactLpSide(zero, it.strict)), integral = false) },
            rows.map { ExactLpRow(strict = it.strict) },
            ExactLpObjective(List(variables + rows.size) { zero }),
        )
    }

    override fun close() {
        if (closed) return
        closed = true
        recordPreparation()
        val current = owner
        owner = null
        budget.closeOwner(current)
    }
}

internal fun List<BigFraction>.admittedSourcePoint(): Boolean = all {
    it.num.bitLength() <= 4096 && it.den.bitLength() <= 4096
}

internal fun List<BigFraction>.satisfiesSourceRows(
    rows: List<ExactRationalInequality>,
    cancellation: Cancellation = Cancellation.Never,
): Boolean = rows.all { row ->
    if (cancellation()) return@all false
    var value = BigFraction.ZERO
    for (i in row.columns.indices) {
        if (cancellation()) return@all false
        value += this[row.columns[i]] * row.coefficients[i]
    }
    if (row.strict) value < row.rhs else value <= row.rhs
}

internal fun sourceDoubleBoundedSplit(
    rows: List<ExactRationalInequality>,
    variables: Int,
    budget: SourceLpBudget,
    cancellation: Cancellation,
): ExactDoubleBoundedSplit {
    return budget.run(rows, variables, cancellation) { token ->
        val homogeneous = rows.map { ExactRationalInequality(it.columns, it.coefficients, BigFraction.ZERO) }
        SourceLp(homogeneous, variables, budget, cone = true).use { cone ->
            SourceLp(rows, variables, budget).use { source ->
                val bounded = ArrayList<ExactDoubleBoundedRow>()
                val unbounded = ArrayList<Int>()
                for (index in rows.indices) {
                    val direction = cone.solve(token, activity = index) ?: return@run ExactDoubleBoundedSplit.Unknown
                    val activity = direction.witness?.objective
                    if (activity != null && activity < BigFraction.ZERO) {
                        unbounded += index
                        continue
                    }
                    if (direction.lowerBound?.let { it >= BigFraction.ZERO } !=
                        true
                    ) {
                        return@run ExactDoubleBoundedSplit.Unknown
                    }
                    val result = source.solve(token, activity = index) ?: return@run ExactDoubleBoundedSplit.Unknown
                    if (result.verdict == LpVerdict.INFEASIBLE) return@run ExactDoubleBoundedSplit.Infeasible
                    val lower = result.lowerBound ?: return@run ExactDoubleBoundedSplit.Unknown
                    if (lower > rows[index].rhs) return@run ExactDoubleBoundedSplit.Unknown
                    bounded += ExactDoubleBoundedRow(index, rows[index], lower)
                }
                ExactDoubleBoundedSplit.Split(bounded, unbounded)
            }
        }
    } ?: ExactDoubleBoundedSplit.Unknown
}

internal fun sourceDescendingDirection(
    rows: List<ExactRationalInequality>,
    activity: ExactRationalInequality,
    variables: Int,
    cancellation: Cancellation,
    budget: SourceLpBudget,
): Boolean? {
    return budget.run(rows, variables, cancellation, additional = listOf(listOf(activity))) { token ->
        val homogeneous = (rows + activity).map {
            ExactRationalInequality(
                it.columns,
                it.coefficients,
                BigFraction.ZERO,
            )
        }
        SourceLp(homogeneous, variables, budget, cone = true).use { cone ->
            val result = cone.solve(token, activity = rows.size) ?: return@run null
            if (result.witness?.objective?.let { it < BigFraction.ZERO } == true) return@run true
            false.takeIf { result.lowerBound?.let { it >= BigFraction.ZERO } == true }
        }
    }
}

@Suppress("TooGenericExceptionCaught")
internal fun closeSourceLpOwners(owners: List<AutoCloseable>) {
    var primary: Throwable? = null
    for (owner in owners) {
        try {
            owner.close()
        } catch (failure: Throwable) {
            if (primary == null) primary = failure else primary.addSuppressed(failure)
        }
    }
    primary?.let { throw it }
}
