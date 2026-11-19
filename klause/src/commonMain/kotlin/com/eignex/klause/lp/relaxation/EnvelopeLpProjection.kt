package com.eignex.klause.lp.relaxation

import com.eignex.klause.lp.engine.ExactLpNumber
import com.eignex.klause.lp.engine.ExactLpSide
import com.eignex.klause.lp.engine.LpBoundTrail
import com.eignex.klause.lp.engine.LpExactState
import com.eignex.klause.lp.engine.LpProjectionStop
import com.eignex.klause.lp.engine.LpRowBoundTerm
import com.eignex.klause.lp.engine.MixedCertificateMeter
import com.eignex.klause.lp.engine.projectRowBound
import com.eignex.klause.lp.engine.rowEndpointSum
import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BigFraction

internal class EnvelopeLpProjection(private val variables: Int, private val control: ExecutionControl) {
    private class Row(val terms: List<LpRowBoundTerm>, val rhs: BigFraction, val strict: Boolean)
    private val rows = ArrayList<Row>()
    private val incident = Array(variables) { ArrayList<Int>() }

    fun append(row: ExactRationalInequality): Boolean {
        val terms = ArrayList<LpRowBoundTerm>(row.columns.size + 1)
        for (entry in row.columns.indices) {
            control.charge(1L)
            if (control()) return false
            terms += LpRowBoundTerm(row.columns[entry], row.coefficients[entry], null)
        }
        terms += LpRowBoundTerm(variables + rows.size, BigFraction.ONE, 1L)
        for (column in row.columns) {
            control.charge(1L)
            if (control()) return false
            incident[column] += rows.size
        }
        rows += Row(terms, row.rhs, row.strict)
        return !control()
    }

    fun accepts(point: List<BigFraction>): Boolean {
        val accounting = EnvelopeProjectionMeter(control)
        val meter = accounting.exact
        return try {
            for (row in rows) {
                var sum = BigFraction.ZERO
                for (index in 0 until row.terms.lastIndex) {
                    val term = row.terms[index]
                    sum = meter.add(sum, meter.multiply(term.coefficient, point[term.column]))
                }
                val accepted = if (row.strict) meter.less(sum, row.rhs) else !meter.less(row.rhs, sum)
                if (accounting.settle() || !accepted) return false
            }
            true
        } catch (_: LpProjectionStop) {
            false
        } finally {
            accounting.settle()
        }
    }

    // Partial projection retains complete row certificates; proposal propagation grants no source authority.
    fun project(initial: LpExactState, changed: Int? = null, nextWitness: () -> Long): LpExactState {
        val trail = LpBoundTrail(initial)
        val accounting = EnvelopeProjectionMeter(control)
        val meter = accounting.exact
        fun settle() {
            if (accounting.settle()) throw LpProjectionStop()
        }
        fun projectRow(index: Int, changedColumn: (Int) -> Unit): Boolean {
            val row = rows[index]
            control.charge(row.terms.size.toLong())
            if (control() || trail.state.conflict != null) return false
            val state = trail.state
            // Logical slacks have no upper side, so only minima project structural bounds.
            val minimum = rowEndpointSum(state, row.terms, true, meter)
            settle()
            for (entry in 0 until row.terms.lastIndex) {
                val bound = projectRowBound(state, index, row.terms[entry], row.terms, minimum, true, meter)
                settle()
                if (bound != null) {
                    if (!trail.assertBound(bound.column, bound.upper,
                            ExactLpSide(ExactLpNumber.of(bound.value), bound.strict), nextWitness(), control)) return false
                    changedColumn(bound.column)
                }
            }
            return true
        }
        try {
            if (changed == null) {
                for (index in rows.indices.asSequence() + rows.indices.reversed().asSequence()) {
                    if (!projectRow(index) {}) break
                }
            } else {
                val visits = ByteArray(rows.size)
                val queued = BooleanArray(rows.size)
                val pending = ArrayDeque<Int>()
                fun schedule(column: Int) {
                    for (index in incident[column]) {
                        control.charge(1L)
                        if (control()) throw LpProjectionStop()
                        if (visits[index] < 2 && !queued[index]) {
                            pending.addLast(index)
                            queued[index] = true
                        }
                    }
                }
                schedule(changed)
                while (pending.isNotEmpty()) {
                    val index = pending.removeFirst()
                    queued[index] = false
                    visits[index]++
                    if (!projectRow(index, ::schedule)) break
                }
            }
        } catch (_: LpProjectionStop) {
            // Scalar or row admission can decline independently of the retained simplex owner.
        } finally {
            accounting.settle()
        }
        return trail.state
    }
}

private class EnvelopeProjectionMeter(private val control: ExecutionControl) {
    private var charged = 0L
    val exact = MixedCertificateMeter(object : ExecutionControl(control) {
        override fun isCancelled() = settle()
    })

    fun settle(): Boolean {
        val work = exact.work
        val delta = work - charged
        charged = work
        control.charge(delta)
        return control()
    }
}
