package com.eignex.klause.theory

import com.eignex.klause.ir.IntSpan
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.SourceBoolId
import com.eignex.klause.ir.SourceIntId
import com.eignex.klause.ir.Term
import com.eignex.klause.lowering.LinearEncodingInterrupted
import com.eignex.klause.lowering.LinearFormulaLowering
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.PreparedFactorProjections
import com.eignex.klause.solver.pipeline.ExactSearchRow
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.bigIntOf

private const val MAX_CONDITIONED_LINEAR_CASES = 4096

internal fun PreparedFactorProjections.conditionedLinearSupported(
    index: Int,
    cancellation: Cancellation = Cancellation.Never,
): Boolean = conditionedLinearDomains(index, cancellation) != null

private fun PreparedFactorProjections.conditionedLinearDomains(
    index: Int,
    cancellation: Cancellation,
): List<Pair<SourceIntId, IntSpan>>? {
    val selected = encoder(index, EncodingTarget.LINEAR)
    if (selected.support != EncoderSupport.CONDITIONED || selected.requiredBooleans.isNotEmpty()) return null
    val conditions = selected.requiredIntegers.distinct()
    var cases = 1L
    return conditions.map { id ->
        if (cancellation()) return null
        cancellation.charge(1)
        if (cancellation()) return null
        if (id.index !in 0 until problem.numIntVars || !problem.intBounds.hasLower(id.index) ||
            !problem.intBounds.hasUpper(id.index)
        ) return null
        val domain = problem.declaredIntDomains.finiteDomain(id.index)
            .spanOrNull(MAX_CONDITIONED_LINEAR_CASES.toLong()) ?: return null
        cases *= domain.size.toLong()
        if (cases > MAX_CONDITIONED_LINEAR_CASES) return null
        id to domain
    }
}

internal fun PreparedFactorProjections.installConditionedLinearFactor(
    index: Int,
    integerPins: Map<SourceIntId, BigInt>,
    booleanPins: Map<SourceBoolId, Boolean>,
    cancellation: Cancellation,
    allocator: EncodingRealAllocator,
    onDecline: (EncoderDecline) -> Unit,
): List<EncodedTheoryFactor>? {
    val domains = conditionedLinearDomains(index, cancellation) ?: run {
        onDecline(if (cancellation()) EncoderDecline.RESOURCE else EncoderDecline.CONDITIONS)
        return null
    }
    val source = problem.factors[index]
    val lowering = LinearFormulaLowering(cancellation)
    val result = ArrayList<EncodedTheoryFactor>()
    val pins = integerPins.toMutableMap()
    fun install(position: Int): Boolean {
        lowering.checkpoint()
        if (position < domains.size) {
            val (id, domain) = domains[position]
            val fixed = integerPins[id]
            var matched = false
            for (offset in 0 until domain.size) {
                lowering.checkpoint()
                val value = bigIntOf(domain.valueAt(offset))
                if (fixed != null && value != fixed) continue
                matched = true
                pins[id] = value
                if (!install(position + 1)) return false
            }
            if (!matched) {
                onDecline(EncoderDecline.CONDITIONS)
                return false
            }
            if (fixed == null) pins.remove(id) else pins[id] = fixed
            return true
        }
        val relations = installLinearFactor(index, EncodingTarget.LINEAR, pins, booleanPins,
            cancellation, allocator, onDecline) ?: return false
        val guards = domains.map { (id, _) -> ExactSearchRow.of(intArrayOf(Term.ofIntVar(id.index)),
            listOf(BigFraction.ONE), LinearOp.NE, BigFraction.of(checkNotNull(pins[id]), BIG_ONE)) }
        for (relation in relations) {
            lowering.checkpoint()
            val clauses = lowering.or(listOf(listOf(guards), lowering.relation(relation.linearForm)))
            val previous = relation.attribution
            val attribution = EncodingAttribution(previous.sourceFactorIndex,
                previous.booleans, (previous.integers + domains.map { it.first }).distinct(), previous.reals,
                auxiliaries = previous.auxiliaries, integerConditions = integerPins,
                booleanConditions = booleanPins, affineAuxiliaries = previous.affineAuxiliaries,
                linearGuards = previous.linearGuards)
            for (form in lowering.forms(clauses)) result += EncodedTheoryFactor(source, form, attribution)
        }
        return true
    }
    return try {
        if (!install(0) || cancellation()) {
            if (cancellation()) onDecline(EncoderDecline.RESOURCE)
            null
        } else result
    } catch (_: LinearEncodingInterrupted) {
        onDecline(EncoderDecline.RESOURCE)
        null
    }
}
