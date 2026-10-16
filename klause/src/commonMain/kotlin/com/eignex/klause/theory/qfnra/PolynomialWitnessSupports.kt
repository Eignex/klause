package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.AlgebraicReal
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialTerm
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.magnitudeBitLength

internal class PolynomialWitnessSupports(
    private val arithmetic: PolynomialArithmetic,
    private val maximumTerms: Int,
    private val maximumDegree: Int,
    private val maximumBits: Int,
    private val maximumRefinements: Int,
) {
    private data class Term(val coefficient: BigFraction, val powers: List<Pair<Int, Int>>)
    private data class Row(val atom: PolynomialAtom, val terms: List<Term>, val variables: List<Int>)
    private data class Support(val row: Row, val variables: List<Int>)

    fun point(
        atoms: List<PolynomialAtom>,
        payloads: List<List<PolynomialTerm>>,
        realCount: Int,
        fixed: Map<Int, BigFraction>,
        takeAxis: () -> Boolean,
        takeProposal: () -> Boolean,
    ): Map<Int, BigFraction>? {
        if (atoms.size != payloads.size) return null
        val rows = rows(atoms, payloads, realCount) ?: return null
        val point = LinkedHashMap<Int, BigFraction>()
        for (variable in 0 until realCount) {
            arithmetic.step()
            val value = fixed[variable] ?: BigFraction.ZERO
            if (!admitted(value)) return null
            point[variable] = value
        }
        val owners = IntArray(realCount) { arithmetic.step(); -1 }
        val supports = ArrayList<Support>()
        for (row in rows) {
            arithmetic.step()
            if (row.atom.relation != PolynomialRelation.EQ) continue
            val variables = row.variables.filter { arithmetic.step(); it !in fixed }
            if (variables.isEmpty()) continue
            for (variable in variables) {
                arithmetic.step()
                if (owners[variable] >= 0) return null
                owners[variable] = supports.size
            }
            supports.add(Support(row, variables))
        }
        if (supports.size < 2) return null
        val closing = List(supports.size) { arithmetic.step(); ArrayList<Row>() }
        for (row in rows) {
            var last = -1
            for (variable in row.variables) {
                arithmetic.step()
                last = maxOf(last, owners[variable])
            }
            // Coordinates outside equality supports retain their proposed zero or fixed seed.
            if (last < 0) {
                if (!row.atom.holds((evaluate(row.terms, point) ?: return null).signum())) return null
            } else closing[last].add(row)
        }
        val algebraic = AlgebraicArithmetic(arithmetic)
        for ((index, support) in supports.withIndex()) {
            arithmetic.step()
            if (!takeProposal()) return null
            when (holds(closing[index], point)) {
                null -> return null
                true -> continue
                false -> Unit
            }
            var joined = false
            for (variable in support.variables) {
                arithmetic.step()
                if (!takeAxis()) return null
                if (!axisAdmitted(support.row, variable, point)) return null
                val coordinates = LinkedHashMap<Int, AlgebraicReal>()
                for ((id, value) in point) {
                    arithmetic.step()
                    coordinates[id] = algebraic.rational(value)
                }
                val axis = algebraic.specialize(support.row.atom.polynomial, variable, coordinates)
                if (axis.isZero || axis.degree <= 0) continue
                for (root in algebraic.roots(axis)) {
                    arithmetic.step()
                    var refined = root
                    var passes = 0
                    while (refined.rational == null && passes < maximumRefinements) {
                        arithmetic.step()
                        refined = algebraic.refine(refined)
                        passes++
                    }
                    val value = refined.rational ?: continue
                    if (!admitted(value)) return null
                    if (!takeProposal()) return null
                    point[variable] = value
                    when (holds(closing[index], point)) {
                        null -> return null
                        true -> joined = true
                        false -> point[variable] = BigFraction.ZERO
                    }
                    if (joined) break
                }
                if (joined) break
            }
            if (!joined) return null
        }
        repeat(point.size + 1) { arithmetic.step() }
        return point.toMap()
    }

    private fun rows(
        atoms: List<PolynomialAtom>,
        payloads: List<List<PolynomialTerm>>,
        realCount: Int,
    ): List<Row>? {
        var count = 0
        val rows = ArrayList<Row>()
        for ((index, atom) in atoms.withIndex()) {
            arithmetic.step()
            val payload = payloads[index]
            if (payload.size > maximumTerms - count) return null
            count += payload.size
            val terms = ArrayList<Term>()
            val referenced = HashSet<Int>()
            for (term in payload) {
                if (!admitted(term.coefficient)) return null
                val powers = ArrayList<Pair<Int, Int>>()
                for (variable in term.monomial.variables) {
                    arithmetic.step()
                    val degree = term.monomial.exponent(variable)
                    if (variable !in 0 until realCount || degree > maximumDegree) return null
                    powers.add(variable to degree)
                    referenced.add(variable)
                }
                terms.add(Term(term.coefficient, powers))
            }
            val variables = ArrayList<Int>()
            for (variable in 0 until realCount) {
                arithmetic.step()
                if (variable in referenced) variables.add(variable)
            }
            rows.add(Row(atom, terms, variables))
        }
        return rows
    }

    private fun holds(rows: List<Row>, point: Map<Int, BigFraction>): Boolean? {
        for (row in rows) {
            arithmetic.step()
            val value = evaluate(row.terms, point) ?: return null
            if (!row.atom.holds(value.signum())) return false
        }
        return true
    }

    private fun axisAdmitted(row: Row, variable: Int, point: Map<Int, BigFraction>): Boolean {
        val coefficients = LinkedHashMap<Int, BigFraction>()
        for (term in row.terms) {
            arithmetic.step()
            var degree = 0
            var coefficient = term.coefficient
            for ((id, exponent) in term.powers) {
                arithmetic.step()
                if (id == variable) degree = exponent
                else {
                    val factor = power(point.getValue(id), exponent) ?: return false
                    arithmetic.step()
                    coefficient *= factor
                    if (!admitted(coefficient)) return false
                }
            }
            arithmetic.step()
            val sum = (coefficients[degree] ?: BigFraction.ZERO) + coefficient
            if (!admitted(sum)) return false
            coefficients[degree] = sum
        }
        return true
    }

    private fun evaluate(terms: List<Term>, point: Map<Int, BigFraction>): BigFraction? {
        var sum = BigFraction.ZERO
        for (term in terms) {
            arithmetic.step()
            var product = term.coefficient
            for ((variable, exponent) in term.powers) {
                arithmetic.step()
                val factor = power(point.getValue(variable), exponent) ?: return null
                arithmetic.step()
                product *= factor
                if (!admitted(product)) return null
            }
            arithmetic.step()
            sum += product
            if (!admitted(sum)) return null
        }
        return sum
    }

    private fun power(value: BigFraction, exponent: Int): BigFraction? {
        var result = BigFraction.ONE
        var base = value
        var remaining = exponent
        while (remaining != 0) {
            arithmetic.step()
            if (remaining and 1 != 0) {
                result *= base
                if (!admitted(result)) return null
            }
            remaining = remaining ushr 1
            if (remaining != 0) {
                arithmetic.step()
                base *= base
                if (!admitted(base)) return null
            }
        }
        return result
    }

    private fun admitted(value: BigFraction): Boolean {
        arithmetic.step()
        return value.num.magnitudeBitLength() <= maximumBits && value.den.magnitudeBitLength() <= maximumBits
    }
}
