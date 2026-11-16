package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction

internal enum class LogicalBasisLayoutDecline { INVALID_BASIS, SINGULAR }

// Core columns address structural basis slots; core rows retain their original equation addresses.
internal class LogicalBasisLayout private constructor(
    val structuralSlots: List<Int>,
    val retainedRows: List<Int>,
    val logicalSlots: List<Int>,
) {
    companion object {
        fun prepare(
            structuralColumns: Int,
            rows: Int,
            headings: IntArray,
            column: (Int) -> List<Pair<Int, BigFraction>>,
            charge: (Long, Long) -> Unit,
            decline: (LogicalBasisLayoutDecline) -> Nothing,
        ): LogicalBasisLayout {
            if (structuralColumns < 0 || rows < 0 || headings.size != rows ||
                structuralColumns.toLong() + rows > Int.MAX_VALUE) {
                decline(LogicalBasisLayoutDecline.INVALID_BASIS)
            }
            charge(rows.toLong() * 4L, 256L + rows * 128L)
            val logical = IntArray(rows) { -1 }
            val structural = ArrayList<Int>()
            for (slot in headings.indices) {
                charge(1L, 0L)
                val id = headings[slot]
                if (id !in 0 until structuralColumns + rows) decline(LogicalBasisLayoutDecline.INVALID_BASIS)
                if (id < structuralColumns) {
                    structural += slot
                } else {
                    val support = column(id)
                    if (support.size != 1 || support.single().second != BigFraction.ONE) {
                        decline(LogicalBasisLayoutDecline.INVALID_BASIS)
                    }
                    val row = support.single().first
                    if (row !in logical.indices) decline(LogicalBasisLayoutDecline.INVALID_BASIS)
                    if (logical[row] >= 0) decline(LogicalBasisLayoutDecline.SINGULAR)
                    logical[row] = slot
                }
            }
            val retained = logical.indices.filter { logical[it] < 0 }
            if (retained.size != structural.size) decline(LogicalBasisLayoutDecline.INVALID_BASIS)
            return LogicalBasisLayout(structural.toList(), retained, logical.toList())
        }
    }
}

internal class LogicalBasisCore private constructor(
    private val layout: LogicalBasisLayout,
    val matrix: List<List<BigFraction>>,
) {
    val structuralSlots: List<Int> get() = layout.structuralSlots
    val retainedRows: List<Int> get() = layout.retainedRows
    val logicalSlots: List<Int> get() = layout.logicalSlots

    companion object {
        fun prepare(authority: ExactBasisAuthority, meter: ExactBasisMeter): LogicalBasisCore {
            val previousPhase = meter.phase
            meter.phase = ExactBasisPhase.ASSEMBLY
            val layout = LogicalBasisLayout.prepare(authority.model.n, authority.model.m, authority.headings,
                { authority.matrix[it] }, meter::charge, { reason ->
                    meter.stop(when (reason) {
                        LogicalBasisLayoutDecline.INVALID_BASIS -> ExactBasisDecline.INVALID_BASIS
                        LogicalBasisLayoutDecline.SINGULAR -> ExactBasisDecline.SINGULAR
                    })
                })
            val structural = layout.structuralSlots
            val retained = layout.retainedRows
            meter.charge(authority.model.m.toLong(), authority.model.m * 4L)
            val local = IntArray(authority.model.m) { -1 }
            for (id in retained.indices) local[retained[id]] = id
            val size = retained.size
            meter.charge(size.toLong() * size, size.toLong() * size * 16L + size * 64L)
            val matrix = List(size) { MutableList(size) { BigFraction.ZERO } }
            for (id in structural.indices) {
                for ((row, value) in authority.matrix[authority.headings[structural[id]]]) {
                    meter.charge()
                    if (local[row] >= 0) matrix[local[row]][id] = value
                }
            }
            val result = LogicalBasisCore(layout, matrix.map { it.toList() })
            meter.phase = previousPhase
            return result
        }
    }
}
