package com.eignex.klause.lp.relaxation

import com.eignex.klause.lp.engine.CertifiedLpResult
import com.eignex.klause.lp.engine.ExactLpBounds
import com.eignex.klause.lp.engine.ExactLpColumn
import com.eignex.klause.lp.engine.ExactLpEntry
import com.eignex.klause.lp.engine.ExactLpModel
import com.eignex.klause.lp.engine.ExactLpNumber
import com.eignex.klause.lp.engine.ExactLpObjective
import com.eignex.klause.lp.engine.ExactLpRow
import com.eignex.klause.lp.engine.ExactLpSide
import com.eignex.klause.lp.engine.LpBoundTrail
import com.eignex.klause.lp.engine.LpClaim
import com.eignex.klause.lp.engine.LpExactState
import com.eignex.klause.lp.engine.LpProjectionMeter
import com.eignex.klause.lp.engine.LpScopedRow
import com.eignex.klause.lp.engine.LpScopedSolver
import com.eignex.klause.lp.engine.verifyRationalCertificate
import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BigFraction

// The adapter's lifetime is one immutable source/query scope. No envelope or certificate escapes it.
internal class CertifiedEnvelopeLp private constructor(
    private var state: LpExactState,
    val variables: Int,
    private val control: ExecutionControl,
    private val rowTemplates: MutableList<ExactRationalInequality>,
) : AutoCloseable {
    private var witness = 0L
    private var owner: LpScopedSolver? = null
    private var closed = false
    val rows: Int get() = state.model.m

    fun narrow(column: Int, interval: EnvelopeInterval): Boolean {
        require(column in 0 until variables)
        control.charge(1L)
        if (closed || control()) return false
        val next = LpBoundTrail(state)
        val lo = interval.lower
        if (lo != null && !next.assertBound(column, false, ExactLpSide(ExactLpNumber.of(lo)), witness++, control)) {
            return false
        }
        val hi = interval.upper
        if (hi != null && !next.assertBound(column, true, ExactLpSide(ExactLpNumber.of(hi)), witness++, control)) {
            return false
        }
        return adopt(next.state)
    }

    fun solve(column: Int? = null, upper: Boolean = false): CertifiedLpResult? {
        require(column == null || column in 0 until variables)
        control.charge(state.model.numVars.toLong())
        if (closed || control()) return null
        val objective = ExactLpObjective(List(state.model.numVars) { id ->
            ExactLpNumber.of(if (id != column) 0L else if (upper) -1L else 1L)
        })
        val next = LpBoundTrail(state)
        if (!next.replaceObjective(objective, control)) return null
        val projected = projectEnvelopeRows(next.state, rowTemplates, control) { witness++ }
        if (!adopt(projected)) return null
        if (state.conflict == null) directPoint(column, upper)?.let { return it }
        val numerical = owner ?: LpScopedSolver(state, control).also { owner = it }
        return numerical.solve(token = control,
            claim = if (column == null) LpClaim.FEASIBLE_COMPLETION else LpClaim.ATTAINED_OPTIMUM)
            .also { state = numerical.state }
    }

    fun append(rows: List<ExactRationalInequality>): Boolean {
        for (row in rows) {
            control.charge(row.columns.size.toLong() + 1L)
            if (closed || control()) return false
            val zero = ExactLpNumber.of(0L)
            val addition = LpScopedRow(
                state.rows.lastId + 1L,
                row.columns.indices.map { row.columns[it] to ExactLpNumber.of(row.coefficients[it]) },
                ExactLpNumber.of(row.rhs),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero, row.strict)), integral = false),
                ExactLpRow(strict = row.strict),
            )
            val numerical = owner
            if (numerical != null) {
                if (!numerical.append(addition, false, control)) return false
                state = numerical.state
            } else {
                val next = LpBoundTrail(state)
                if (!next.append(addition, false, control)) return false
                state = next.state
            }
            rowTemplates += row
        }
        return true
    }

    override fun close() {
        if (closed) return
        closed = true
        owner?.close()
        owner = null
    }

    private fun adopt(next: LpExactState): Boolean {
        if (closed || control() || owner?.adopt(next, control) == false) return false
        state = next
        return true
    }

    private fun directPoint(column: Int?, upper: Boolean): CertifiedLpResult? {
        val source = state
        var trial = LpBoundTrail(source)
        if (column != null) {
            val bounds = source.model.column(column).bounds
            val endpoint = (if (upper) bounds.upper else bounds.lower)?.number ?: return null
            if (!trial.assertBound(column, !upper, ExactLpSide(endpoint), witness++, control)) return null
        }
        val point = ArrayList<BigFraction>(variables)
        for (id in 0 until variables) {
            control.charge(1L)
            val projected = projectEnvelopeRows(trial.state, rowTemplates, control) { witness++ }
            if (control() || projected.conflict != null) return null
            trial = LpBoundTrail(projected)
            val value = proposalValue(projected.model.column(id).bounds)
            val side = ExactLpSide(ExactLpNumber.of(value))
            if (!trial.assertBound(id, false, side, witness++, control) ||
                !trial.assertBound(id, true, side, witness++, control)) return null
            point += value
        }
        val meter = LpProjectionMeter(cancellation = control)
        val model = source.ownerWorkingModel(meter)
        control.charge(meter.work)
        if (model == null || control()) return null
        val checked = verifyRationalCertificate(model, sourcePrimal = point,
            duals = List(model.m) { BigFraction.ZERO }, cancellation = control)
        control.charge(checked.metrics.work)
        if (control() || checked.witness == null ||
            (column != null && checked.bound?.value != checked.witness.objective)) return null
        return CertifiedLpResult(null, checked.bound, checked.witness, null, null, false, { null },
            reconstruction = checked.metrics)
    }

    private fun proposalValue(bounds: ExactLpBounds): BigFraction {
        val lo = bounds.lower
        val hi = bounds.upper
        return when {
            lo != null && lo.strict && hi != null -> (lo.number.value + hi.number.value) *
                BigFraction.ofLong(2).reciprocal()
            hi != null && hi.strict && lo != null -> (lo.number.value + hi.number.value) *
                BigFraction.ofLong(2).reciprocal()
            lo != null && (lo.number.value > BigFraction.ZERO || lo.strict) ->
                lo.number.value + if (lo.strict) BigFraction.ONE else BigFraction.ZERO
            hi != null && (hi.number.value < BigFraction.ZERO || hi.strict) ->
                hi.number.value - if (hi.strict) BigFraction.ONE else BigFraction.ZERO
            else -> BigFraction.ZERO
        }
    }

    companion object {
        fun prepare(
            bounds: List<EnvelopeInterval>,
            rows: List<ExactRationalInequality>,
            control: ExecutionControl,
        ): CertifiedEnvelopeLp? {
            control.charge(bounds.size.toLong() + rows.size)
            if (control()) return null
            val columns = List(bounds.size) { ArrayList<ExactLpEntry>() }
            for ((index, row) in rows.withIndex()) {
                control.charge(row.columns.size.toLong() + 1L)
                if (control()) return null
                for (entry in row.columns.indices) {
                    columns[row.columns[entry]] += ExactLpEntry(index, ExactLpNumber.of(row.coefficients[entry]))
                }
            }
            val zero = ExactLpNumber.of(0L)
            val model = ExactLpModel(
                columns,
                rows.map { ExactLpNumber.of(it.rhs) },
                bounds.map { interval ->
                    ExactLpColumn(ExactLpBounds(
                        interval.lower?.let { ExactLpSide(ExactLpNumber.of(it)) },
                        interval.upper?.let { ExactLpSide(ExactLpNumber.of(it)) },
                    ), integral = false)
                } + rows.map { ExactLpColumn(ExactLpBounds(ExactLpSide(zero, it.strict)), integral = false) },
                rows.map { ExactLpRow(strict = it.strict) },
                ExactLpObjective(List(bounds.size + rows.size) { zero }),
            )
            if (control()) return null
            return CertifiedEnvelopeLp(LpExactState(model), bounds.size, control,
                rows.toMutableList())
        }
    }
}
