package com.eignex.klause.lp.engine

import com.eignex.koblas.DenseVector
import com.eignex.koblas.koblas

internal class SimplexBasicValues {
    var values: DoubleArray? = null
        private set
    var model: LpModel? = null
        private set
    var numerical: LpScalingView? = null
        private set
    var status: Array<VarStatus>? = null
        private set
    var primalOnly = false
        private set

    fun invalidate() {
        values = null
        model = null
        numerical = null
        status = null
        primalOnly = false
    }

    fun seed(model: LpModel, numerical: LpScalingView, values: DoubleArray, status: Array<VarStatus>) {
        this.values = values
        this.model = model
        this.numerical = numerical
        this.status = status
        primalOnly = false
    }

    private fun seat(source: LpScalingView, side: VarStatus, column: Int): Double = when (side) {
        VarStatus.AT_UPPER -> source.upperD(column)
        VarStatus.AT_LOWER, VarStatus.FIXED -> source.lowerD(column)
        VarStatus.FREE, VarStatus.BASIC -> 0.0
    }

    fun retain(
        model: LpModel,
        numerical: LpScalingView,
        status: Array<VarStatus>,
        beta: DoubleArray,
        primalOnly: Boolean = false,
    ) {
        if (model.exactState == null) return
        values = (values ?: DoubleArray(model.m)).also { beta.copyInto(it) }
        this.model = model
        this.primalOnly = primalOnly
        this.numerical = numerical
        this.status = this.status?.also { status.copyInto(it) } ?: status.copyOf()
    }

    inline fun restore(
        model: LpModel,
        numerical: LpScalingView,
        status: Array<VarStatus>,
        out: DoubleArray,
        workspace: SimplexWorkspace,
        work: LpWork,
        colPtr: IntArray,
        accumulate: (DoubleArray, Double, Int) -> Unit,
        solve: (DoubleArray, DoubleArray) -> Unit,
    ): Boolean {
        val m = model.m
        val n = model.n
        val numVars = model.numVars
        val previous = this.model ?: return false
        val previousNumerical = this.numerical ?: return false
        val values = this.values ?: return false
        val seats = this.status ?: return false
        val before = previous.exactState ?: return false
        val after = model.exactState ?: return false
        if (before.popRevision != after.popRevision || (0 until n).any {
                previous.exactShift(
                    it,
                ) != model.exactShift(it)
            }
        ) {
            return false
        }
        if ((0 until m).any { previous.exactRhs(it) != model.exactRhs(it) }) return false
        work.add(numVars)
        values.copyInto(out)
        val rhs = workspace.basicRhs
        rhs.fill(0.0)
        var changed = false
        for (j in 0 until numVars) {
            if (status[j] == VarStatus.BASIC) continue
            val delta = seat(numerical, status[j], j) - seat(previousNumerical, seats[j], j)
            if (delta == 0.0) continue
            changed = true
            work.add(2 * (colPtr[j + 1] - colPtr[j]))
            accumulate(rhs, -delta, j)
        }
        if (changed) {
            val change = workspace.boundChange
            solve(rhs, change)
            work.add(m)
            koblas.axpy(1.0, DenseVector.wrap(change, size = m), DenseVector.wrap(out, size = m))
        }
        return out.all { it.isFinite() }
    }
}
