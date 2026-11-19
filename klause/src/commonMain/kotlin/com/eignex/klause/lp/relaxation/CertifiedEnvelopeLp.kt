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
    private val owner: LpScopedSolver,
    val variables: Int,
    private val control: ExecutionControl,
    private val rowTemplates: MutableList<ExactRationalInequality>,
) : AutoCloseable {
    private var witness = 0L
    val rows: Int get() = owner.state.model.m

    fun narrow(column: Int, interval: EnvelopeInterval): Boolean {
        require(column in 0 until variables)
        control.charge(1L)
        if (control()) return false
        val lo = interval.lower
        if (lo != null && !owner.assertBound(column, false, ExactLpSide(ExactLpNumber.of(lo)), witness++, control)) {
            return false
        }
        val hi = interval.upper
        return hi == null || owner.assertBound(column, true, ExactLpSide(ExactLpNumber.of(hi)), witness++, control)
    }

    fun solve(column: Int? = null, upper: Boolean = false): CertifiedLpResult? {
        require(column == null || column in 0 until variables)
        control.charge(owner.state.model.numVars.toLong())
        if (control()) return null
        val objective = ExactLpObjective(List(owner.state.model.numVars) { id ->
            ExactLpNumber.of(if (id != column) 0L else if (upper) -1L else 1L)
        })
        if (!owner.replaceObjective(objective, control)) return null
        val projected = projectEnvelopeRows(owner.state, rowTemplates, control) { witness++ }
        if (!owner.adopt(projected, control)) return null
        if (owner.state.conflict == null) directPoint(column, upper)?.let { return it }
        return owner.solve(token = control,
            claim = if (column == null) LpClaim.FEASIBLE_COMPLETION else LpClaim.ATTAINED_OPTIMUM)
    }

    fun append(rows: List<ExactRationalInequality>): Boolean {
        for (row in rows) {
            control.charge(row.columns.size.toLong() + 1L)
            if (control()) return false
            val zero = ExactLpNumber.of(0L)
            val addition = LpScopedRow(
                owner.state.rows.lastId + 1L,
                row.columns.indices.map { row.columns[it] to ExactLpNumber.of(row.coefficients[it]) },
                ExactLpNumber.of(row.rhs),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero, row.strict)), integral = false),
                ExactLpRow(strict = row.strict),
            )
            if (!owner.append(addition, false, control)) return false
            rowTemplates += row
        }
        return true
    }

    override fun close() = owner.close()

    private fun directPoint(column: Int?, upper: Boolean): CertifiedLpResult? {
        val source = owner.state
        val trial = LpBoundTrail(source)
        if (column != null) {
            val bounds = source.model.column(column).bounds
            val endpoint = (if (upper) bounds.upper else bounds.lower)?.number ?: return null
            if (!trial.assertBound(column, !upper, ExactLpSide(endpoint), witness++, control)) return null
        }
        val projected = projectEnvelopeRows(trial.state, rowTemplates, control) { witness++ }
        if (control() || projected.conflict != null) return null
        val point = List(variables) { id ->
            control.charge(1L)
            val bounds = projected.model.column(id).bounds
            val lo = bounds.lower
            val hi = bounds.upper
            when {
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
            return CertifiedEnvelopeLp(LpScopedSolver(LpExactState(model), control), bounds.size, control,
                rows.toMutableList())
        }
    }
}
