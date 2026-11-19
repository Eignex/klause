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

// Each pass uses the engine's exact row certificates. A partial pass only retains complete deductions.
internal fun projectEnvelopeRows(
    initial: LpExactState,
    rows: List<ExactRationalInequality>,
    control: ExecutionControl,
    nextWitness: () -> Long,
): LpExactState {
    val trail = LpBoundTrail(initial)
    val meter = MixedCertificateMeter(control)
    var charged = 0L
    fun settle() {
        control.charge(meter.work - charged)
        charged = meter.work
        if (control()) throw LpProjectionStop()
    }
    try {
        for (index in rows.indices.asSequence() + rows.indices.reversed().asSequence()) {
            val row = rows[index]
            control.charge(row.columns.size.toLong() + 1)
            if (control() || trail.state.conflict != null) break
            val state = trail.state
            val terms = row.columns.indices.map {
                LpRowBoundTerm(row.columns[it], row.coefficients[it], null)
            } + LpRowBoundTerm(state.model.n + index, BigFraction.ONE, 1L)
            // Logical slacks have no upper side, so only minima project structural bounds.
            val minimum = rowEndpointSum(state, terms, true, meter)
            settle()
            for (term in terms.dropLast(1)) {
                val bound = projectRowBound(state, index, term, terms, minimum, true, meter)
                settle()
                if (bound != null && !trail.assertBound(bound.column, bound.upper,
                        ExactLpSide(ExactLpNumber.of(bound.value), bound.strict), nextWitness(), control)) {
                    return trail.state
                }
            }
        }
    } catch (_: LpProjectionStop) {
        // Scalar or row admission can decline independently of the retained simplex owner.
    } finally {
        control.charge(meter.work - charged)
    }
    return trail.state
}
