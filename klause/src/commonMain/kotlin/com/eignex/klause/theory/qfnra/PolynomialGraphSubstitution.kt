package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.Polynomial
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialTerm
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.util.magnitudeBitLength

internal class PolynomialGraphDefinition(val atom: Int, val variable: Int, val replacement: Polynomial)

internal fun triangularGraphSections(
    atoms: List<PolynomialAtom>,
    arithmetic: PolynomialArithmetic,
): List<PolynomialGraphDefinition> {
    val selected = HashMap<Int, PolynomialGraphDefinition>()
    for ((index, atom) in atoms.withIndex()) {
        arithmetic.step()
        if (atom.relation != PolynomialRelation.EQ) continue
        var variable = -1
        for (id in atom.polynomial.variables) {
            arithmetic.step()
            variable = maxOf(variable, id)
        }
        if (variable < 0 || variable in selected) continue
        var pivot: PolynomialTerm? = null
        var total = true
        for (term in atom.polynomial.terms) {
            arithmetic.step()
            val exponent = term.monomial.exponent(variable)
            if (exponent == 0) continue
            if (pivot != null || exponent != 1 || term.monomial.variables.size != 1) {
                total = false
                break
            }
            pivot = term
        }
        if (!total || pivot == null) continue
        selected[variable] = graphDefinition(index, variable, atom.polynomial, pivot, arithmetic)
    }
    return selected.values.sortedWith { left, right ->
        arithmetic.step()
        right.variable.compareTo(left.variable)
    }
}

internal class PolynomialGraphPlan(
    val definitions: List<PolynomialGraphDefinition>,
    val candidates: Int,
    val cyclicCoordinates: Int,
    val triangular: Boolean,
)

internal fun acyclicGraphPlan(atoms: List<PolynomialAtom>, arithmetic: PolynomialArithmetic): PolynomialGraphPlan {
    val selected = graphCandidates(atoms, arithmetic)
    val dependencies = selected.mapValues { (_, definition) ->
        definition.replacement.variables.filter { arithmetic.step(); it in selected }
    }
    val dependents = HashMap<Int, MutableList<Int>>()
    for ((variable, inputs) in dependencies) for (dependency in inputs) {
        arithmetic.step()
        dependents.getOrPut(dependency) { ArrayList() }.add(variable)
    }
    val finished = graphFinishOrder(dependencies, arithmetic)
    val visited = HashSet<Int>()
    val cyclic = HashSet<Int>()
    for (start in finished.asReversed()) {
        arithmetic.step()
        if (!visited.add(start)) continue
        val component = ArrayList<Int>()
        val pending = ArrayDeque<Int>()
        pending.addLast(start)
        while (pending.isNotEmpty()) {
            arithmetic.step()
            val variable = pending.removeLast()
            component.add(variable)
            for (dependent in dependents[variable].orEmpty()) {
                arithmetic.step()
                if (visited.add(dependent)) pending.addLast(dependent)
            }
        }
        if (component.size > 1 || start in dependencies.getValue(start)) {
            for (variable in component) {
                arithmetic.step()
                cyclic.add(variable)
            }
        }
    }
    val ordered = finished.asReversed().mapNotNull {
        arithmetic.step()
        if (it in cyclic) null else selected.getValue(it)
    }
    return PolynomialGraphPlan(ordered, selected.size, cyclic.size, false)
}

private fun graphFinishOrder(
    dependencies: Map<Int, List<Int>>,
    arithmetic: PolynomialArithmetic,
): List<Int> {
    val visited = HashSet<Int>()
    val finished = ArrayList<Int>()
    for (start in dependencies.keys) {
        arithmetic.step()
        val pending = ArrayDeque<Pair<Int, Boolean>>()
        pending.addLast(start to false)
        while (pending.isNotEmpty()) {
            arithmetic.step()
            val (variable, combine) = pending.removeLast()
            if (combine) {
                finished.add(variable)
                continue
            }
            if (!visited.add(variable)) continue
            pending.addLast(variable to true)
            for (dependency in dependencies.getValue(variable)) {
                arithmetic.step()
                if (dependency !in visited) pending.addLast(dependency to false)
            }
        }
    }
    return finished
}

private fun graphCandidates(
    atoms: List<PolynomialAtom>,
    arithmetic: PolynomialArithmetic,
): Map<Int, PolynomialGraphDefinition> {
    val occurrences = HashMap<Int, Int>()
    for (atom in atoms) for (variable in atom.polynomial.variables) {
        arithmetic.step()
        occurrences[variable] = (occurrences[variable] ?: 0) + 1
    }
    val selected = LinkedHashMap<Int, PolynomialGraphDefinition>()
    for ((index, atom) in atoms.withIndex()) {
        arithmetic.step()
        if (atom.relation != PolynomialRelation.EQ) continue
        val terms = atom.polynomial.terms
        val counts = HashMap<Int, Int>()
        for (term in terms) for (variable in term.monomial.variables) {
            arithmetic.step()
            counts[variable] = (counts[variable] ?: 0) + 1
        }
        var best: GraphPivot? = null
        for (term in terms) {
            arithmetic.step()
            val variable = term.monomial.variables.singleOrNull() ?: continue
            if (variable in selected || term.monomial.exponent(variable) != 1 || counts[variable] != 1) continue
            val fill = (terms.size - 1).toLong() * ((occurrences[variable] ?: 1) - 1)
            val bits = term.coefficient.num.magnitudeBitLength().toLong() + term.coefficient.den.magnitudeBitLength()
            val candidate = GraphPivot(variable, term, fill, bits)
            if (best == null || candidate.before(best)) best = candidate
        }
        best?.let { selected[it.variable] = graphDefinition(index, it.variable, atom.polynomial, it.term, arithmetic) }
    }
    return selected
}

private class GraphPivot(val variable: Int, val term: PolynomialTerm, val fill: Long, val bits: Long) {
    fun before(other: GraphPivot): Boolean = when {
        fill != other.fill -> fill < other.fill
        bits != other.bits -> bits < other.bits
        else -> variable > other.variable
    }
}

private fun graphDefinition(
    index: Int,
    variable: Int,
    polynomial: Polynomial,
    pivot: PolynomialTerm,
    arithmetic: PolynomialArithmetic,
): PolynomialGraphDefinition {
    val remainder = ArrayList<PolynomialTerm>()
    for (term in polynomial.terms) {
        arithmetic.step()
        if (term != pivot) remainder.add(term)
    }
    val replacement = arithmetic.scale(Polynomial.of(remainder), pivot.coefficient.reciprocal().negated())
    return PolynomialGraphDefinition(index, variable, replacement)
}

internal class PolynomialGraphSubstitution(
    sections: List<PolynomialGraphDefinition>,
    private val arithmetic: PolynomialArithmetic,
) {
    private val definitions = sections.associate { arithmetic.step(); it.variable to it.replacement }
    private val positions = sections.withIndex().associate { arithmetic.step(); it.value.variable to it.index }
    private val closed = HashMap<Int, Polynomial>()

    fun prepare(value: Polynomial): Iterator<Polynomial?> = sequence {
        var result = value
        for (variable in value.variables) {
            arithmetic.step()
            if (variable !in definitions) continue
            val pending = ArrayDeque<Pair<Int, Boolean>>()
            pending.addLast(variable to false)
            while (pending.isNotEmpty()) {
                arithmetic.step()
                val (id, combine) = pending.removeLast()
                if (id in closed) continue
                val replacement = definitions.getValue(id)
                if (!combine) {
                    pending.addLast(id to true)
                    for (dependency in replacement.variables) {
                        arithmetic.step()
                        if (dependency in definitions && dependency !in closed) {
                            check(positions.getValue(dependency) > positions.getValue(id))
                            pending.addLast(dependency to false)
                        }
                    }
                    yield(null)
                    continue
                }
                var expanded = replacement
                for (dependency in replacement.variables) {
                    arithmetic.step()
                    if (dependency !in definitions) continue
                    expanded = arithmetic.substitute(expanded, dependency, closed.getValue(dependency))
                    yield(null)
                }
                closed[id] = expanded
                yield(null)
            }
            result = arithmetic.substitute(result, variable, closed.getValue(variable))
            yield(null)
        }
        arithmetic.step()
        yield(result)
    }.iterator()
}
