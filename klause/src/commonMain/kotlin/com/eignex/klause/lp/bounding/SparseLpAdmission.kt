package com.eignex.klause.lp.bounding

import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.symmetry.SymmetryHandling
import com.eignex.klause.ir.IntegerConstants
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.Term
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
import com.eignex.klause.lp.relaxation.RootDomains
import com.eignex.klause.lp.rootDomainOf
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.CONSTRUCTION_BATCH_SIZE
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.collections.IntDisjointSet
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.CheckedLongOverflowException
import com.eignex.util.numbers.addExact
import com.eignex.util.numbers.magnitudeBitLength
import com.eignex.util.numbers.mulExact

internal const val SPARSE_LP_MAX_WORK = 150_000_000L
internal const val SPARSE_LP_MAX_ALLOCATION = 2L * 1024L * 1024L * 1024L
internal const val SPARSE_LP_MAX_FACTOR_ENTRIES = 2_000_000L
internal const val SPARSE_LP_MAX_FACTOR_WORK = 8_000_000L
internal const val SPARSE_LP_CHECKING_ALLOCATION = 16L * 1024L * 1024L
private const val MAX_DIMENSION = 65_536L
private const val MAX_ENTRIES = 1_000_000L
internal const val SPARSE_LP_MAX_PREPARATION_WORK = 8_000_000L

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
): SparseLpAdmission {
    val columns = problem.numIntVars.toLong() + problem.numBoolVars + problem.numRealVars
    val rows = problem.factors.size.toLong()
    var entries = 0L
    var inspected = 0L
    var assemblyWork = 0L
    val preparationAllowance = minOf(SPARSE_LP_MAX_PREPARATION_WORK, workAllowance.coerceAtLeast(0L))
    fun decline(reason: String) = SparseLpAdmission(mapOf("Decline$reason" to 1L,
        "PreparationWork" to maxOf(inspected, assemblyWork)))
    if (automatic && (objective == null || problem.numRealVars != 0 || problem.intBounds.hasOpenSides)) {
        return decline("Source")
    }
    if ((problem as? BakedProblem)?.factorProjections?.builtInRelaxation == false) return decline("Projection")
    if (columns + rows > MAX_DIMENSION) {
        return decline("Shape")
    }
    if (cancellation()) return decline("Cancelled")
    if (32L * (columns + rows) > preparationAllowance) return decline("PreparationWork")
    for ((index, factor) in problem.factors.withIndex()) {
        if (cancellation()) return decline("Cancelled")
        inspected++
        if (factor is ExactLinear && factor.strict) return decline("StrictRow")
        entries += when (factor) {
            is Linear -> if (factor.integerConstants != null && factor.op != LinearOp.NE) factor.vars.size
            else return decline("Source")
            is ExactLinear -> if (factor.constants is IntegerConstants && factor.activator == LinearRow.ALWAYS &&
                factor.relation != LinearOp.NE) factor.size else return decline("Source")
            is SymmetryHandling -> 0
            else -> return decline("Source")
        }
        if (entries > MAX_ENTRIES || 32L * (entries + columns + rows) > preparationAllowance) {
            return decline("PreparationWork")
        }
        if ((index + 1) % CONSTRUCTION_BATCH_SIZE == 0) yield(Unit)
    }
    val reservation = sparseLpStorage(rows, columns, entries)
    if (reservation > SPARSE_LP_MAX_ALLOCATION) return decline("Storage")
    val plannedAssemblyWork = 32L * (entries + rows + columns)
    if (automatic) {
        if (objective != null && objectiveCornerSatisfiesSource(problem, objective, cancellation,
                preparationAllowance - inspected) { inspected += it }) return decline("ObjectiveCorner")
        declineSparseSourceWork(problem, plannedAssemblyWork, workAllowance, cancellation) { inspected += it }
            ?.let { return SparseLpAdmission(it.metrics + ("PreparationWork" to inspected)) }
    }
    assemblyWork = plannedAssemblyWork + inspected
    if (assemblyWork > preparationAllowance) return decline("PreparationWork")
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
    val estimate = estimateSparseLpAdmission(model, assemblyWork, cancellation, workAllowance, automatic)
    val reusable = estimate.admitted && problem.numRealVars == 0 && relaxation.persistentEligible &&
        !model.hasContinuous && relaxation.colVarId.all { it >= 0 } &&
        model.extent() * LpNodeOverhead.BUILD <= assemblyWork
    if (cancellation()) return decline("Cancelled")
    return if (reusable) SparseLpAdmission(estimate.metrics, relaxation) else estimate
}

@Suppress("LongMethod", "CyclomaticComplexMethod", "ReturnCount")
private suspend fun SequenceScope<Unit>.objectiveCornerSatisfiesSource(
    problem: Problem,
    objective: LinearObjective,
    cancellation: ExecutionControl,
    allowance: Long,
    observeWork: (Long) -> Unit,
): Boolean {
    if (problem.numBoolVars != 0) return false
    var work = problem.numIntVars.toLong()
    try {
        if (work > allowance) return false
        val corner = LongArray(problem.numIntVars)
        for (column in corner.indices) {
            if (column % CONSTRUCTION_BATCH_SIZE == 0) {
                if (cancellation()) return false
                yield(Unit)
            }
            val domain = problem.rootDomainOf(column)
            corner[column] = if (objective.intCoefficientValues.getOrElse(column) { 0L } < 0L) domain.max
            else domain.min
        }
        for ((index, factor) in problem.factors.withIndex()) {
            work++
            if (work > allowance || cancellation()) return false
            if (index % CONSTRUCTION_BATCH_SIZE == 0) yield(Unit)
            val compact = factor as? Linear
            val exact = factor as? ExactLinear
            if (compact == null && exact == null) return false
            val size = compact?.vars?.size ?: checkNotNull(exact).size
            val constants = compact?.integerConstants ?: (checkNotNull(exact).constants as? IntegerConstants)
                ?: return false
            val relation = compact?.op ?: checkNotNull(exact).relation
            var sum = 0L
            for (k in 0 until size) {
                work += 4L
                if (work > allowance || cancellation()) return false
                if (k % CONSTRUCTION_BATCH_SIZE == 0) yield(Unit)
                val column = if (compact != null) compact.vars[k] else {
                    val ref = checkNotNull(exact).ref(k)
                    if (!Term.isInt(ref)) return false
                    Term.intVar(ref)
                }
                sum = addExact(sum, mulExact(constants.coeff(k), corner[column]))
            }
            val satisfied = when (relation) {
                LinearOp.LE -> sum <= constants.bound
                LinearOp.EQ -> sum == constants.bound
                LinearOp.GE -> sum >= constants.bound
                LinearOp.NE -> false
            }
            if (!satisfied) return false
        }
        return !cancellation()
    } catch (_: CheckedLongOverflowException) {
        return false
    } finally {
        observeWork(work)
    }
}

@Suppress("LongMethod", "CyclomaticComplexMethod", "ReturnCount")
private fun declineSparseSourceWork(
    problem: Problem,
    preparationWork: Long,
    allowance: Long,
    cancellation: ExecutionControl,
    observeWork: (Long) -> Unit,
): SparseLpAdmission? {
    val columns = problem.numIntVars
    if (columns == 0 || problem.intBounds.hasOpenSides) return null
    val rows = problem.factors.size
    var work = 5L * columns + rows
    fun cancelled() = SparseLpAdmission(mapOf("DeclineCancelled" to 1L))
    try {
        val partition = IntDisjointSet(columns)
        val fixed = BooleanArray(columns)
        val used = BooleanArray(columns)
        val marks = IntArray(columns) { -1 }
        val firstColumn = IntArray(rows) { -1 }
        for (column in 0 until columns) {
            if (column % CONSTRUCTION_BATCH_SIZE == 0 && cancellation()) return cancelled()
            work++
            val domain = problem.rootDomainOf(column)
            fixed[column] = domain.min == domain.max
        }
        var nonzeros = 0L
        var activeRows = 0L
        var fillRisk = 0L
        for ((index, factor) in problem.factors.withIndex()) {
            if (cancellation()) return cancelled()
            work++
            val compact = factor as? Linear
            val exact = factor as? ExactLinear
            if (compact == null && exact == null) continue
            val size = if (compact != null) compact.vars.size else checkNotNull(exact).size
            val constants = if (compact != null) checkNotNull(compact.integerConstants)
            else checkNotNull(exact).constants as IntegerConstants
            var first = -1
            var degree = 0L
            for (k in 0 until size) {
                if (k % CONSTRUCTION_BATCH_SIZE == 0 && cancellation()) return cancelled()
                work++
                if (constants.coeff(k) == 0L) continue
                val column = if (compact != null) compact.vars[k] else Term.intVar(checkNotNull(exact).ref(k))
                if (fixed[column]) continue
                // Coalescing can erase repeated memberships; emitted admission owns that shape.
                if (marks[column] == index) return null
                marks[column] = index
                if (first < 0) first = column else {
                    used[first] = true
                    used[column] = true
                    partition.union(first, column)
                    work += 3L
                }
                degree++
            }
            // Unary rows can be discharged by declared bounds without entering the LP.
            if (degree < 2L) continue
            firstColumn[index] = first
            activeRows++
            nonzeros += degree
            fillRisk += degree * (degree - 1L)
        }
        work += 2L * columns
        val componentRows = IntArray(columns)
        val componentColumns = IntArray(columns)
        for (column in 0 until columns) {
            if (column % CONSTRUCTION_BATCH_SIZE == 0 && cancellation()) return cancelled()
            work++
            if (used[column]) {
                componentColumns[partition.find(column)]++
                work += 3L
            }
        }
        for (row in firstColumn.indices) {
            if (row % CONSTRUCTION_BATCH_SIZE == 0 && cancellation()) return cancelled()
            work++
            if (firstColumn[row] >= 0) {
                componentRows[partition.find(firstColumn[row])]++
                work += 3L
            }
        }
        var components = 0L
        var activeColumns = 0L
        var largestRows = 0L
        var largestColumns = 0L
        var factorWork = 0L
        for (column in 0 until columns) {
            if (column % CONSTRUCTION_BATCH_SIZE == 0 && cancellation()) return cancelled()
            work++
            if (componentRows[column] == 0) continue
            components++
            activeColumns += componentColumns[column]
            largestRows = maxOf(largestRows, componentRows[column].toLong())
            largestColumns = maxOf(largestColumns, componentColumns[column].toLong())
            val width = minOf(componentRows[column], componentColumns[column]).toLong()
            factorWork += width * width * width
        }
        val queryWork = fillRisk + factorWork +
            (nonzeros + activeRows + activeColumns) * minOf(largestRows, largestColumns)
        val totalWork = preparationWork + 2L * queryWork
        if (totalWork <= allowance) return null
        return SparseLpAdmission(mapOf("DeclineQueryWork" to 1L, "Automatic" to 1L,
            "SourcePreflight" to 1L, "SourceRows" to activeRows, "SourceColumns" to activeColumns,
            "SourceNonzeros" to nonzeros, "SourceComponents" to components,
            "SourceLargestComponentRows" to largestRows, "SourceLargestComponentColumns" to largestColumns,
            "SourceRowIntersectionRisk" to fillRisk, "SourceEstimatedFactorWork" to factorWork,
            "SourceEstimatedQueryWork" to queryWork,
            "SourceEstimatedTotalWork" to totalWork, "QueryWorkAllowance" to allowance.coerceAtLeast(0L)))
    } finally {
        observeWork(work)
    }
}

// Reserve capped factor storage, CSC, exact authority, temporary maps, transforms and scratch.
// Component counts inform diagnosis; this owner remains monolithic.
private fun sparseLpStorage(rows: Long, columns: Long, entries: Long): Long =
    minOf(rows * rows * 3L, SPARSE_LP_MAX_FACTOR_ENTRIES) * 256L +
        entries * 512L + (rows + columns) * 4096L + SPARSE_LP_CHECKING_ALLOCATION

@Suppress("LongMethod", "CyclomaticComplexMethod")
internal fun estimateSparseLpAdmission(
    model: LpModel,
    preparationWork: Long = 0L,
    cancellation: ExecutionControl = ExecutionControl.Never,
    workAllowance: Long = SPARSE_LP_MAX_WORK,
    automatic: Boolean = false,
): SparseLpAdmission {
    fun decline(reason: String) = SparseLpAdmission(mapOf("Decline$reason" to 1L,
        "PreparationWork" to preparationWork))
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
    fun number(value: BigFraction) {
        scalarBits = maxOf(scalarBits, value.num.magnitudeBitLength(), value.den.magnitudeBitLength())
    }
    for (column in 0 until model.n) {
        if (cancellation()) return decline("Cancelled")
        model.forEachRationalColumn(column) { row, value ->
            number(value)
            if (!value.isZero) {
                entries++
                rowDegree[row]++
                union(row, model.m + column)
            }
        }
        if (entries > MAX_ENTRIES) return decline("Entries")
    }
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
    val componentRows = IntArray(parent.size)
    val componentColumns = IntArray(parent.size)
    for (row in 0 until model.m) componentRows[root(row)]++
    for (column in 0 until model.n) componentColumns[root(model.m + column)]++
    var components = 0L
    var largestRows = 0L
    var largestColumns = 0L
    var factorWork = 0L
    for (id in parent.indices) {
        if (componentRows[id] + componentColumns[id] == 0) continue
        components++
        largestRows = maxOf(largestRows, componentRows[id].toLong())
        largestColumns = maxOf(largestColumns, componentColumns[id].toLong())
        val width = minOf(componentRows[id], componentColumns[id]).toLong()
        factorWork += width * width * width
    }
    val fillRisk = rowDegree.sumOf { it.toLong() * maxOf(0, it - 1) }
    val storage = sparseLpStorage(model.m.toLong(), model.n.toLong(), entries)
    // A query must also afford a dense basis refresh inside each connected block.
    val queryWork = fillRisk + factorWork + (entries + dimensions) * minOf(largestRows, largestColumns)
    // Root optimization and its downstream query share one source assembly and one admission grant.
    val totalWork = preparationWork + 2L * queryWork
    val decline = when {
        scalarBits > 128 -> "ScalarWidth"
        storage > SPARSE_LP_MAX_ALLOCATION -> "Storage"
        preparationWork > SPARSE_LP_MAX_PREPARATION_WORK -> "PreparationWork"
        automatic && totalWork > workAllowance -> "QueryWork"
        else -> null
    }
    return SparseLpAdmission(buildMap {
        put("Rows", model.m.toLong())
        put("Columns", model.n.toLong())
        put("Nonzeros", entries)
        put("Components", components)
        put("LargestComponentRows", largestRows)
        put("LargestComponentColumns", largestColumns)
        put("ScalarBits", scalarBits.toLong())
        put("RowIntersectionRisk", fillRisk)
        put("EstimatedFactorWork", factorWork)
        put("EstimatedQueryWork", queryWork)
        put("EstimatedContinuationWork", queryWork)
        put("EstimatedTotalWork", totalWork)
        put("QueryWorkAllowance", workAllowance.coerceAtLeast(0L))
        put("Automatic", if (automatic) 1L else 0L)
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
