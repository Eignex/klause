package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.Monomial
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialTerm
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.bigIntOf
import com.eignex.util.numbers.magnitudeBitLength
import com.eignex.util.numbers.shl

internal class DyadicWitnessBase(private val arithmetic: PolynomialArithmetic) {
    fun base(
        atoms: List<PolynomialAtom>,
        counts: List<Int>,
        seed: List<BigFraction>,
        active: Set<Int>,
        fixed: Map<Int, BigFraction>,
        coupledTerms: List<List<PolynomialTerm>>?,
        work: QfNraWork?,
    ): List<BigFraction>? {
        arithmetic.step()
        if (counts.size != atoms.size || seed.size > MAX_WITNESS_REALS) return null
        repeat(2 * atoms.size + seed.size + 3) { arithmetic.step() }
        val payloads = ArrayList<List<PolynomialTerm>>()
        val selected = IntArray(atoms.size) { -1 }
        val weights = DyadicWeights(seed.size)
        var total = 0
        for ((index, atom) in atoms.withIndex()) {
            arithmetic.step()
            val count = counts[index]
            if (count < 0 || count > MAX_WITNESS_TERMS - total) return null
            total += count
            val terms = coupledTerms?.let {
                arithmetic.step()
                it[index]
            } ?: run {
                repeat(count * (seed.size + 1) + 1) { arithmetic.step() }
                chargeDyadicSorting(count, seed.size)
                atom.polynomial.terms
            }
            if (terms.size != count) return null
            arithmetic.step()
            payloads.add(terms)
        }
        work?.dyadicPreExtraction = false
        for (pass in 0 until MAX_WITNESS_PASSES) {
            var changed = false
            for (index in atoms.indices) {
                arithmetic.step()
                val terms = payloads[index]
                if (terms.isEmpty()) {
                    if (!atoms[index].holds(0)) return null
                    continue
                }
                val orientation = dyadicOrientation(atoms[index].relation, terms)
                val chosen = dyadicLeadingTerm(terms, orientation, weights) ?: return null
                arithmetic.step()
                selected[index] = chosen
                changed = dyadicGuide(terms, chosen, orientation, weights, active, fixed, true)
                    ?.let { changed || it } ?: return null
            }
            if (!changed) break
        }
        for (index in atoms.indices) {
            arithmetic.step()
            if (selected[index] < 0) continue
            dyadicGuide(payloads[index], selected[index],
                dyadicOrientation(atoms[index].relation, payloads[index]),
                weights, active, fixed, false) ?: return null
        }
        repeat(seed.size + 1) { arithmetic.step() }
        val base = seed.mapIndexed { id, value ->
            arithmetic.step()
            if (id in active && id !in fixed) dyadicValue(weights[id]) else value
        }
        arithmetic.step()
        return base.takeIf { it.all(arithmetic::admitsWitnessFraction) }
    }

    private fun chargeDyadicSorting(count: Int, coordinates: Int) {
        arithmetic.step()
        require(count in 0..MAX_WITNESS_TERMS && coordinates in 0..MAX_WITNESS_REALS)
        if (count <= 1) return
        var remaining = count - 1
        var levels = 0L
        while (remaining > 0) {
            arithmetic.step()
            levels++
            remaining = remaining ushr 1
        }
        // Cached monomial comparisons visit at most one entry per original coordinate.
        val visits = count.toLong() * levels * (coordinates + 1L)
        check(visits in 0..Int.MAX_VALUE.toLong())
        repeat(visits.toInt()) { arithmetic.step() }
    }

    private fun dyadicOrientation(relation: PolynomialRelation, terms: List<PolynomialTerm>): Int {
        arithmetic.step()
        if (relation == PolynomialRelation.LT || relation == PolynomialRelation.LE) return -1
        if (relation == PolynomialRelation.NE && terms.none {
                arithmetic.step()
                it.coefficient.signum() > 0
            }
        ) return -1
        return 1
    }

    private fun dyadicLeadingTerm(
        terms: List<PolynomialTerm>,
        orientation: Int,
        weights: DyadicWeights,
    ): Int? {
        var selected: Int? = null
        var greatest = Long.MIN_VALUE
        for ((index, term) in terms.withIndex()) {
            arithmetic.step()
            if (term.coefficient.signum() * orientation <= 0) continue
            val weight = weights.weight(term.monomial) ?: return null
            if (selected == null || weight > greatest) {
                arithmetic.step()
                selected = index
                greatest = weight
            }
        }
        return selected
    }

    private data class DyadicWeightEntry(val revision: Long, val value: Long)

    private inner class DyadicWeights(val size: Int) {
        init {
            arithmetic.step()
        }

        private val exponents = IntArray(size)
        private var revision = 0L
        private val cache = HashMap<Monomial, DyadicWeightEntry>()

        operator fun get(variable: Int): Int = exponents[variable]

        fun replace(variable: Int, exponent: Int) {
            arithmetic.step()
            check(revision < Long.MAX_VALUE)
            arithmetic.step()
            exponents[variable] = exponent
            revision++
            arithmetic.step()
        }

        fun weight(monomial: Monomial): Long? {
            arithmetic.step()
            val retained = cache[monomial]
            arithmetic.step()
            if (retained != null && retained.revision == revision) return retained.value
            val variables = monomial.variables
            repeat(variables.size) { arithmetic.step() }
            var weight = 0L
            for (id in variables) {
                arithmetic.step()
                if (id !in exponents.indices) return null
                val degree = monomial.exponent(id).toLong()
                val exponent = exponents[id].toLong()
                if (degree < 0 || exponent !in -(MAX_WITNESS_BITS - 1L)..(MAX_WITNESS_BITS - 1L)) return null
                arithmetic.step()
                // An Int degree times an admitted exponent fits Long before the checked sum.
                val product = degree * exponent
                weight = dyadicSum(weight, product) ?: return null
            }
            arithmetic.step()
            val completed = DyadicWeightEntry(revision, weight)
            arithmetic.step()
            cache[monomial] = completed
            return weight
        }
    }

    private fun dyadicSum(left: Long, right: Long): Long? {
        arithmetic.step()
        if ((right > 0 && left > Long.MAX_VALUE - right) || (right < 0 && left < Long.MIN_VALUE - right)) {
            return null
        }
        return left + right
    }

    private fun dyadicGuide(
        terms: List<PolynomialTerm>,
        chosen: Int,
        orientation: Int,
        weights: DyadicWeights,
        active: Set<Int>,
        fixed: Map<Int, BigFraction>,
        repair: Boolean,
    ): Boolean? {
        var changed = false
        var negatives = 0
        for (term in terms) {
            arithmetic.step()
            if (term.coefficient.signum() * orientation < 0) negatives++
        }
        val positive = terms[chosen]
        for (negative in terms) {
            arithmetic.step()
            if (negative.coefficient.signum() * orientation >= 0) continue
            val margin = dyadicMargin(positive.coefficient, negative.coefficient, negatives)
            val left = weights.weight(positive.monomial) ?: return null
            val right = weights.weight(negative.monomial) ?: return null
            val gap = dyadicSum(left, -right) ?: return null
            if (gap >= margin) continue
            if (!repair) return null
            val deficit = dyadicSum(margin, -gap) ?: return null
            if (!repairDyadicRow(positive.monomial, negative.monomial, deficit,
                    weights, active, fixed)) return null
            changed = true
        }
        return changed
    }

    private fun dyadicMargin(positive: BigFraction, negative: BigFraction, count: Int): Long {
        repeat(5) { arithmetic.step() }
        val coefficientBound = negative.num.magnitudeBitLength().toLong() -
            (negative.den.magnitudeBitLength() - 1L) -
            (positive.num.magnitudeBitLength() - 1L) + positive.den.magnitudeBitLength()
        var remaining = count - 1
        var countBits = 0L
        while (remaining > 0) {
            arithmetic.step()
            countBits++
            remaining = remaining ushr 1
        }
        arithmetic.step()
        return coefficientBound + countBits + 1
    }

    private fun repairDyadicRow(
        positive: Monomial,
        negative: Monomial,
        deficit: Long,
        weights: DyadicWeights,
        active: Set<Int>,
        fixed: Map<Int, BigFraction>,
    ): Boolean {
        var selected = -1
        var greatest = 0L
        var replacement = 0
        for (id in 0 until weights.size) {
            arithmetic.step()
            if (id !in active || id in fixed) continue
            arithmetic.step()
            val coefficient = positive.exponent(id).toLong() - negative.exponent(id)
            val magnitude = if (coefficient < 0) -coefficient else coefficient
            if (magnitude == 0L || magnitude <= greatest) continue
            arithmetic.step()
            val movement = (deficit - 1) / magnitude + 1
            val value = dyadicSum(weights[id].toLong(), if (coefficient > 0) movement else -movement)
                ?: continue
            if (value !in -(MAX_WITNESS_BITS - 1L)..(MAX_WITNESS_BITS - 1L)) continue
            arithmetic.step()
            selected = id
            greatest = magnitude
            replacement = value.toInt()
        }
        if (selected < 0) return false
        weights.replace(selected, replacement)
        return true
    }

    private fun dyadicValue(exponent: Int): BigFraction {
        arithmetic.step()
        require(exponent in -(MAX_WITNESS_BITS - 1)..(MAX_WITNESS_BITS - 1))
        val magnitude = if (exponent < 0) -exponent else exponent
        repeat(magnitude / Long.SIZE_BITS + 3) { arithmetic.step() }
        val power = bigIntOf(1) shl magnitude
        arithmetic.step()
        val result = if (exponent < 0) BigFraction.of(bigIntOf(1), power)
        else BigFraction.of(power, bigIntOf(1))
        arithmetic.step()
        return result
    }
}

internal fun PolynomialArithmetic.admitsWitnessFraction(value: BigFraction): Boolean {
    step()
    return value.num.magnitudeBitLength() <= MAX_WITNESS_BITS &&
        value.den.magnitudeBitLength() <= MAX_WITNESS_BITS
}
