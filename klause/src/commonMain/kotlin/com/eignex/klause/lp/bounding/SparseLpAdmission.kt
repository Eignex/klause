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
import com.eignex.klause.lp.engine.forEachRationalColumn
import com.eignex.klause.lp.relaxation.CpToLpRelaxation
import com.eignex.klause.lp.relaxation.LpAssemblyCancelled
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.CONSTRUCTION_BATCH_SIZE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.CheckedLongOverflowException
import com.eignex.klause.util.magnitudeBitLength

internal const val SPARSE_LP_MAX_WORK = 150_000_000L
internal const val SPARSE_LP_MAX_ALLOCATION = 2L * 1024L * 1024L * 1024L
internal const val SPARSE_LP_MAX_FACTOR_ENTRIES = 2_000_000L
internal const val SPARSE_LP_MAX_FACTOR_WORK = 8_000_000L
private const val MAX_DIMENSION = 65_536L
private const val MAX_ENTRIES = 1_000_000L
private const val MAX_PREPARATION_WORK = 8_000_000L

internal class SparseLpAdmission(val metrics: Map<String, Long>) {
    val admitted: Boolean get() = metrics["Admitted"] == 1L
    val work: Long get() = metrics["PreparationWork"] ?: 0L
}

// Source admission bounds assembly before allocating it. Only compact linear rows are eligible;
// expanded hulls and guarded layouts need their own allocation reservations.
@Suppress("CyclomaticComplexMethod")
internal suspend fun SequenceScope<Unit>.prepareSparseLpAdmission(
    problem: Problem,
    objective: LinearObjective?,
    cancellation: Cancellation = Cancellation.Never,
): SparseLpAdmission {
    val columns = problem.numIntVars.toLong() + problem.numBoolVars + problem.numRealVars
    val rows = problem.factors.size.toLong()
    var entries = 0L
    fun decline(reason: String) = SparseLpAdmission(mapOf("Decline$reason" to 1L,
        "PreparationWork" to (entries + rows + columns)))
    if (columns + rows > MAX_DIMENSION) {
        return decline("Shape")
    }
    for ((index, factor) in problem.factors.withIndex()) {
        if (cancellation()) return decline("Cancelled")
        entries += when (factor) {
            is Linear -> if (factor.integerConstants != null && factor.op != LinearOp.NE) factor.vars.size
                else return decline("Source")
            is ExactLinear -> if (factor.constants is IntegerConstants && factor.activator == LinearRow.ALWAYS &&
                factor.relation != LinearOp.NE) factor.size else return decline("Source")
            is SymmetryHandling -> 0
            else -> return decline("Source")
        }
        if (entries > MAX_ENTRIES || 32L * (entries + columns + rows) > MAX_PREPARATION_WORK) {
            return decline("PreparationWork")
        }
        if ((index + 1) % CONSTRUCTION_BATCH_SIZE == 0) yield(Unit)
    }
    val reservation = sparseLpStorage(rows, columns, entries)
    if (reservation > SPARSE_LP_MAX_ALLOCATION) return decline("Storage")
    val relaxer = CpToLpRelaxation(problem, objective, deferred = true)
    relaxer.preparation().forEach { yield(Unit) }
    val model = try {
        relaxer.build(PropagationSession(problem), cancellation = cancellation).model
    } catch (_: LpAssemblyCancelled) {
        return decline("Cancelled")
    } catch (_: CheckedLongOverflowException) {
        return decline("ScalarWidth")
    }
    if (cancellation()) return decline("Cancelled")
    return estimateSparseLpAdmission(model, 32L * (entries + rows + columns), cancellation)
}

// Reserve capped factor storage, CSC, exact authority, temporary maps, transforms and scratch.
// Component counts inform diagnosis; this owner remains monolithic.
private fun sparseLpStorage(rows: Long, columns: Long, entries: Long): Long =
    minOf(rows * rows * 3L, SPARSE_LP_MAX_FACTOR_ENTRIES) * 256L +
        entries * 512L + (rows + columns) * 4096L

@Suppress("LongMethod", "CyclomaticComplexMethod")
internal fun estimateSparseLpAdmission(
    model: LpModel,
    preparationWork: Long = 0L,
    cancellation: Cancellation = Cancellation.Never,
): SparseLpAdmission {
    val dimensions = model.n.toLong() + model.m
    if (dimensions > MAX_DIMENSION) return SparseLpAdmission(mapOf("DeclineShape" to 1L))
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
        if (rank[a] < rank[b]) { val swap = a; a = b; b = swap }
        parent[b] = a
        if (rank[a] == rank[b]) rank[a]++
    }
    var entries = 0L
    var scalarBits = 1
    fun number(value: BigFraction) {
        scalarBits = maxOf(scalarBits, value.num.magnitudeBitLength(), value.den.magnitudeBitLength())
    }
    for (column in 0 until model.n) {
        if (cancellation()) return SparseLpAdmission(mapOf("DeclineCancelled" to 1L,
            "PreparationWork" to preparationWork))
        model.forEachRationalColumn(column) { row, value ->
            number(value)
            if (!value.isZero) {
                entries++
                rowDegree[row]++
                union(row, model.m + column)
            }
        }
        if (entries > MAX_ENTRIES) return SparseLpAdmission(mapOf("DeclineEntries" to 1L))
    }
    for (row in 0 until model.m) number(model.exactRhs(row))
    for (column in 0 until model.numVars) {
        number(model.exactCost(column))
        val bounds = model.exactBounds(column)
        bounds.lower?.let { number(it.number.value) }
        bounds.upper?.let { number(it.number.value) }
    }
    number(model.exactConstant())
    val componentRows = IntArray(parent.size)
    val componentColumns = IntArray(parent.size)
    for (row in 0 until model.m) componentRows[root(row)]++
    for (column in 0 until model.n) componentColumns[root(model.m + column)]++
    var components = 0L
    var largestRows = 0L
    var largestColumns = 0L
    for (id in parent.indices) {
        if (componentRows[id] + componentColumns[id] == 0) continue
        components++
        largestRows = maxOf(largestRows, componentRows[id].toLong())
        largestColumns = maxOf(largestColumns, componentColumns[id].toLong())
    }
    val fillRisk = rowDegree.sumOf { it.toLong() * maxOf(0, it - 1) }
    val storage = sparseLpStorage(model.m.toLong(), model.n.toLong(), entries)
    val decline = when {
        scalarBits > 128 -> "ScalarWidth"
        storage > SPARSE_LP_MAX_ALLOCATION -> "Storage"
        preparationWork > MAX_PREPARATION_WORK -> "PreparationWork"
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
        put("FactorEntryLimit", SPARSE_LP_MAX_FACTOR_ENTRIES)
        put("FactorWorkLimit", SPARSE_LP_MAX_FACTOR_WORK)
        put("ReservedBytes", storage)
        put("PreparationWork", preparationWork)
        put("WorkLimit", SPARSE_LP_MAX_WORK)
        put("StorageLimit", SPARSE_LP_MAX_ALLOCATION)
        put("Admitted", if (decline == null) 1L else 0L)
        decline?.let { put("Decline$it", 1L) }
    })
}
