package com.eignex.klause.lp.engine

import com.eignex.klause.util.Cancellation

internal data class LpMatrixProjectionStatus(val underflows: Int, val overflows: Int)

internal class LpProjectionStop : RuntimeException()

internal class LpProjectionMeter(
    private val workLimit: Long = Long.MAX_VALUE,
    private val allocationLimit: Long = Long.MAX_VALUE,
    private val cancellation: Cancellation = Cancellation.Never,
) {
    var matrixWork: Long = 0L
        private set
    var vectorWork: Long = 0L
        private set
    var allocation: Long = 0L
        private set
    val work: Long get() = matrixWork + vectorWork

    init {
        require(workLimit >= 0L && allocationLimit >= 0L)
    }

    fun poll() {
        if (cancellation()) throw LpProjectionStop()
    }

    fun reserveVectors(model: ExactLpModel, materialize: Boolean = true) {
        val vectors = model.m.toLong() + model.numVars + model.n
        reserve(vectors * 4L + 1L, if (materialize) vectors * 64L + 512L else 0L, matrix = false)
    }

    fun reserve(work: Long, bytes: Long, matrix: Boolean) {
        poll()
        if (work < 0L || bytes < 0L || work > workLimit - this.work || bytes > allocationLimit - allocation) {
            throw LpProjectionStop()
        }
        if (matrix) matrixWork += work else vectorWork += work
        allocation += bytes
    }
}

internal data class LpBoundAssertion(
    val column: Int,
    val upper: Boolean,
    val side: ExactLpSide,
    val witness: Long,
    val depth: Int,
)

internal data class LpBoundConflict(val column: Int, val lower: LpBoundAssertion, val upper: LpBoundAssertion)

internal class LpExactState internal constructor(
    val baseModel: ExactLpModel,
    assertions: List<LpBoundAssertion> = emptyList(),
    scopes: List<Int> = emptyList(),
    val matrixRevision: Long = 0L,
    val boundRevision: Long = 0L,
    val objectiveRevision: Long = 0L,
    val popRevision: Long = 0L,
    changedColumns: List<Int> = emptyList(),
    val rows: LpScopedRows = LpScopedRows.initial(baseModel.m),
    val rowRevision: Long = 0L,
    /**
     * The state this one follows on the trail. When it shares this state's base model and rows, only the columns
     * whose assertions differ are rebuilt and the rest are taken from it: a search push otherwise rebuilds every
     * column and rechecks every assertion, which on a large model costs more than the node it is for.
     */
    previous: LpExactState? = null,
) {
    private val activeAssertions = assertions.toList()
    private val scopeMarks = scopes.toList()
    private val changed = changedColumns.toList()
    private val lower: Array<LpBoundAssertion?>
    private val upper: Array<LpBoundAssertion?>
    private val inconsistent: BooleanArray
    private var projection: LpMatrixProjection? = null
    private var projectionAttempted = false
    private var vectors: LpVectorProjection? = null
    private var ownerView: LpModel? = null
    private var inheritedVectors: LpVectorProjection? = null
    private var projectionColumns = intArrayOf()
    private var projectionCosts = intArrayOf()
    private var projectionObjective = false

    // Whether every right-hand side, cost, bound and origin projects to a finite double; null until checked.
    private var scalarsProjectable: Boolean? = null

    val assertions: List<LpBoundAssertion> get() = activeAssertions.toList()
    val scopes: List<Int> get() = scopeMarks.toList()
    val changedColumns: List<Int> get() = changed.toList()
    val depth: Int get() = scopeMarks.size
    val model: ExactLpModel
    val conflict: LpBoundConflict?
    val trailStorageUnits: Long

    init {
        require(listOf(matrixRevision, boundRevision, objectiveRevision, popRevision, rowRevision).all { it >= 0L })
        require(rows.size == baseModel.m)
        require(scopeMarks.all { it in 0..activeAssertions.size })
        require(scopeMarks.zipWithNext().all { (a, b) -> a <= b })
        require(changed.all { it in 0 until baseModel.numVars } && changed.distinct().size == changed.size)
        val source = previous?.takeIf { it.baseModel.sharesRegion(baseModel) && it.rows === rows }
        if (source == null) {
            trailStorageUnits = activeAssertions.sumOf { it.storageUnits() } + scopeMarks.size
            validateRows()
            validateAssertions(0)
            lower = arrayOfNulls(baseModel.numVars)
            upper = arrayOfNulls(baseModel.numVars)
            for (j in 0 until baseModel.numVars) seedDeclared(j)
            for (assertion in activeAssertions) tighten(assertion)
            model = fullModel()
            inconsistent = BooleanArray(model.numVars) { !model.column(it).bounds.consistent }
        } else {
            if (source.baseModel.objective !== baseModel.objective) validateRowCosts()
            val shared = sharedPrefix(source.activeAssertions)
            var storage = source.trailStorageUnits - source.scopeMarks.size + scopeMarks.size
            for (index in shared until source.activeAssertions.size) storage -= source.activeAssertions[index].storageUnits()
            for (index in shared until activeAssertions.size) storage += activeAssertions[index].storageUnits()
            trailStorageUnits = storage
            validateAssertions(shared)
            val touched = HashSet<Int>()
            for (index in shared until source.activeAssertions.size) touched.add(source.activeAssertions[index].column)
            for (index in shared until activeAssertions.size) touched.add(activeAssertions[index].column)
            lower = if (touched.isEmpty()) source.lower else source.lower.copyOf()
            upper = if (touched.isEmpty()) source.upper else source.upper.copyOf()
            for (j in touched) seedDeclared(j)
            if (touched.isNotEmpty()) {
                for (assertion in activeAssertions) if (assertion.column in touched) tighten(assertion)
            }
            val region = if (touched.isEmpty()) {
                source.model
            } else {
                source.model.withBoundColumns(
                    List(baseModel.numVars) { if (it in touched) column(it) else source.model.column(it) },
                )
            }
            model = if (region.objective === baseModel.objective) region else region.withObjective(baseModel.objective)
            inconsistent = if (touched.isEmpty()) source.inconsistent else source.inconsistent.copyOf()
            for (j in touched) inconsistent[j] = !model.column(j).bounds.consistent
            deriveVectors(source, touched.toIntArray())
        }
        conflict = inconsistent.indexOfFirst { it }.takeIf { it >= 0 }?.let {
            LpBoundConflict(it, requireNotNull(lower[it]), requireNotNull(upper[it]))
        }
    }

    private fun validateRows() {
        require(rows.entries().all { !it.active || it.depth == null || it.depth <= depth })
        require(rows.entries().all { it.suspendedAt == null || it.suspendedAt <= depth })
        validateRowCosts()
    }

    private fun validateRowCosts() {
        require(
            (0 until rows.size).all { rows.row(it).active || baseModel.objective.cost(baseModel.n + it).value.isZero },
        )
    }

    // Checks the assertions from [from] on; the ones before it were checked when the state that shares them was built.
    private fun validateAssertions(from: Int) {
        val witnesses = HashSet<Long>()
        for (index in from until activeAssertions.size) {
            val assertion = activeAssertions[index]
            require(assertion.column in 0 until baseModel.numVars && assertion.witness >= 0L)
            require(assertion.column < baseModel.n || rows.row(assertion.column - baseModel.n).active)
            require(
                assertion.column < baseModel.n ||
                    assertion.depth >= (rows.row(assertion.column - baseModel.n).depth ?: 0),
            )
            require(assertion.depth == scopeMarks.count { it <= index })
            require(witnesses.add(assertion.witness))
        }
        if (from > 0 && witnesses.isNotEmpty()) {
            for (index in 0 until from) require(activeAssertions[index].witness !in witnesses)
        }
    }

    private fun sharedPrefix(before: List<LpBoundAssertion>): Int {
        val limit = minOf(before.size, activeAssertions.size)
        var k = 0
        while (k < limit && before[k] === activeAssertions[k]) k++
        return k
    }

    private fun seedDeclared(j: Int) {
        lower[j] = null
        upper[j] = null
        if (j >= baseModel.n && !rows.row(j - baseModel.n).active) return
        val declared = baseModel.column(j).bounds
        val bounds = if (j >= baseModel.n && baseModel.row(j - baseModel.n).strict &&
            declared.lower?.number?.value?.isZero == true
        ) {
            declared.copy(lower = declared.lower.copy(strict = true))
        } else {
            declared
        }
        lower[j] = bounds.lower?.let { LpBoundAssertion(j, false, it, -2L * j - 1L, 0) }
        upper[j] = bounds.upper?.let { LpBoundAssertion(j, true, it, -2L * j - 2L, 0) }
    }

    private fun tighten(assertion: LpBoundAssertion) {
        val sides = if (assertion.upper) upper else lower
        val previous = sides[assertion.column]
        if (previous == null || assertion.side.strongerThan(previous.side, assertion.upper)) {
            sides[assertion.column] = assertion
        }
    }

    private fun column(j: Int): ExactLpColumn {
        val original = baseModel.column(j)
        val lowerSide = lower[j]?.side
        val upperSide = upper[j]?.side
        val integral = original.integral && (j < baseModel.n || rows.row(j - baseModel.n).active)
        return if (lowerSide == original.bounds.lower && upperSide == original.bounds.upper &&
            integral == original.integral
        ) {
            original
        } else {
            original.copy(bounds = ExactLpBounds(lowerSide, upperSide), integral = integral)
        }
    }

    private fun fullModel(): ExactLpModel {
        val columns = List(baseModel.numVars) { column(it) }
        return if (rows.activeCount == rows.size) {
            baseModel.copy(columns = columns)
        } else {
            baseModel.copy(
                columns = columns,
                rows = List(baseModel.m) {
                    val row = baseModel.row(it)
                    if (rows.row(it).active || !row.strict) row else row.copy(strict = false)
                },
            )
        }
    }

    private fun boundsProject(j: Int): Boolean {
        val bounds = model.column(j).bounds
        return (bounds.lower == null || bounds.lower.number.project() != null) &&
            (bounds.upper == null || bounds.upper.number.project() != null)
    }

    private fun objectiveProjects(): Boolean = model.objective.constant.project() != null &&
        model.objective.scale.project(nonzeroRequired = true) != null && model.objective.externalConstant.project() != null

    fun activeSide(column: Int, upper: Boolean): LpBoundAssertion? = if (upper) this.upper[column] else lower[column]

    fun sameMatrix(other: LpExactState): Boolean = matrixRevision == other.matrixRevision &&
        baseModel.sameMatrix(other.baseModel) && rows.sameIdentities(other.rows)

    fun fullAuthorityEquals(other: LpExactState): Boolean = baseModel.sameAuthority(other.baseModel) &&
        model.sameAuthority(other.model) && activeAssertions == other.activeAssertions &&
        scopeMarks == other.scopeMarks &&
        matrixRevision == other.matrixRevision && boundRevision == other.boundRevision &&
        objectiveRevision == other.objectiveRevision && popRevision == other.popRevision &&
        rowRevision == other.rowRevision && rows.sameAuthority(other.rows)

    internal fun inheritProjection(previous: LpExactState) {
        projection = previous.projection
        projectionAttempted = previous.projectionAttempted
    }

    internal fun inheritScalars(previous: LpExactState) {
        if (vectors != null || inheritedVectors != null || model.n != previous.model.n || model.m != previous.model.m) {
            return
        }
        for (i in 0 until model.m) {
            if (model.rhs(i) != previous.model.rhs(i) || model.row(i) != previous.model.row(i)) return
        }
        for (j in 0 until model.numVars) {
            val column = model.column(j)
            val old = previous.model.column(j)
            if (column.origin != old.origin || column.tag != old.tag || column.integral != old.integral) return
        }
        deriveVectors(previous, (0 until model.numVars).filter {
            model.column(it).bounds != previous.model.column(it).bounds
        }.toIntArray())
    }

    private fun deriveVectors(previous: LpExactState, columns: IntArray) {
        projectionObjective = previous.model.objective !== model.objective
        projectionCosts = if (projectionObjective) {
            (0 until model.numVars).filter {
                previous.model.objective.cost(it) != model.objective.cost(it)
            }.toIntArray()
        } else {
            intArrayOf()
        }
        if (columns.isEmpty() && !projectionObjective) {
            vectors = previous.vectors
        } else {
            inheritedVectors = previous.vectors
            projectionColumns = columns
        }
        if (previous.scalarsProjectable == true) {
            scalarsProjectable = columns.all { boundsProject(it) } &&
                projectionCosts.all { model.objective.cost(it).project(nonzeroRequired = true) != null } &&
                (!projectionObjective || objectiveProjects())
        }
    }

    val matrixProjectionStatus: LpMatrixProjectionStatus? get() = projection?.status
    val matrixProjectionDeclined: Boolean get() = projectionAttempted && projection == null
    fun projectionLostNonzero(working: LpModel): Boolean? = vectors?.lostNonzero(working)

    fun toWorkingModel(meter: LpProjectionMeter = LpProjectionMeter()): LpModel? = try {
        projectWorkingModel(meter, copyVectors = true)
    } catch (_: LpProjectionStop) {
        null
    }

    // Numerical owners only read projected inputs. Caller-owned views copy the vectors because their arrays
    // may be edited independently of source authority and of any retained owner.
    fun ownerWorkingModel(meter: LpProjectionMeter = LpProjectionMeter()): LpModel? = try {
        ownerView?.let {
            meter.reserve(1L, 0L, matrix = false)
            it
        } ?: projectWorkingModel(meter, copyVectors = false)?.also { ownerView = it }
    } catch (_: LpProjectionStop) {
        null
    }

    fun canProjectWorkingModel(cancellation: Cancellation = Cancellation.Never): Boolean = try {
        val meter = LpProjectionMeter(cancellation = cancellation)
        ensureMatrixProjection(meter) && run {
            meter.reserveVectors(model, materialize = false)
            scalarsProjectable ?: (projectScalars(meter) != null).also { scalarsProjectable = it }
        }
    } catch (_: LpProjectionStop) {
        false
    }

    private fun projectWorkingModel(meter: LpProjectionMeter, copyVectors: Boolean): LpModel? {
        if (!ensureMatrixProjection(meter)) return null
        val matrix = projection ?: return null
        val projected = projectVectors(meter) ?: return null
        val scalars = if (copyVectors) projected.ownedCopy(model, meter) else projected
        val layout = scalars.layout
        meter.reserve(1L, 512L, matrix = false)
        return LpModel(
            n = model.n,
            m = model.m,
            csc = matrix.csc,
            rhs = layout.rhs,
            cost = layout.cost,
            upper = layout.upper,
            hasUpper = scalars.hasUpper,
            loShift = layout.origins,
            objConstant = 0L,
            sense = model.objective.sense,
            tag = layout.tag,
            rowGlobal = layout.rowGlobal,
            rowStrict = layout.rowStrict,
            rowPremises = layout.rowPremises,
            probeClampedLo = layout.probeClampedLo,
            probeClampedHi = layout.probeClampedHi,
            colContinuous = layout.continuous,
            doubleView = LpDoubleView(
                matrix.colPtr, matrix.rowIdx, matrix.values, scalars.rhs, scalars.cost,
                scalars.upper, scalars.hasUpper, scalars.constant, scalars.origins,
            ),
            exactState = this,
        )
    }

    private fun projectVectors(meter: LpProjectionMeter): LpVectorProjection? {
        meter.poll()
        vectors?.let { return it }
        inheritedVectors?.let { previous ->
            val bounded = if (projectionColumns.isEmpty()) previous else {
                previous.withBounds(model, projectionColumns, meter) ?: return null
            }
            val next = if (projectionObjective) bounded.withObjective(model, projectionCosts, meter) ?: return null
                else bounded
            meter.poll()
            vectors = next
            scalarsProjectable = true
            inheritedVectors = null
            return next
        }
        meter.reserveVectors(model)
        val rhs = DoubleArray(model.m)
        val costs = DoubleArray(model.numVars)
        val uppers = DoubleArray(model.numVars)
        val hasUpper = BooleanArray(model.numVars)
        val origins = DoubleArray(model.n)
        val constant = projectScalars(meter, rhs, costs, uppers, hasUpper, origins) ?: return null
        val nonzero = LpProjectionNonzero(model)
        meter.poll()
        return LpVectorProjection(
            rhs,
            costs,
            uppers,
            hasUpper,
            constant,
            origins,
            LpProjectionLayout(model),
            nonzero,
        ).also {
            vectors = it
            scalarsProjectable = true
        }
    }

    private fun ensureMatrixProjection(meter: LpProjectionMeter): Boolean {
        meter.poll()
        if (!projectionAttempted) {
            val completed = LpMatrixProjection.create(model, meter)
            meter.poll()
            projection = completed
            projectionAttempted = true
        }
        return projection != null
    }

    private fun projectScalars(
        meter: LpProjectionMeter,
        rhs: DoubleArray? = null,
        costs: DoubleArray? = null,
        uppers: DoubleArray? = null,
        hasUpper: BooleanArray? = null,
        origins: DoubleArray? = null,
    ): Double? {
        for (i in 0 until model.m) {
            meter.poll()
            val value = model.rhs(i).project() ?: return null
            if (rhs != null) rhs[i] = value
        }
        for (j in 0 until model.numVars) {
            meter.poll()
            val cost = model.objective.cost(j).project(nonzeroRequired = true) ?: return null
            if (costs != null) costs[j] = cost
            model.column(j).bounds.lower?.let { if (it.number.project() == null) return null }
            model.column(j).bounds.upper?.let {
                val upper = it.number.project() ?: return null
                if (uppers != null) uppers[j] = upper
                if (hasUpper != null) hasUpper[j] = true
            }
            if (j < model.n) {
                val origin = model.column(j).origin.project() ?: return null
                if (origins != null) origins[j] = origin
            }
        }
        val constant = model.objective.constant.project() ?: return null
        if (model.objective.scale.project(nonzeroRequired = true) == null ||
            model.objective.externalConstant.project() == null
        ) {
            return null
        }
        meter.poll()
        return constant
    }
}

private fun LpBoundAssertion.storageUnits(): Long = 5L + (side.premises?.size ?: 0L)

internal fun ExactLpSide.strongerThan(other: ExactLpSide, upper: Boolean): Boolean {
    val comparison = number.value.compareTo(other.number.value)
    return (if (upper) comparison < 0 else comparison > 0) ||
        (comparison == 0 && strict && !other.strict)
}

private fun ExactLpNumber.project(nonzeroRequired: Boolean = false): Double? {
    val result = approximation
    return result.takeIf { it.isFinite() && (!nonzeroRequired || it != 0.0 || value.isZero) }
}

private class LpProjectionLayout(model: ExactLpModel) {
    val rhs = LongArray(model.m)
    val cost = LongArray(model.numVars)
    val upper = LongArray(model.numVars)
    val origins = LongArray(model.n)
    val tag = IntArray(model.n) { model.column(it).tag }
    val rowGlobal = BooleanArray(model.m) { model.row(it).global }
    val rowStrict = BooleanArray(model.m) { model.row(it).strict }
    val rowPremises = arrayOfNulls<LpRowPremises>(model.m)
    val probeClampedLo = BooleanArray(model.n)
    val probeClampedHi = BooleanArray(model.n)
    val continuous = BooleanArray(model.n) { !model.column(it).integral }
}

private class LpProjectionNonzero(
    val rhs: IntArray,
    val costs: IntArray,
    val origins: IntArray,
    val bounds: IntArray,
) {
    constructor(model: ExactLpModel) : this(
        (0 until model.m).filter { !model.rhs(it).value.isZero }.toIntArray(),
        (0 until model.numVars).filter { !model.objective.cost(it).value.isZero }.toIntArray(),
        (0 until model.n).filter { !model.column(it).origin.value.isZero }.toIntArray(),
        IntArray(model.numVars) { boundMask(model.column(it).bounds) },
    )

    fun withBounds(model: ExactLpModel, columns: IntArray, meter: LpProjectionMeter): LpProjectionNonzero {
        var next = bounds
        for (column in columns) {
            meter.poll()
            val mask = boundMask(model.column(column).bounds)
            if (mask == bounds[column]) continue
            if (next === bounds) {
                meter.reserve(bounds.size.toLong(), bounds.size * 4L, matrix = false)
                next = bounds.copyOf()
            }
            next[column] = mask
        }
        return LpProjectionNonzero(rhs, costs, origins, next)
    }

    fun withCosts(model: ExactLpModel, columns: IntArray, meter: LpProjectionMeter): LpProjectionNonzero {
        var size = costs.size
        var changed = false
        for (column in columns) {
            meter.poll()
            val present = costs.binarySearch(column) >= 0
            val required = !model.objective.cost(column).value.isZero
            if (present != required) {
                changed = true
                size += if (required) 1 else -1
            }
        }
        if (!changed) return this
        meter.reserve(costs.size.toLong() + columns.size, size * 4L, matrix = false)
        val next = IntArray(size)
        var old = 0
        var change = 0
        var position = 0
        while (old < costs.size || change < columns.size) {
            meter.poll()
            if (change < columns.size && (old == costs.size || columns[change] <= costs[old])) {
                val column = columns[change++]
                if (old < costs.size && costs[old] == column) old++
                if (!model.objective.cost(column).value.isZero) next[position++] = column
            } else {
                next[position++] = costs[old++]
            }
        }
        return LpProjectionNonzero(rhs, next, origins, bounds)
    }

    private companion object {
        fun boundMask(bounds: ExactLpBounds): Int = (if (bounds.lower?.number?.value?.isZero == false) 1 else 0) or
            (if (bounds.upper?.number?.value?.isZero == false) 2 else 0)
    }
}

// Snapshots share immutable projected vectors, copying only arrays with changed values. No projection retains
// its predecessor's exact state, so the numerical cache cannot keep the search trail alive.
private class LpVectorProjection(
    val rhs: DoubleArray,
    val cost: DoubleArray,
    val upper: DoubleArray,
    val hasUpper: BooleanArray,
    val constant: Double,
    val origins: DoubleArray,
    val layout: LpProjectionLayout,
    private val nonzero: LpProjectionNonzero,
) {
    fun lostNonzero(model: LpModel): Boolean {
        for (i in nonzero.rhs) if (model.rhsD(i) == 0.0) return true
        for (j in nonzero.costs) if (model.costD(j) == 0.0) return true
        for (j in nonzero.origins) if (model.loShiftD(j) == 0.0) return true
        for (j in nonzero.bounds.indices) {
            val mask = nonzero.bounds[j]
            if (mask and 1 != 0 && model.lowerD(j) == 0.0) return true
            if (mask and 2 != 0 && model.upperD(j) == 0.0) return true
        }
        return false
    }

    fun ownedCopy(model: ExactLpModel, meter: LpProjectionMeter): LpVectorProjection {
        meter.reserveVectors(model)
        return LpVectorProjection(
            rhs.copyOf(),
            cost.copyOf(),
            upper.copyOf(),
            hasUpper.copyOf(),
            constant,
            origins.copyOf(),
            LpProjectionLayout(model),
            nonzero,
        )
    }

    fun withBounds(model: ExactLpModel, columns: IntArray, meter: LpProjectionMeter): LpVectorProjection? {
        meter.reserve(columns.size * 4L + 1L, 0L, matrix = false)
        var nextUpper = upper
        var nextHasUpper = hasUpper
        for (column in columns) {
            meter.poll()
            val bounds = model.column(column).bounds
            bounds.lower?.let { if (it.number.project() == null) return null }
            val value = bounds.upper?.number?.project() ?: if (bounds.upper == null) 0.0 else return null
            if (upper[column].toRawBits() != value.toRawBits()) {
                if (nextUpper === upper) {
                    meter.reserve(upper.size.toLong(), upper.size * 8L, matrix = false)
                    nextUpper = upper.copyOf()
                }
                nextUpper[column] = value
            }
            if (hasUpper[column] != (bounds.upper != null)) {
                if (nextHasUpper === hasUpper) {
                    meter.reserve(hasUpper.size.toLong(), hasUpper.size.toLong(), matrix = false)
                    nextHasUpper = hasUpper.copyOf()
                }
                nextHasUpper[column] = bounds.upper != null
            }
        }
        meter.poll()
        return LpVectorProjection(
            rhs,
            cost,
            nextUpper,
            nextHasUpper,
            constant,
            origins,
            layout,
            nonzero.withBounds(model, columns, meter),
        )
    }

    fun withObjective(model: ExactLpModel, columns: IntArray, meter: LpProjectionMeter): LpVectorProjection? {
        meter.reserve(columns.size * 2L + 4L, 0L, matrix = false)
        var nextCost = cost
        for (column in columns) {
            meter.poll()
            val value = model.objective.cost(column).project(nonzeroRequired = true) ?: return null
            if (cost[column].toRawBits() != value.toRawBits()) {
                if (nextCost === cost) {
                    meter.reserve(cost.size.toLong(), cost.size * 8L, matrix = false)
                    nextCost = cost.copyOf()
                }
                nextCost[column] = value
            }
        }
        val nextConstant = model.objective.constant.project() ?: return null
        if (model.objective.scale.project(nonzeroRequired = true) == null ||
            model.objective.externalConstant.project() == null
        ) {
            return null
        }
        meter.poll()
        return LpVectorProjection(rhs, nextCost, upper, hasUpper, nextConstant, origins, layout,
            nonzero.withCosts(model, columns, meter))
    }
}

private class LpMatrixProjection(
    val colPtr: IntArray,
    val rowIdx: IntArray,
    val values: DoubleArray,
    val status: LpMatrixProjectionStatus,
) {
    val csc = Csc(colPtr, rowIdx, LongArray(values.size))

    companion object {
        fun create(model: ExactLpModel, meter: LpProjectionMeter): LpMatrixProjection? {
            meter.reserve(model.n.toLong() + model.m + 1L, 0L, matrix = true)
            val size = model.keySize
            if (size < 0L || size > (Long.MAX_VALUE - 64L) / 32L) return null
            meter.reserve(size, size * 32L + 64L, matrix = true)
            var underflows = 0
            var overflows = 0
            val pointers = IntArray(model.n + 1)
            for (j in 0 until model.n) {
                meter.poll()
                val count = pointers[j].toLong() + model.entries(j).size
                if (count > Int.MAX_VALUE) return null
                pointers[j + 1] = count.toInt()
            }
            val indices = IntArray(pointers.last())
            val values = DoubleArray(indices.size)
            for (j in 0 until model.n) {
                for ((k, entry) in model.entries(j).withIndex()) {
                    indices[pointers[j] + k] = entry.row
                    meter.poll()
                    val approximation = entry.number.approximation
                    if (approximation.isNaN()) return null
                    values[pointers[j] + k] = when {
                        !approximation.isFinite() -> {
                            overflows++
                            if (entry.number.value.signum() < 0) -Double.MAX_VALUE else Double.MAX_VALUE
                        }

                        approximation == 0.0 && !entry.number.value.isZero -> {
                            underflows++
                            if (entry.number.value.signum() < 0) -0.0 else 0.0
                        }

                        else -> approximation
                    }
                }
            }
            meter.poll()
            return LpMatrixProjection(pointers, indices, values, LpMatrixProjectionStatus(underflows, overflows))
        }
    }
}
