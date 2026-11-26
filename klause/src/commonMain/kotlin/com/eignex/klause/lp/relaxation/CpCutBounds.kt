package com.eignex.klause.lp.relaxation

import com.eignex.klause.lp.engine.CutPremise
import com.eignex.klause.lp.engine.CutSourceKind
import com.eignex.klause.propagation.PropagationSession
import com.eignex.util.numbers.BigFraction

internal class CpCutBounds private constructor(
    private val lower: LongArray,
    private val upper: LongArray,
    private val pins: ByteArray,
) {
    fun implies(premise: CutPremise): Boolean = when (premise) {
        is CutPremise.Bound -> impliesBound(premise)
        is CutPremise.Literal -> {
            val pin = pins.getOrNull(premise.literal ushr 1)
            pin != null && pin.toInt() == ((premise.literal and 1) xor 1)
        }
        else -> false
    }

    private fun impliesBound(premise: CutPremise.Bound): Boolean {
        if (!premise.expression.constant.isZero) return false
        val term = premise.expression.terms.entries.singleOrNull() ?: return false
        if (term.key.kind != CutSourceKind.INTEGER || term.value != BigFraction.ONE ||
            term.key.id !in lower.indices
        ) return false
        val endpoint = BigFraction.ofLong(if (premise.upper) upper[term.key.id] else lower[term.key.id])
        val stronger = if (premise.upper) endpoint < premise.value else endpoint > premise.value
        return stronger || (endpoint == premise.value && !premise.strict)
    }

    companion object {
        fun capture(session: PropagationSession): CpCutBounds {
            val lower = LongArray(session.problem.numIntVars)
            val upper = LongArray(session.problem.numIntVars)
            for (variable in lower.indices) {
                val domain = session.intDomain(variable)
                lower[variable] = domain.min
                upper[variable] = domain.max
            }
            val pins = ByteArray(session.problem.numBoolVars) {
                val value = session.boolValue(it)
                if (value == null) -1 else if (value) 1 else 0
            }
            return CpCutBounds(lower, upper, pins)
        }
    }
}
