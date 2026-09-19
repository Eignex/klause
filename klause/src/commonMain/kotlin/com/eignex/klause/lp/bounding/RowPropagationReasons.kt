package com.eignex.klause.lp.bounding

import com.eignex.klause.lp.engine.ExactLpPremises
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.search.SearchAtomPremise
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchExplanation
import com.eignex.klause.util.Cancellation
import com.ionspin.kotlin.bignum.integer.BigInteger

internal data class RowPropagationLimits(
    val rows: Int = 64,
    val columns: Int = 128,
    val rowLength: Int = 16,
    val inputBits: Int = 128,
    val bits: Int = 512,
    val work: Long = 200_000,
    val allocation: Long = 200_000,
    val expansion: Int = 4096,
    val publications: Int = 128,
)

internal class RowPropagationBudget(val limits: RowPropagationLimits) {
    var work = 0L
        private set
    var allocation = 0L
        private set
    var expansion = 0
        private set
    var declined = false
        private set
    var cancellation: Cancellation = Cancellation.Never

    init {
        require(limits.rows in 0..64 && limits.columns in 0..128 && limits.rowLength in 0..16)
        require(limits.inputBits in 1..128 && limits.bits in limits.inputBits..512)
        require(limits.work >= 0 && limits.allocation >= 0 && limits.expansion in 0..4096)
        require(limits.publications in 0..128)
    }

    fun charge(work: Long = 1, allocation: Long = 0): Boolean {
        if (declined || cancellation() || work > limits.work - this.work ||
            allocation > limits.allocation - this.allocation
        ) {
            declined = true
            return false
        }
        this.work += work
        this.allocation += allocation
        return true
    }

    fun visit(): Boolean {
        if (expansion >= limits.expansion || !charge(allocation = 2)) {
            declined = true
            return false
        }
        expansion++
        return true
    }

    fun input(value: BigFraction): Boolean = charge() && value.num.bitLength() <= limits.inputBits &&
        value.den.bitLength() <= limits.inputBits

    fun arithmetic(a: BigFraction, b: BigFraction = BigFraction.ZERO): Boolean {
        val bits = maxOf(a.num.bitLength(), a.den.bitLength()) +
            maxOf(b.num.bitLength(), b.den.bitLength()) + 1
        return charge() && bits <= limits.bits && charge(1L + bits.toLong() * bits, 8L + bits)
    }

    fun compare(a: BigFraction, b: BigFraction): Int? = if (arithmetic(a, b)) a.compareTo(b) else null

    fun round(value: BigFraction, upper: Boolean, strict: Boolean): BigFraction? {
        if (!arithmetic(value, BigFraction.ONE)) return null
        val quotient = value.num / value.den
        val remainder = value.num % value.den
        val delta = when {
            remainder == BigInteger.ZERO && strict -> if (upper) -1 else 1
            remainder != BigInteger.ZERO && value.num < BigInteger.ZERO && upper -> -1
            remainder != BigInteger.ZERO && value.num > BigInteger.ZERO && !upper -> 1
            else -> 0
        }
        return BigFraction.of(quotient + BigInteger.fromInt(delta), BigInteger.ONE)
    }
}

internal class RowPropagationReasons(private val budget: RowPropagationBudget) {
    private data class Published(val epoch: Any, val premise: SearchAtomPremise)
    private val published = HashMap<Int, Published>()
    private var owner: SearchContext? = null

    fun known(decision: SearchDecision, context: SearchContext): Boolean = owner === context &&
        context.atomLiteral(decision)?.let(published::containsKey) == true

    fun metadata(premises: ExactLpPremises?): SearchAtomPremise? {
        if (premises == null || premises.size == 0L || !budget.charge(premises.size, premises.size * 4)) return null
        if (premises.boundEntries().isNotEmpty()) return null
        return SearchAtomPremise.All(premises.literalEntries().map {
            SearchAtomPremise.Asserted(SearchDecision.Bool(it))
        })
    }

    fun expand(premise: SearchAtomPremise, context: SearchContext, epoch: Any): SearchAtomPremise? {
        if ((owner != null && owner !== context) || !budget.charge(allocation = 4)) return null
        owner = context
        val pending = ArrayDeque<SearchAtomPremise>()
        val leaves = LinkedHashMap<Int, SearchAtomPremise>()
        pending.add(premise)
        while (pending.isNotEmpty()) {
            if (!budget.visit()) return null
            when (val next = pending.removeLast()) {
                is SearchAtomPremise.All -> {
                    if (next.premises.size > budget.limits.expansion - budget.expansion - pending.size ||
                        !budget.charge(allocation = next.premises.size.toLong())
                    ) return null
                    pending.addAll(next.premises)
                }
                is SearchAtomPremise.Asserted -> {
                    val literal = context.atomLiteral(next.decision) ?: return null
                    if (context.boolValue(literal ushr 1) != (literal and 1 == 0)) return null
                    val prior = published[literal]
                    if (prior != null) {
                        if (prior.epoch !== epoch) return null
                        pending.add(prior.premise)
                    } else {
                        leaves[literal] = next
                    }
                }
                SearchAtomPremise.Unavailable -> return null
            }
        }
        if (!budget.charge(allocation = leaves.size.toLong() * 2 + 1)) return null
        return SearchAtomPremise.All(leaves.values.toList())
    }

    fun explanation(
        expanded: SearchAtomPremise,
        context: SearchContext,
        conclusion: SearchDecision? = null,
    ): SearchExplanation? {
        val all = expanded as? SearchAtomPremise.All ?: return null
        if (!budget.charge(allocation = all.premises.size.toLong() + 1)) return null
        val literals = ArrayList<Int>()
        conclusion?.let { literals.add(context.atomLiteral(it) ?: return null) }
        for (leaf in all.premises) {
            if (!budget.visit()) return null
            val asserted = leaf as? SearchAtomPremise.Asserted ?: return null
            val literal = context.atomLiteral(asserted.decision) ?: return null
            if (context.boolValue(literal ushr 1) != (literal and 1 == 0)) return null
            literals.add(literal xor 1)
        }
        return SearchExplanation(literals.toIntArray())
    }

    fun reserve(decision: SearchDecision, expanded: SearchAtomPremise, context: SearchContext, epoch: Any): Boolean {
        val literal = context.atomLiteral(decision) ?: return false
        if (owner !== context || (literal !in published && published.size >= budget.limits.publications) ||
            !budget.charge(allocation = 4)
        ) return false
        published[literal] = Published(epoch, expanded)
        return true
    }
}
