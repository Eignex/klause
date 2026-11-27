package com.eignex.klause.lp.bounding

import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.symmetry.SymmetryHandling
import com.eignex.klause.ir.IntegerConstants
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.exactBounds
import com.eignex.klause.lp.engine.exactConstant
import com.eignex.klause.lp.engine.exactCost
import com.eignex.klause.lp.engine.exactRhs
import com.eignex.klause.lp.engine.exactShift
import com.eignex.klause.lp.engine.forEachRationalColumn
import com.eignex.klause.lp.relaxation.CpToLpRelaxation
import com.eignex.klause.lp.relaxation.LpAssemblyCancelled
import com.eignex.klause.lp.relaxation.LpRelaxation
import com.eignex.klause.lp.relaxation.LpSourcePreparation
import com.eignex.klause.lp.relaxation.LpSparseRootLayout
import com.eignex.klause.lp.relaxation.RootDomains
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.CONSTRUCTION_BATCH_SIZE
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.CheckedLongOverflowException
import com.eignex.util.numbers.magnitudeBitLength
import kotlin.math.abs

internal const val SPARSE_LP_MAX_WORK = 150_000_000L
internal const val SPARSE_LP_MAX_ALLOCATION = 2L * 1024L * 1024L * 1024L
internal const val SPARSE_LP_MAX_FACTOR_ENTRIES = 2_000_000L
internal const val SPARSE_LP_MAX_FACTOR_WORK = 8_000_000L
internal const val SPARSE_LP_CHECKING_ALLOCATION = 16L * 1024L * 1024L
private const val MAX_DIMENSION = 65_536L
private const val MAX_ENTRIES = 1_000_000L
internal const val SPARSE_LP_MAX_PREPARATION_WORK = 8_000_000L
private const val MAX_AUTOMATIC_FILL_RISK = 8_000_000L

internal class SparseLpAdmission(val metrics: Map<String, Long>, val sourceLayout: LpRelaxation? = null) {
    val admitted: Boolean get() = metrics["Admitted"] == 1L
    val work: Long get() = metrics["PreparationWork"] ?: 0L
}

// Source admission bounds assembly before allocating it. Only compact linear rows are eligible;
// expanded hulls and guarded layouts need their own allocation reservations.
@Suppress("CyclomaticComplexMethod")
internal suspend fun SequenceScope<Unit>.prepareSparseLpAdmission(
    problem: Problem,
    objective: LinearObjective?,
    cancellation: ExecutionControl = ExecutionControl.Never,
    workAllowance: Long = SPARSE_LP_MAX_WORK,
    sourcePreparation: LpSourcePreparation = LpSourcePreparation.ADMITTED,
    automatic: Boolean = false,
    queryWorkAllowance: Long = workAllowance,
): SparseLpAdmission {
    val columns = problem.numIntVars.toLong() + problem.numBoolVars + problem.numRealVars
    val rows = problem.factors.size.toLong()
    var entries = 0L
    var inspected = 0L
    var assemblyWork = 0L
    var sourceFillRisk = 0L
    val preparationAllowance = minOf(SPARSE_LP_MAX_PREPARATION_WORK, workAllowance.coerceAtLeast(0L))
    fun decline(reason: String) = SparseLpAdmission(mapOf("Decline$reason" to 1L,
        "PreparationWork" to maxOf(inspected, assemblyWork), "Automatic" to if (automatic) 1L else 0L,
        "SourceInspectedFillRisk" to sourceFillRisk))
    fun unaffordableSourcePass(): SparseLpAdmission? {
        if (!automatic) return null
        val pass = sparsePassWork(rows, columns, entries, sourceFillRisk)
        val total = 32L * (entries + rows + columns) + pass
        if (total <= queryWorkAllowance) return null
        return SparseLpAdmission(decline("QueryWork").metrics + mapOf(
            "SourceEstimatedPassWork" to pass, "SourceEstimatedPassTotalWork" to total,
            "QueryWorkAllowance" to queryWorkAllowance))
    }
    if ((problem as? BakedProblem)?.factorProjections?.builtInRelaxation == false) return decline("Projection")
    if (columns + rows > MAX_DIMENSION) {
        return decline("Shape")
    }
    if (cancellation()) return decline("Cancelled")
    val cache = if (automatic && sourcePreparation == LpSourcePreparation.ADMITTED && objective != null)
        (problem as? BakedProblem)?.factorProjections?.lpPreparation else null
    if (objective != null) {
        cache?.reusableSparseRoot(problem, objective, preparationAllowance, queryWorkAllowance)?.let {
            if (cancellation()) return decline("Cancelled")
            val metrics = it.metrics + mapOf(
                "PreparationWork" to 1L, "ReusedPreparationWork" to it.preparationWork,
                "SourceEstimatedPassTotalWork" to it.sourceQueryWork, "QueryWorkAllowance" to queryWorkAllowance,
            )
            return if (it.metrics.getValue("EstimatedProbeTotalWork") <= queryWorkAllowance) {
                SparseLpAdmission(metrics, it.relaxation)
            } else {
                SparseLpAdmission(metrics + mapOf("Admitted" to 0L, "DeclineQueryWork" to 1L))
            }
        }
    }
    val optimisticPassWork = sparsePassWork(rows, columns, 0L, 0L)
    if (automatic && optimisticPassWork > queryWorkAllowance) {
        return SparseLpAdmission(decline("QueryWork").metrics + mapOf(
            "SourceEstimatedPassWork" to optimisticPassWork, "QueryWorkAllowance" to queryWorkAllowance))
    }
    if (32L * (columns + rows) > preparationAllowance) return decline("PreparationWork")
    for ((index, factor) in problem.factors.withIndex()) {
        if (cancellation()) return decline("Cancelled")
        inspected++
        if (factor is ExactLinear && factor.strict) return decline("StrictRow")
        val size = when (factor) {
            is Linear -> if (factor.integerConstants != null && factor.op != LinearOp.NE) factor.vars.size
            else return decline("Source")
            is ExactLinear -> if (factor.constants is IntegerConstants && factor.activator == LinearRow.ALWAYS &&
                factor.relation != LinearOp.NE) factor.size else return decline("Source")
            is SymmetryHandling -> 0
            else -> return decline("Source")
        }
        entries += size
        sourceFillRisk += size.toLong() * maxOf(0, size - 1)
        if (automatic && sourceFillRisk > MAX_AUTOMATIC_FILL_RISK) return decline("FillRisk")
        if (entries > MAX_ENTRIES || 32L * (entries + columns + rows) > preparationAllowance) {
            return decline("PreparationWork")
        }
        if ((index + 1) % CONSTRUCTION_BATCH_SIZE == 0) {
            // Additional rows only increase the forecast for this fixed layout shape.
            unaffordableSourcePass()?.let { return it }
            yield(Unit)
        }
    }
    val reservation = sparseLpStorage(rows, columns, entries)
    if (reservation > SPARSE_LP_MAX_ALLOCATION) return decline("Storage")
    unaffordableSourcePass()?.let { return it }
    assemblyWork = 32L * (entries + rows + columns)
    val relaxation = try {
        val relaxer = CpToLpRelaxation(problem, objective, deferred = true,
            reuseSourceTemplates = sourcePreparation != LpSourcePreparation.DISABLED,
            reuseTemplateIndexes = sourcePreparation != LpSourcePreparation.OBJECT,
            reuseTemplateMetadata = sourcePreparation == LpSourcePreparation.ADMITTED,
            earnTemplatePreparation = sourcePreparation == LpSourcePreparation.ADMITTED)
        relaxer.preparation().forEach {
            if (cancellation()) return decline("Cancelled")
            yield(Unit)
        }
        relaxer.build(RootDomains(problem), cancellation = cancellation)
    } catch (_: LpAssemblyCancelled) {
        return decline("Cancelled")
    } catch (_: CheckedLongOverflowException) {
        return decline("ScalarWidth")
    }
    if (cancellation()) return decline("Cancelled")
    val model = relaxation.model
    val estimate = estimateSparseLpAdmission(model, assemblyWork, cancellation, automatic, queryWorkAllowance)
    val reusable = estimate.admitted && problem.numRealVars == 0 && relaxation.persistentEligible &&
        !model.hasContinuous && relaxation.colVarId.all { it >= 0 } &&
        model.extent() * LpNodeOverhead.BUILD <= assemblyWork
    if (cancellation()) return decline("Cancelled")
    if (!reusable) return estimate
    if (cache != null && objective != null) {
        val sourceWork = assemblyWork + sparsePassWork(rows, columns, entries, sourceFillRisk)
        cache.retainSparseRoot(LpSparseRootLayout(problem, objective, relaxation, estimate.metrics, assemblyWork,
            sourceWork))
    }
    return SparseLpAdmission(estimate.metrics, relaxation)
}

// Reserve capped factor storage, CSC, exact authority, temporary maps, transforms and scratch.
// Disconnected blocks share this owner's global workspace and storage caps.
private fun sparseLpStorage(rows: Long, columns: Long, entries: Long): Long =
    minOf(rows * rows * 3L, SPARSE_LP_MAX_FACTOR_ENTRIES) * 256L +
        entries * 512L + (rows + columns) * 4096L + SPARSE_LP_CHECKING_ALLOCATION

// This owner refreshes one monolithic basis even when the source graph has independent blocks.
private fun sparseQueryWork(rows: Long, columns: Long, entries: Long, fillRisk: Long): Long =
    rows * rows * rows + (entries + rows + columns) * minOf(rows, columns) + fillRisk

// Global scans and basis-vector passes span all rows even when factor arithmetic stays within blocks.
private fun sparsePassWork(rows: Long, columns: Long, entries: Long, fillRisk: Long): Long =
    (entries + rows + columns) * rows + fillRisk

@Suppress("LongMethod", "CyclomaticComplexMethod")
internal fun estimateSparseLpAdmission(
    model: LpModel,
    preparationWork: Long = 0L,
    cancellation: ExecutionControl = ExecutionControl.Never,
    automatic: Boolean = false,
    queryWorkAllowance: Long = SPARSE_LP_MAX_WORK,
): SparseLpAdmission {
    fun decline(reason: String) = SparseLpAdmission(mapOf("Decline$reason" to 1L,
        "PreparationWork" to preparationWork, "Automatic" to if (automatic) 1L else 0L))
    val dimensions = model.n.toLong() + model.m
    if (dimensions > MAX_DIMENSION) return decline("Shape")
    val parent = IntArray(dimensions.toInt()) { it }
    val rank = IntArray(parent.size)
    val rowDegree = IntArray(model.m)
    fun root(id: Int): Int {
        var at = id
        while (parent[at] != at) {
            parent[at] = parent[parent[at]]
            at = parent[at]
        }
        return at
    }
    fun union(left: Int, right: Int) {
        var a = root(left)
        var b = root(right)
        if (a == b) return
        if (rank[a] < rank[b]) {
            val swap = a
            a = b
            b = swap
        }
        parent[b] = a
        if (rank[a] == rank[b]) rank[a]++
    }
    var entries = 0L
    var scalarBits = 1
    val integralStorage = !model.hasContinuous
    fun number(value: BigFraction) {
        scalarBits = maxOf(scalarBits, value.num.magnitudeBitLength(), value.den.magnitudeBitLength())
    }
    fun entry(row: Int, column: Int) {
        entries++
        rowDegree[row]++
        union(row, model.m + column)
    }
    for (column in 0 until model.n) {
        if (cancellation()) return decline("Cancelled")
        if (integralStorage) {
            model.forEachInColumn(column) { row, value ->
                scalarBits = maxOf(scalarBits, signedMagnitudeBits(value))
                if (value != 0L) entry(row, column)
            }
        } else {
            model.forEachRationalColumn(column) { row, value ->
                number(value)
                if (!value.isZero) entry(row, column)
            }
        }
        if (entries > MAX_ENTRIES) return decline("Entries")
    }
    if (integralStorage) {
        scalarBits = maxOf(scalarBits, integralVectorBits(model))
    } else {
        for (row in 0 until model.m) number(model.exactRhs(row))
        for (column in 0 until model.numVars) {
            number(model.exactCost(column))
            if (column < model.n) number(model.exactShift(column))
            val bounds = model.exactBounds(column)
            bounds.lower?.let { number(it.number.value) }
            bounds.upper?.let { number(it.number.value) }
        }
        number(model.exactConstant())
        model.exactState?.model?.objective?.let {
            number(it.scale.value)
            number(it.externalConstant.value)
        }
    }
    val componentRows = IntArray(parent.size)
    val componentColumns = IntArray(parent.size)
    for (row in 0 until model.m) componentRows[root(row)]++
    for (column in 0 until model.n) componentColumns[root(model.m + column)]++
    var components = 0L
    var largestRows = 0L
    var largestColumns = 0L
    var componentRowCubes = 0L
    for (id in parent.indices) {
        if (componentRows[id] + componentColumns[id] == 0) continue
        components++
        largestRows = maxOf(largestRows, componentRows[id].toLong())
        largestColumns = maxOf(largestColumns, componentColumns[id].toLong())
        val rows = componentRows[id].toLong()
        componentRowCubes += rows * rows * rows
    }
    val fillRisk = rowDegree.sumOf { it.toLong() * maxOf(0, it - 1) }
    val storage = sparseLpStorage(model.m.toLong(), model.n.toLong(), entries)
    val queryWork = sparseQueryWork(model.m.toLong(), model.n.toLong(), entries, fillRisk)
    // Sparse updates cannot connect these emitted blocks; ordering and recovery still use one owner.
    // Two predicted attempts do not bound repair or subsequent pivots under cooperative funding.
    val factorAttemptForecast = componentRowCubes + model.m.toLong() * model.m
    val factorAttemptWork = minOf(factorAttemptForecast, SPARSE_LP_MAX_FACTOR_WORK)
    val probeWork = 2L * factorAttemptWork +
        sparsePassWork(model.m.toLong(), model.n.toLong(), entries, fillRisk)
    val decline = when {
        scalarBits > 128 -> "ScalarWidth"
        storage > SPARSE_LP_MAX_ALLOCATION -> "Storage"
        preparationWork > SPARSE_LP_MAX_PREPARATION_WORK -> "PreparationWork"
        automatic && fillRisk > MAX_AUTOMATIC_FILL_RISK -> "FillRisk"
        automatic && factorAttemptForecast > SPARSE_LP_MAX_FACTOR_WORK -> "FactorForecast"
        automatic && preparationWork + probeWork > queryWorkAllowance -> "QueryWork"
        else -> null
    }
    return SparseLpAdmission(buildMap {
        put("Rows", model.m.toLong())
        put("Automatic", if (automatic) 1L else 0L)
        put("Columns", model.n.toLong())
        put("Nonzeros", entries)
        put("Components", components)
        put("LargestComponentRows", largestRows)
        put("LargestComponentColumns", largestColumns)
        put("ScalarBits", scalarBits.toLong())
        put("RowIntersectionRisk", fillRisk)
        if (automatic) put("FillRiskLimit", MAX_AUTOMATIC_FILL_RISK)
        put("EstimatedMonolithicQueryWork", queryWork)
        put("EstimatedFactorAttemptWork", factorAttemptWork)
        put("FactorAttemptForecast", factorAttemptForecast)
        put("EstimatedProbeWork", probeWork)
        put("EstimatedProbeTotalWork", preparationWork + probeWork)
        put("QueryWorkAllowance", queryWorkAllowance)
        put("FactorEntryLimit", SPARSE_LP_MAX_FACTOR_ENTRIES)
        put("FactorWorkLimit", SPARSE_LP_MAX_FACTOR_WORK)
        put("ReservedBytes", storage)
        put("CheckingReservedBytes", SPARSE_LP_CHECKING_ALLOCATION)
        put("PreparationWork", preparationWork)
        put("WorkLimit", SPARSE_LP_MAX_WORK)
        put("StorageLimit", SPARSE_LP_MAX_ALLOCATION)
        put("Admitted", if (decline == null) 1L else 0L)
        decline?.let { put("Decline$it", 1L) }
    })
}

private fun integralVectorBits(model: LpModel): Int {
    var bits = signedMagnitudeBits(model.objConstant)
    for (row in 0 until model.m) bits = maxOf(bits, signedMagnitudeBits(model.rhs[row]))
    for (column in 0 until model.numVars) {
        bits = maxOf(bits, signedMagnitudeBits(model.cost[column]))
        if (column < model.n) bits = maxOf(bits, signedMagnitudeBits(model.loShift[column]))
        // Normalized lower sides are zero. Probe upper sides are absent from the exact box.
        if (model.hasUpper[column] && (column >= model.n || !model.probeClampedHi[column])) {
            bits = maxOf(bits, signedMagnitudeBits(model.upper[column]))
        }
    }
    return bits
}

private fun signedMagnitudeBits(value: Long): Int =
    if (value == Long.MIN_VALUE) Long.SIZE_BITS else Long.SIZE_BITS - abs(value).countLeadingZeroBits()
