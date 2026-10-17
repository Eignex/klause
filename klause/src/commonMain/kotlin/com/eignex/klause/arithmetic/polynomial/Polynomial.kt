package com.eignex.klause.arithmetic.polynomial

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.binarySearchInt

internal class Monomial private constructor(private val powers: Map<Int, Int>) : Comparable<Monomial> {
    private val coordinateIds = powers.keys.toIntArray().apply { sortDescending() }
    private val degrees = IntArray(coordinateIds.size) { powers.getValue(coordinateIds[it]) }
    private val structuralHash = run {
        var hash = 1
        for (index in coordinateIds.indices) {
            hash = 31 * hash + coordinateIds[index]
            hash = 31 * hash + degrees[index]
        }
        hash
    }

    val variables: List<Int> get() = List(coordinateIds.size) { coordinateIds[coordinateIds.lastIndex - it] }
    fun exponent(variable: Int): Int = powers[variable] ?: 0

    fun without(variable: Int): Monomial = of(powers.filterKeys { it != variable })

    fun times(other: Monomial): Monomial = of(buildMap {
        putAll(powers)
        for ((variable, degree) in other.powers) {
            val previous = get(variable) ?: 0
            require(degree <= Int.MAX_VALUE - previous) { "monomial degree overflow" }
            put(variable, previous + degree)
        }
    })

    fun quotient(divisor: Monomial): Monomial? {
        if (divisor.powers.any { (variable, degree) -> exponent(variable) < degree }) return null
        return of(powers.mapValues { (variable, degree) -> degree - divisor.exponent(variable) })
    }

    override fun compareTo(other: Monomial): Int {
        var left = 0
        var right = 0
        while (left < coordinateIds.size && right < other.coordinateIds.size) {
            if (coordinateIds[left] > other.coordinateIds[right]) return 1
            if (coordinateIds[left] < other.coordinateIds[right]) return -1
            val comparison = degrees[left].compareTo(other.degrees[right])
            if (comparison != 0) return comparison
            left++
            right++
        }
        return when {
            left < coordinateIds.size -> 1
            right < other.coordinateIds.size -> -1
            else -> 0
        }
    }

    override fun equals(other: Any?): Boolean = other is Monomial && powers == other.powers
    override fun hashCode(): Int = structuralHash

    companion object {
        val ONE = Monomial(emptyMap())

        fun of(powers: Map<Int, Int>): Monomial {
            require(powers.all { (variable, degree) -> variable >= 0 && degree >= 0 })
            return Monomial(powers.filterValues { it != 0 })
        }
    }
}

internal data class PolynomialTerm(val monomial: Monomial, val coefficient: BigFraction)

internal class Polynomial private constructor(private val coefficients: Map<Monomial, BigFraction>) {
    private val orderedTerms = coefficients.entries.sortedBy { it.key }
        .map { PolynomialTerm(it.key, it.value) }.toTypedArray()
    private val coordinates = if (orderedTerms.isEmpty() ||
        (orderedTerms.size == 1 && orderedTerms[0].monomial == Monomial.ONE)
    ) null else run {
        val degrees = HashMap<Int, Int>()
        for (term in orderedTerms) for (variable in term.monomial.variables) {
            degrees[variable] = maxOf(degrees[variable] ?: 0, term.monomial.exponent(variable))
        }
        val ids = degrees.keys.sorted().toIntArray()
        ids to IntArray(ids.size) { degrees.getValue(ids[it]) }
    }

    val terms: List<PolynomialTerm> get() = orderedTerms.toList()
    val isZero: Boolean get() = coefficients.isEmpty()
    val variables: List<Int> get() = coordinates?.first?.let { ids -> List(ids.size) { ids[it] } } ?: emptyList()
    val mainVariable: Int? get() = coordinates?.first?.lastOrNull()

    fun degree(variable: Int): Int {
        val captured = coordinates ?: return if (isZero) -1 else 0
        val index = captured.first.binarySearchInt(variable)
        return if (index < 0) 0 else captured.second[index]
    }

    fun containsVariable(variable: Int): Boolean {
        val ids = coordinates?.first ?: return false
        return ids.binarySearchInt(variable) >= 0
    }

    fun coefficient(monomial: Monomial): BigFraction = coefficients[monomial] ?: BigFraction.ZERO

    override fun equals(other: Any?): Boolean = other is Polynomial && coefficients == other.coefficients
    override fun hashCode(): Int = coefficients.hashCode()

    companion object {
        val ZERO = Polynomial(emptyMap())
        val ONE = constant(BigFraction.ONE)

        fun constant(value: BigFraction): Polynomial = of(listOf(PolynomialTerm(Monomial.ONE, value)))

        fun variable(variable: Int): Polynomial = of(
            listOf(PolynomialTerm(Monomial.of(mapOf(variable to 1)), BigFraction.ONE)),
        )

        fun of(terms: List<PolynomialTerm>): Polynomial {
            val merged = mutableMapOf<Monomial, BigFraction>()
            for ((monomial, coefficient) in terms) {
                merged[monomial] = (merged[monomial] ?: BigFraction.ZERO) + coefficient
            }
            return Polynomial(merged.filterValues { !it.isZero })
        }
    }
}
