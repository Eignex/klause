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
    val preparedSupports: Long = 0,
    val jointSupports: Long = 0,
)

// Every real point satisfying univariate equations lies in the product of their root sets, so a coupled
// predicate failing at each combination refutes the conjunction of that support. A support carries no region,
// condition or snapshot authority of its own; the caller scopes the exclusion it learns from it.
internal class PolynomialCoupledSections(
    private val parent: QfNraWork,
    limits: PolynomialLimits,
    private val maximumWork: Long = MAX_COUPLED_WORK,
    private val prepareSupports: Boolean = true,
    private val joint: Boolean = true,
) {
    private var supportCount = 0L
    private var combinationCount = 0L
    private var conflictCount = 0L
    private var repeatedCount = 0L
    private var declineCount = 0L
    private var spentWork = 0L
    private var preparedCount = 0L
    private var jointCount = 0L
    val stats: PolynomialCoupledStats get() = PolynomialCoupledStats(
        supportCount, combinationCount, conflictCount, repeatedCount, declineCount, spentWork,
        preparedCount, jointCount,
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
            val direct = required.mapIndexed { index, atom ->
                arithmetic.step()
                PolynomialCoupledSupport(atom, setOf(index))
            }
            refuteSupports(direct, identities, checked, prepared = false)?.let { return it }
            if (!prepareSupports) return null
            val prepared = prepareCoupledSupport(direct, arithmetic) ?: return null
            refuteSupports(prepared, identities, checked, prepared = true)
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
        required: List<PolynomialCoupledSupport>,
        identities: List<Long>,
        checked: MutableSet<List<Long>>,
        prepared: Boolean,
    ): List<Int>? {
        val sections = HashMap<Int, Int>()
        val equations = arrayOfNulls<UnaryCellPolynomial>(required.size)
        val candidates = ArrayList<Int>()
        for ((index, entry) in required.withIndex()) {
            arithmetic.step()
            val atom = entry.atom
            if (atom.relation != PolynomialRelation.EQ) continue
            val equation = unaryCellPolynomial(atom.polynomial, arithmetic) ?: continue
            equations[index] = equation
            if (equation.variable !in sections) sections[equation.variable] = index
        }
        if (sections.isEmpty()) return null
        val roots = HashMap<Int, MutableList<AlgebraicReal>>()
        for ((index, entry) in required.withIndex()) {
            arithmetic.step()
            val variables = entry.atom.polynomial.variables
            if (variables.size < 2 || variables.any { arithmetic.step(); it !in sections }) continue
            if (!fitsCellLimits(entry.atom.polynomial, arithmetic)) continue
            candidates.add(index)
            checkSupport(listOf(index), required, identities, checked, sections, equations, roots, prepared)
                ?.let { return it }
        }
        if (!joint || candidates.size < 2) return null
        val remaining = candidates.toMutableList()
        while (remaining.isNotEmpty()) {
            arithmetic.step()
            val connected = arrayListOf(remaining.removeAt(0))
            val variables = required[connected.first()].atom.polynomial.variables.toMutableSet()
            var changed = true
            while (changed) {
                arithmetic.step()
                changed = false
                val iterator = remaining.iterator()
                while (iterator.hasNext()) {
                    arithmetic.step()
                    val index = iterator.next()
                    val dependencies = required[index].atom.polynomial.variables
                    if (dependencies.any { arithmetic.step(); it in variables }) {
                        for (variable in dependencies) { arithmetic.step(); variables.add(variable) }
                        connected.add(index)
                        iterator.remove()
                        changed = true
                    }
                }
            }
            if (connected.size > 1) {
                checkSupport(connected, required, identities, checked, sections, equations, roots, prepared)
                    ?.let { return it }
            }
        }
        return null
    }

    private fun checkSupport(
        predicates: List<Int>,
        required: List<PolynomialCoupledSupport>,
        identities: List<Long>,
        checked: MutableSet<List<Long>>,
        sections: Map<Int, Int>,
        equations: Array<UnaryCellPolynomial?>,
        roots: MutableMap<Int, MutableList<AlgebraicReal>>,
        prepared: Boolean,
    ): List<Int>? {
        val variables = LinkedHashSet<Int>()
        for (index in predicates) for (variable in required[index].atom.polynomial.variables) {
            arithmetic.step()
            variables.add(variable)
        }
        val support = LinkedHashSet<Int>()
        for (index in variables.map { arithmetic.step(); sections.getValue(it) } + predicates) {
            for (premise in required[index].premises) { arithmetic.step(); support.add(premise) }
        }
        val ordered = support.sorted()
        repeat(ordered.size) { arithmetic.step() }
        val key = buildList {
            add(if (prepared) -1L else -2L)
            add(if (predicates.size > 1) -1L else -2L)
            for (index in ordered) { arithmetic.step(); add(identities[index]) }
        }
        if (!checked.add(key)) {
            repeatedCount++
            return null
        }
        supportCount++
        if (prepared) preparedCount++
        if (predicates.size > 1) jointCount++
        for (variable in variables) {
            arithmetic.step()
            val section = sections.getValue(variable)
            val values = roots.getOrPut(variable) {
                algebraic.roots(checkNotNull(equations[section]).polynomial).toMutableList()
            }
            if (values.isEmpty()) {
                conflictCount++
                return required[section].premises.sorted()
            }
        }
        val atoms = predicates.map { arithmetic.step(); required[it].atom }
        if (satisfiable(atoms, variables.toList(), roots)) return null
        conflictCount++
        return ordered
    }

    private fun satisfiable(
        atoms: List<PolynomialAtom>,
        variables: List<Int>,
        roots: Map<Int, MutableList<AlgebraicReal>>,
    ): Boolean {
        val choice = IntArray(variables.size)
        while (true) {
            arithmetic.step()
            combinationCount++
            if (atoms.all { arithmetic.step(); it.holds(sign(it, variables, choice, roots)) }) return true
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
