package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.AlgebraicReal
import com.eignex.klause.arithmetic.polynomial.Monomial
import com.eignex.klause.arithmetic.polynomial.Polynomial
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.ir.PolynomialRelation

internal class PolynomialAffineReduction private constructor(
    val atoms: List<PolynomialAtom>,
    private val fibres: List<AffineFibre>,
    val inconsistent: Boolean,
) {
    val eliminatedCount: Int get() = fibres.size

    fun reconstruct(
        assignment: QfNraAssignment,
        arithmetic: PolynomialArithmetic,
        algebraic: AlgebraicArithmetic,
    ): Map<Int, AlgebraicReal> {
        val point = HashMap<Int, AlgebraicReal>()
        for (id in 0 until assignment.realCount) {
            arithmetic.step()
            point[id] = assignment.realValue(id)
        }
        for (fibre in fibres.asReversed()) {
            arithmetic.step()
            val graph = fibre.point
            if (graph != null) {
                point[fibre.variable] = algebraic.evaluate(graph, point)
                continue
            }
            val lower = endpoint(fibre.lower, point, true, arithmetic, algebraic)
            val upper = endpoint(fibre.upper, point, false, arithmetic, algebraic)
            check(lower != null || upper != null)
            val comparison = if (lower != null && upper != null) algebraic.compare(lower.value, upper.value) else -1
            check(comparison < 0 || (comparison == 0 && !checkNotNull(lower).strict && !checkNotNull(upper).strict))
            point[fibre.variable] = if (comparison == 0) checkNotNull(lower).value
            else algebraic.rational(algebraic.sampleBetween(lower?.value, upper?.value))
        }
        arithmetic.step()
        return point
    }

    companion object {
        fun prepare(
            formula: PolynomialFormula,
            region: PolynomialBooleanRegion,
            realCount: Int,
            arithmetic: PolynomialArithmetic,
            onGraph: (() -> Unit)? = null,
        ): Iterator<PolynomialAffineReduction?> = sequence {
            var current = region.atoms.map { (index, truth) ->
                arithmetic.step()
                val atom = formula.atoms[index]
                if (truth) atom else PolynomialAtom(atom.polynomial, complement(atom.relation))
            }
            val signChecking = preparePolynomialSignRefutation(current, realCount, arithmetic)
            var signConflict: Boolean? = null
            while (signConflict == null) {
                signConflict = signChecking.next()
                if (signConflict == null) yield(null)
            }
            if (signConflict) {
                arithmetic.step()
                yield(PolynomialAffineReduction(current, emptyList(), true))
                return@sequence
            }
            val fibres = ArrayList<AffineFibre>()
            var inconsistent = false
            var normalizeConjunction = true
            var graphChanges = false
            while (true) {
                if (normalizeConjunction) {
                    val preparation = preparePolynomialConjunction(current, arithmetic)
                    var conjunction: PolynomialConjunction? = null
                    while (conjunction == null) {
                        conjunction = preparation.next()
                        if (conjunction == null) yield(null)
                    }
                    inconsistent = conjunction.inconsistent
                    val changed = conjunction.changed
                    current = conjunction.atoms
                    if (inconsistent || (!changed && graphChanges)) break
                    normalizeConjunction = false
                    graphChanges = false
                }
                val equations = current.mapNotNull { atom ->
                    arithmetic.step()
                    if (atom.relation == PolynomialRelation.EQ) atom.polynomial else null
                }
                if (inconsistentEquations(equations, arithmetic) || zeroContradiction(current, arithmetic)) {
                    inconsistent = true
                    break
                }
                val graph = equationalSection(equations, current.map { arithmetic.step(); it.polynomial }, arithmetic)
                if (graph == null) {
                    if (!graphChanges) break
                    normalizeConjunction = true
                    continue
                }
                val rewritten = ArrayList<PolynomialAtom>()
                for (atom in current) {
                    arithmetic.step()
                    val polynomial = if (graph.variable in atom.polynomial.variables) {
                        arithmetic.substitute(atom.polynomial, graph.variable, graph.replacement)
                    } else atom.polynomial
                    if (polynomial.isZero && atom.holds(0)) continue
                    rewritten.add(PolynomialAtom(polynomial, atom.relation))
                    yield(null)
                }
                arithmetic.step()
                fibres.add(AffineFibre(graph.variable, graph.replacement, emptyList(), emptyList()))
                current = rewritten
                graphChanges = true
                onGraph?.invoke()
                yield(null)
            }
            if (!inconsistent) {
                val consequenceChecking = preparePolynomialConsequenceRefutation(current, arithmetic)
                var consequenceConflict: Boolean? = null
                while (consequenceConflict == null) {
                    consequenceConflict = consequenceChecking.next()
                    if (consequenceConflict == null) yield(null)
                }
                inconsistent = consequenceConflict
            }
            while (!inconsistent) {
                val variable = affineCoordinate(current, realCount, arithmetic)
                if (variable < 0) break
                val monomial = Monomial.of(mapOf(variable to 1))
                val coordinate = Polynomial.variable(variable)
                val lower = ArrayList<AffineBound>()
                val upper = ArrayList<AffineBound>()
                val next = ArrayList<PolynomialAtom>()
                for (atom in current) {
                    arithmetic.step()
                    val coefficient = atom.polynomial.coefficient(monomial)
                    if (coefficient.isZero) {
                        next.add(atom)
                        continue
                    }
                    val remainder = arithmetic.subtract(atom.polynomial, arithmetic.scale(coordinate, coefficient))
                    val bound = AffineBound(arithmetic.scale(remainder, coefficient.reciprocal().negated()),
                        atom.relation == PolynomialRelation.GT || atom.relation == PolynomialRelation.LT)
                    val increasing = atom.relation == PolynomialRelation.GE || atom.relation == PolynomialRelation.GT
                    if ((coefficient.signum() > 0) == increasing) lower.add(bound) else upper.add(bound)
                }
                // Pairwise bounds characterize the fibre, including strict or coincident endpoints.
                for (a in lower) for (b in upper) {
                    arithmetic.step()
                    next.add(PolynomialAtom(arithmetic.subtract(b.polynomial, a.polynomial),
                        if (a.strict || b.strict) PolynomialRelation.GT else PolynomialRelation.GE))
                    yield(null)
                }
                arithmetic.step()
                fibres.add(AffineFibre(variable, null, lower.toList(), upper.toList()))
                current = next
                yield(null)
            }
            if (!inconsistent) {
                val magnitudeChecking = preparePolynomialMagnitudeRefutation(current, arithmetic)
                var magnitudeConflict: Boolean? = null
                while (magnitudeConflict == null) {
                    magnitudeConflict = magnitudeChecking.next()
                    if (magnitudeConflict == null) yield(null)
                }
                inconsistent = magnitudeConflict
            }
            arithmetic.step()
            yield(PolynomialAffineReduction(current.toList(), fibres.toList(), inconsistent))
        }.iterator()
    }
}

private class AffineBound(val polynomial: Polynomial, val strict: Boolean)
private class AffineFibre(
    val variable: Int,
    val point: Polynomial?,
    val lower: List<AffineBound>,
    val upper: List<AffineBound>,
)
private class AffineEndpoint(val value: AlgebraicReal, val strict: Boolean)

private fun zeroContradiction(atoms: List<PolynomialAtom>, arithmetic: PolynomialArithmetic): Boolean {
    for (atom in atoms) {
        arithmetic.step()
        if (atom.polynomial.isZero && !atom.holds(0)) return true
    }
    return false
}

private fun endpoint(
    bounds: List<AffineBound>,
    point: Map<Int, AlgebraicReal>,
    lower: Boolean,
    arithmetic: PolynomialArithmetic,
    algebraic: AlgebraicArithmetic,
): AffineEndpoint? {
    var result: AffineEndpoint? = null
    for (bound in bounds) {
        arithmetic.step()
        val value = algebraic.evaluate(bound.polynomial, point)
        val previous = result
        if (previous == null) {
            result = AffineEndpoint(value, bound.strict)
            continue
        }
        val comparison = algebraic.compare(value, previous.value)
        if (if (lower) comparison > 0 else comparison < 0) result = AffineEndpoint(value, bound.strict)
        else if (comparison == 0) result = AffineEndpoint(previous.value, previous.strict || bound.strict)
    }
    return result
}

private fun affineCoordinate(atoms: List<PolynomialAtom>, realCount: Int, arithmetic: PolynomialArithmetic): Int {
    val blocked = BooleanArray(realCount) { arithmetic.step(); false }
    val lower = IntArray(realCount) { arithmetic.step(); 0 }
    val upper = IntArray(realCount) { arithmetic.step(); 0 }
    for (atom in atoms) {
        arithmetic.step()
        val inequality = atom.relation != PolynomialRelation.EQ && atom.relation != PolynomialRelation.NE
        val increasing = atom.relation == PolynomialRelation.GE || atom.relation == PolynomialRelation.GT
        for (term in atom.polynomial.terms) {
            arithmetic.step()
            val variables = term.monomial.variables
            val affine = inequality && variables.size == 1 && term.monomial.exponent(variables.single()) == 1
            for (variable in variables) {
                arithmetic.step()
                if (!affine) blocked[variable] = true
                else if ((term.coefficient.signum() > 0) == increasing) lower[variable]++ else upper[variable]++
            }
        }
    }
    var best = -1
    var bestPairs = Long.MAX_VALUE
    var bestOccurrences = -1
    for (variable in 0 until realCount) {
        arithmetic.step()
        val occurrences = lower[variable].toLong() + upper[variable]
        val pairs = lower[variable].toLong() * upper[variable]
        // Larger affine projections retain their original constraints for CAD.
        if (blocked[variable] || occurrences == 0L || pairs > occurrences) continue
        if (pairs < bestPairs || (pairs == bestPairs && occurrences > bestOccurrences)) {
            best = variable
            bestPairs = pairs
            bestOccurrences = occurrences.toInt()
        }
    }
    return best
}

private fun complement(relation: PolynomialRelation): PolynomialRelation = when (relation) {
    PolynomialRelation.EQ -> PolynomialRelation.NE
    PolynomialRelation.NE -> PolynomialRelation.EQ
    PolynomialRelation.LT -> PolynomialRelation.GE
    PolynomialRelation.LE -> PolynomialRelation.GT
    PolynomialRelation.GT -> PolynomialRelation.LE
    PolynomialRelation.GE -> PolynomialRelation.LT
}
