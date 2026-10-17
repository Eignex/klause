package com.eignex.klause.lp.cut

import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntegerConstants
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Term
import com.eignex.klause.lp.engine.Cut
import com.eignex.klause.solver.result.MirUseBudget
import com.eignex.klause.solver.result.MirUseOwner
import com.eignex.klause.util.fitsLong
import com.eignex.klause.util.toLongExact

internal class MirUseObservation(private val owner: MirUseOwner) {
    private var factorId = -1
    private var kind = 0
    private var relation = -1
    private var capturedRows = 0uL

    fun begin(ctx: CutContext) {
        owner.begin(ctx.session.decisionLevel == 0, ctx.relaxation.sourceMap != null)
        capturedRows = 0uL
        owner.sourceEpoch(ctx.relaxation.sourceMap?.epoch ?: -1L)
        if (ctx.relaxation.sourceMap == null) owner.omit(30)
    }

    fun end() = owner.end()

    fun factor(index: Int, factor: Factor) {
        factorId = index
        kind = when (factor) {
            is Linear -> 1
            is ExactLinear -> 2
            is PseudoBoolean -> 3
            is Cardinality -> 4
            else -> 0
        }
        relation = when (factor) {
            is LinearRow -> factor.relation.ordinal
            is Cardinality -> 4
            else -> -1
        }
        if (!owner.snapshotActive) return
        if (factor is Linear) {
            if (factor.constants !is IntegerConstants) owner.omit(32)
            if (factor.variables.reals.isNotEmpty()) owner.omit(35)
            if (factor.relation == LinearOp.NE) owner.omit(36)
            return
        }
        if (factor is PseudoBoolean && factor.constants !is IntegerConstants) owner.omit(32)
        if (factor !is ExactLinear) {
            if (kind == 0) owner.omit(37)
            return
        }
        if (factor.constants !is IntegerConstants) owner.omit(32)
        if (factor.activator != LinearRow.ALWAYS) owner.omit(33)
        if (factor.strict) owner.omit(34)
        if (factor.variables.reals.isNotEmpty()) owner.omit(35)
        if (factor.relation == LinearOp.NE) owner.omit(36)
    }

    fun potential(factor: Factor, admitted: Boolean, ctx: CutContext) {
        if (!owner.snapshotActive || factor !is ExactLinear || factor.constants !is IntegerConstants ||
            factor.activator != LinearRow.ALWAYS || factor.strict || factor.variables.reals.isNotEmpty() ||
            factor.relation == LinearOp.NE
        ) {
            return
        }
        owner.count(14)
        if (admitted) return
        val size = 10L + 12L * factor.size
        val offset = owner.reserve(size) ?: return
        owner.put(offset, MirUseBudget.POTENTIAL_TAG)
        owner.put(offset + 1, size)
        owner.put(offset + 2, owner.id.toLong())
        owner.put(offset + 3, factorId.toLong())
        owner.put(offset + 4, 2L)
        owner.put(offset + 5, factor.relation.ordinal.toLong())
        owner.put(offset + 6, factor.activator.toLong())
        owner.put(offset + 7, if (factor.strict) 1L else 0L)
        owner.put(offset + 8, factor.bound)
        owner.put(offset + 9, factor.size.toLong())
        for (index in 0 until factor.size) {
            val ref = factor.ref(index)
            val variable = Term.intVar(ref)
            val target = offset + 10 + 12 * index
            owner.put(target, ref.toLong())
            owner.put(target + 1, factor.coeff(index))
            owner.put(target + 2, ctx.relaxation.intColOf[variable].toLong())
            owner.put(target + 3, if (ctx.statesLowerBound(variable)) 1L else 0L)
            owner.put(target + 4, ctx.rootDomain(variable).min)
            columnMapping(target + 5, ctx.relaxation.intColOf[variable], ctx)
        }
    }

    fun integerRow(
        row: LinearRow,
        flipped: Boolean,
        ordinal: Int,
        cols: IntArray,
        coefficients: LongArray,
        bound: Long,
        ctx: CutContext,
    ) {
        owner.count(if (kind == 2) 16 else 15)
        val size = 11L + 2L * row.size + 13L * cols.size
        val offset = owner.reserve(size) ?: return
        rowHeader(offset, size, ordinal, flipped, row.relation.ordinal, row.bound, bound, row.size)
        for (index in 0 until row.size) {
            owner.put(offset + 11 + 2 * index, row.ref(index).toLong())
            owner.put(offset + 12 + 2 * index, row.coeff(index))
        }
        if (normalized(offset + 11 + 2 * row.size, cols, coefficients, ctx)) {
            capturedRows = capturedRows or (1uL shl ordinal)
        }
    }

    fun booleanRow(
        literals: IntArray,
        weight: (Int) -> Long,
        originalBound: Long,
        flipped: Boolean,
        ordinal: Int,
        cols: IntArray,
        coefficients: LongArray,
        bound: Long,
        ctx: CutContext,
    ) {
        owner.count(17)
        val size = 11L + 2L * literals.size + 13L * cols.size
        val offset = owner.reserve(size) ?: return
        rowHeader(offset, size, ordinal, flipped, relation, originalBound, bound, literals.size)
        for (index in literals.indices) {
            owner.put(offset + 11 + 2 * index, Term.ofLit(literals[index]).toLong())
            owner.put(offset + 12 + 2 * index, weight(index))
        }
        if (normalized(offset + 11 + 2 * literals.size, cols, coefficients, ctx)) {
            capturedRows = capturedRows or (1uL shl ordinal)
        }
    }

    private fun rowHeader(
        offset: Int,
        size: Long,
        ordinal: Int,
        flipped: Boolean,
        relation: Int,
        originalBound: Long,
        bound: Long,
        sourceSize: Int,
    ) {
        owner.put(offset, MirUseBudget.ROW_TAG)
        owner.put(offset + 1, size)
        owner.put(offset + 2, owner.id.toLong())
        owner.put(offset + 3, ordinal.toLong())
        owner.put(offset + 4, factorId.toLong())
        owner.put(offset + 5, if (flipped) 1L else 0L)
        owner.put(offset + 6, kind.toLong())
        owner.put(offset + 7, relation.toLong())
        owner.put(offset + 8, originalBound)
        owner.put(offset + 9, bound)
        owner.put(offset + 10, sourceSize.toLong())
    }

    private fun normalized(offset: Int, cols: IntArray, coefficients: LongArray, ctx: CutContext): Boolean {
        var complete = true
        for (index in cols.indices) {
            val col = cols[index]
            val variable = ctx.relaxation.colVarId[col]
            val bool = ctx.relaxation.colIsBool[col]
            val target = offset + 13 * index
            owner.put(target, col.toLong())
            owner.put(target + 1, if (bool) 1L else 0L)
            owner.put(target + 2, variable.toLong())
            owner.put(target + 3, coefficients[index])
            owner.put(target + 4, if (bool) 0L else ctx.rootDomain(variable).min)
            owner.put(target + 5, if (bool || ctx.statesLowerBound(variable)) 1L else 0L)
            if (!columnMapping(target + 6, col, ctx)) complete = false
        }
        return complete
    }

    private fun columnMapping(offset: Int, column: Int, ctx: CutContext): Boolean {
        val mapping = if (column < 0) null else ctx.relaxation.sourceMap?.column(column)
        if (mapping == null) {
            owner.put(offset, 0L)
            owner.omit(30)
            return false
        }
        owner.put(offset + 1, mapping.source.kind.ordinal.toLong())
        owner.put(offset + 2, mapping.source.id.toLong())
        if (!mapping.scale.num.fitsLong() || !mapping.scale.den.fitsLong() ||
            !mapping.offset.num.fitsLong() || !mapping.offset.den.fitsLong()
        ) {
            owner.put(offset, -1L)
            owner.omit(30)
            return false
        }
        owner.put(offset, 1L)
        owner.put(offset + 3, mapping.scale.num.toLongExact())
        owner.put(offset + 4, mapping.scale.den.toLongExact())
        owner.put(offset + 5, mapping.offset.num.toLongExact())
        owner.put(offset + 6, mapping.offset.den.toLongExact())
        return true
    }

    fun produced(first: Int, second: Int, cut: Cut, ctx: CutContext) {
        owner.count(18)
        if (!owner.snapshotActive) return
        if (first !in 0 until ULong.SIZE_BITS || capturedRows and (1uL shl first) == 0uL ||
            (second >= 0 && (second >= ULong.SIZE_BITS || capturedRows and (1uL shl second) == 0uL))
        ) {
            owner.omit(38)
            return
        }
        val size = 8L + 4L * cut.cols.size
        val offset = owner.reserve(size, cut = true) ?: return
        owner.put(offset, MirUseBudget.CUT_TAG)
        owner.put(offset + 1, size)
        owner.put(offset + 2, owner.id.toLong())
        owner.put(offset + 3, first.toLong())
        owner.put(offset + 4, second.toLong())
        owner.put(offset + 5, if (cut.global) 1L else 0L)
        owner.put(offset + 6, cut.rhs)
        owner.put(offset + 7, cut.cols.size.toLong())
        for (index in cut.cols.indices) {
            val col = cut.cols[index]
            val target = offset + 8 + 4 * index
            owner.put(target, col.toLong())
            owner.put(target + 1, cut.coeffs[index])
            owner.put(target + 2, ctx.relaxation.colVarId[col].toLong())
            owner.put(target + 3, if (ctx.relaxation.colIsBool[col]) 1L else 0L)
        }
    }

    fun missingColumn() = owner.omit(23)
    fun openLower() = owner.omit(24)
    fun rowOverflow() = owner.omit(25)
    fun combinationOverflow() = owner.omit(26)
    fun policyStopped() = owner.omit(27)
}
