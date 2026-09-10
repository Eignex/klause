package com.eignex.klause.lp.engine

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
) {
    private val activeAssertions = assertions.toList()
    private val scopeMarks = scopes.toList()
    private val changed = changedColumns.toList()
    private val lower = arrayOfNulls<LpBoundAssertion>(baseModel.numVars)
    private val upper = arrayOfNulls<LpBoundAssertion>(baseModel.numVars)
    private var projection: LpMatrixProjection? = null
    private var projectionAttempted = false

    val assertions: List<LpBoundAssertion> get() = activeAssertions.toList()
    val scopes: List<Int> get() = scopeMarks.toList()
    val changedColumns: List<Int> get() = changed.toList()
    val depth: Int get() = scopeMarks.size
    val model: ExactLpModel
    val conflict: LpBoundConflict?

    init {
        require(listOf(matrixRevision, boundRevision, objectiveRevision, popRevision).all { it >= 0L })
        require(scopeMarks.all { it in 0..activeAssertions.size })
        require(scopeMarks.zipWithNext().all { (a, b) -> a <= b })
        require(activeAssertions.map { it.witness }.distinct().size == activeAssertions.size)
        for ((index, assertion) in activeAssertions.withIndex()) {
            require(assertion.column in 0 until baseModel.n && assertion.witness >= 0L)
            require(assertion.depth == scopeMarks.count { it <= index })
        }
        require(changed.all { it in 0 until baseModel.numVars } && changed.distinct().size == changed.size)
        for (j in 0 until baseModel.numVars) {
            val bounds = baseModel.column(j).bounds
            lower[j] = bounds.lower?.let { LpBoundAssertion(j, false, it, -2L * j - 1L, 0) }
            upper[j] = bounds.upper?.let { LpBoundAssertion(j, true, it, -2L * j - 2L, 0) }
        }
        for (assertion in activeAssertions) {
            val sides = if (assertion.upper) upper else lower
            val previous = sides[assertion.column]
            if (previous == null || assertion.strongerThan(previous)) sides[assertion.column] = assertion
        }
        model = baseModel.copy(
            columns = List(baseModel.numVars) { j ->
                baseModel.column(j).copy(bounds = ExactLpBounds(lower[j]?.side, upper[j]?.side))
            },
        )
        conflict = (0 until model.numVars).firstOrNull { !model.column(it).bounds.consistent }?.let {
            LpBoundConflict(it, requireNotNull(lower[it]), requireNotNull(upper[it]))
        }
    }

    fun activeSide(column: Int, upper: Boolean): LpBoundAssertion? = if (upper) this.upper[column] else lower[column]

    fun sameMatrix(other: LpExactState): Boolean = matrixRevision == other.matrixRevision && model.sameMatrix(
        other.model,
    )

    fun fullAuthorityEquals(other: LpExactState): Boolean = baseModel.sameAuthority(other.baseModel) &&
        model.sameAuthority(
            other.model,
        ) && activeAssertions == other.activeAssertions && scopeMarks == other.scopeMarks &&
        matrixRevision == other.matrixRevision && boundRevision == other.boundRevision &&
        objectiveRevision == other.objectiveRevision && popRevision == other.popRevision

    internal fun inheritProjection(previous: LpExactState) {
        projection = previous.projection
        projectionAttempted = previous.projectionAttempted
    }

    fun toWorkingModel(): LpModel? {
        if (!projectionAttempted) {
            projection = LpMatrixProjection.create(model)
            projectionAttempted = true
        }
        val matrix = projection ?: return null
        val rhs = DoubleArray(model.m)
        val costs = DoubleArray(model.numVars)
        val uppers = DoubleArray(model.numVars)
        val hasUpper = BooleanArray(model.numVars)
        val origins = DoubleArray(model.n)
        for (i in rhs.indices) rhs[i] = model.rhs(i).project() ?: return null
        for (j in 0 until model.numVars) {
            costs[j] = model.objective.cost(j).project(nonzeroRequired = true) ?: return null
            model.column(j).bounds.lower?.let { if (it.number.project() == null) return null }
            model.column(j).bounds.upper?.let {
                uppers[j] = it.number.project() ?: return null
                hasUpper[j] = true
            }
            if (j < model.n) origins[j] = model.column(j).origin.project() ?: return null
        }
        val constant = model.objective.constant.project() ?: return null
        if (model.objective.scale.project(nonzeroRequired = true) == null ||
            model.objective.externalConstant.project() == null
        ) {
            return null
        }
        return LpModel(
            n = model.n,
            m = model.m,
            csc = matrix.csc,
            rhs = LongArray(model.m),
            cost = LongArray(model.numVars),
            upper = LongArray(model.numVars),
            hasUpper = hasUpper.copyOf(),
            loShift = LongArray(model.n),
            objConstant = 0L,
            sense = model.objective.sense,
            tag = IntArray(model.n) { model.column(it).tag },
            rowGlobal = BooleanArray(model.m) { model.row(it).global },
            rowStrict = BooleanArray(model.m) { model.row(it).strict },
            colContinuous = BooleanArray(model.n) { !model.column(it).integral },
            doubleView = LpDoubleView(
                matrix.colPtr, matrix.rowIdx, matrix.values, rhs, costs, uppers, hasUpper, constant, origins,
            ),
            exactState = this,
        )
    }
}

private fun LpBoundAssertion.strongerThan(other: LpBoundAssertion): Boolean {
    val comparison = side.number.value.compareTo(other.side.number.value)
    return (if (upper) comparison < 0 else comparison > 0) ||
        (comparison == 0 && side.strict && !other.side.strict)
}

private fun ExactLpNumber.project(nonzeroRequired: Boolean = false): Double? {
    val result = ieeeBits?.let { Double.fromBits(it) } ?: value.toDouble()
    return result.takeIf { it.isFinite() && (!nonzeroRequired || it != 0.0 || value.isZero) }
}

private class LpMatrixProjection(val colPtr: IntArray, val rowIdx: IntArray, val values: DoubleArray) {
    val csc = Csc(colPtr, rowIdx, LongArray(values.size))

    companion object {
        fun create(model: ExactLpModel): LpMatrixProjection? {
            val pointers = IntArray(model.n + 1)
            for (j in 0 until model.n) {
                val count = pointers[j].toLong() + model.entries(j).size
                if (count > Int.MAX_VALUE) return null
                pointers[j + 1] = count.toInt()
            }
            val indices = IntArray(pointers.last())
            val values = DoubleArray(indices.size)
            for (j in 0 until model.n) {
                for ((k, entry) in model.entries(j).withIndex()) {
                    indices[pointers[j] + k] = entry.row
                    values[pointers[j] + k] = entry.number.project(nonzeroRequired = true) ?: return null
                }
            }
            return LpMatrixProjection(pointers, indices, values)
        }
    }
}
