package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.AlgebraicReal
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.arithmetic.polynomial.PolynomialLimits
import com.eignex.klause.arithmetic.polynomial.PolynomialResourceLimit
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter

internal data class PolynomialCoupledStats(
    val supports: Long = 0,
    val combinations: Long = 0,
    val conflicts: Long = 0,
    val repeatedSupports: Long = 0,
    val declined: Long = 0,
    val work: Long = 0,
)

// Every real point satisfying univariate equations lies in the product of their root sets, so a coupled
// predicate failing at each combination refutes the conjunction of that support. A support carries no region,
// condition or snapshot authority of its own; the caller scopes the exclusion it learns from it.
internal class PolynomialCoupledSections(
    private val parent: QfNraWork,
    limits: PolynomialLimits,
    private val maximumWork: Long = MAX_COUPLED_WORK,
) {
    private var supportCount = 0L
    private var combinationCount = 0L
    private var conflictCount = 0L
    private var repeatedCount = 0L
    private var declineCount = 0L
    private var spentWork = 0L
    val stats: PolynomialCoupledStats get() = PolynomialCoupledStats(
        supportCount, combinationCount, conflictCount, repeatedCount, declineCount, spentWork,
    )
    val available: Boolean get() = spentWork < maximumWork
    private var parentStopped = false
    private val meter = WorkMeter {
        spentWork += it
        parent.charge(it)
    }
    private val token = object : Cancellation {
        override fun isCancelled(): Boolean {
            if (parent()) parentStopped = true
            return parentStopped || spentWork >= maximumWork
        }
        override fun deadline() = parent.deadline()
        override fun workMeter(): WorkMeter = meter
    }
    private val arithmetic = PolynomialArithmetic(token, limits)
    private val algebraic = AlgebraicArithmetic(arithmetic)

    fun refute(required: List<PolynomialAtom>, identities: List<Long>, checked: MutableSet<List<Long>>): List<Int>? {
        if (!available || required.size > MAX_COUPLED_PREMISES) return null
        return try {
            arithmetic.step()
            refuteSupports(required, identities, checked)
        } catch (stop: PolynomialInterrupted) {
            if (parentStopped) throw stop
            declineCount++
            null
        } catch (_: PolynomialResourceLimit) {
            declineCount++
            null
        }
    }

    private fun refuteSupports(
        required: List<PolynomialAtom>,
        identities: List<Long>,
        checked: MutableSet<List<Long>>,
    ): List<Int>? {
        val sections = HashMap<Int, Int>()
        val equations = arrayOfNulls<UnaryCellPolynomial>(required.size)
        for ((index, atom) in required.withIndex()) {
            arithmetic.step()
            if (atom.relation != PolynomialRelation.EQ) continue
            val equation = unaryCellPolynomial(atom.polynomial, arithmetic) ?: continue
            equations[index] = equation
            if (equation.variable !in sections) sections[equation.variable] = index
        }
        if (sections.isEmpty()) return null
        val roots = HashMap<Int, MutableList<AlgebraicReal>>()
        for ((index, atom) in required.withIndex()) {
            arithmetic.step()
            val variables = atom.polynomial.variables
            if (variables.size < 2 || variables.any { arithmetic.step(); it !in sections }) continue
            if (!fitsCellLimits(atom.polynomial, arithmetic)) continue
            val support = (variables.map { arithmetic.step(); sections.getValue(it) } + index).sorted()
            if (!checked.add(support.map { arithmetic.step(); identities[it] })) {
                repeatedCount++
                continue
            }
            supportCount++
            for (variable in variables) {
                arithmetic.step()
                val section = sections.getValue(variable)
                val values = roots.getOrPut(variable) {
                    algebraic.roots(checkNotNull(equations[section]).polynomial).toMutableList()
                }
                if (values.isEmpty()) {
                    conflictCount++
                    return listOf(section)
                }
            }
            if (satisfiable(atom, variables, roots)) continue
            conflictCount++
            return support
        }
        return null
    }

    private fun satisfiable(
        atom: PolynomialAtom,
        variables: List<Int>,
        roots: Map<Int, MutableList<AlgebraicReal>>,
    ): Boolean {
        val choice = IntArray(variables.size)
        while (true) {
            arithmetic.step()
            combinationCount++
            if (atom.holds(sign(atom, variables, choice, roots))) return true
            var position = 0
            while (position < variables.size) {
                arithmetic.step()
                choice[position]++
                if (choice[position] < roots.getValue(variables[position]).size) break
                choice[position] = 0
                position++
            }
            if (position == variables.size) return false
        }
    }

    // Bisected isolating intervals usually separate a nonzero value from zero far more cheaply than an exact
    // algebraic image; refinements stay with their root for every later combination.
    private fun sign(
        atom: PolynomialAtom,
        variables: List<Int>,
        choice: IntArray,
        roots: Map<Int, MutableList<AlgebraicReal>>,
    ): Int {
        val point = HashMap<Int, AlgebraicReal>()
        for ((position, variable) in variables.withIndex()) {
            arithmetic.step()
            point[variable] = roots.getValue(variable)[choice[position]]
        }
        repeat(MAX_COUPLED_REFINEMENTS) {
            algebraic.enclosedSignAt(atom.polynomial, point)?.let { return it }
            for ((position, variable) in variables.withIndex()) {
                arithmetic.step()
                val refined = algebraic.refine(point.getValue(variable))
                roots.getValue(variable)[choice[position]] = refined
                point[variable] = refined
            }
        }
        return algebraic.signAt(atom.polynomial, point)
    }
}

private const val MAX_COUPLED_WORK = 16_384L
private const val MAX_COUPLED_PREMISES = 64
private const val MAX_COUPLED_REFINEMENTS = 32
