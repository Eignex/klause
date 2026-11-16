package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.AffineLineEvaluation
import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.AlgebraicReal
import com.eignex.klause.arithmetic.polynomial.Polynomial
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialResourceLimit
import com.eignex.klause.theory.qfnra.PolynomialSigns.ALL_SIGNS
import com.eignex.klause.theory.qfnra.PolynomialSigns.NEGATIVE_SIGN
import com.eignex.klause.theory.qfnra.PolynomialSigns.POSITIVE_SIGN
import com.eignex.klause.theory.qfnra.PolynomialSigns.ZERO_SIGN
import com.eignex.klause.theory.qfnra.PolynomialSigns.relationSigns
import com.eignex.util.collections.IntArrayDeque
import com.eignex.util.numbers.BigFraction

// Depth-first lifting over cylindrical algebraic decomposition cells: per coordinate, the projection
// polynomials' roots split the line into sections and sectors, each sampled once. Collins, "Quantifier
// elimination for real closed fields by cylindrical algebraic decomposition", LNCS 33, 1975.
// https://doi.org/10.1007/3-540-07407-4_17
internal fun searchCells(
    formula: PolynomialFormula,
    region: PolynomialBooleanRegion,
    projection: PolynomialProjection,
    boolCount: Int,
    arithmetic: PolynomialArithmetic,
    algebraic: AlgebraicArithmetic,
    work: QfNraWork,
    fixed: Map<Int, BigFraction>,
    family: Set<Polynomial>? = null,
    capture: PolynomialFailureCapture? = null,
    snapshot: Long = 0,
    refinement: PolynomialCellRefinement.PolynomialCellRegion? = null,
    feedback: ((PolynomialAtom, Map<Int, AlgebraicReal>) -> Boolean)? = null,
): QfNraAssignment? {
    val coordinates = HashMap<Int, AlgebraicReal>()
    if (projection.variableCount == 0) {
        return checkRegionSample(formula, region, coordinates, boolCount, arithmetic, algebraic)
    }
    val independent = if (family == null) emptySet()
    else independentRegionAtoms(formula, region, family, fixed, arithmetic)
    val projectedVariables = family?.takeIf { refinement != null }?.flatMap { polynomial ->
        arithmetic.step()
        polynomial.variables
    }?.toSet()
    val atomLevels = region.atoms.keys.associateWith { index ->
        var level = -1
        for (variable in formula.atoms[index].polynomial.variables) {
            arithmetic.step()
            level = maxOf(level, projection.level(variable))
        }
        level
    }
    val frames = ArrayDeque<CadFrame>()
    frames.addLast(liftFrame(projection, 0, coordinates, arithmetic, algebraic, fixed))
    while (frames.isNotEmpty()) {
        arithmetic.step()
        val frame = frames.last()
        if (frame.nextCell >= frame.cellCount) {
            coordinates.remove(frame.variable)
            frames.removeLast()
            continue
        }
        val cell = frame.nextCell++
        val root = (cell / 2).toInt()
        var coordinate = frame.point ?: if (frame.rootsOnly) frame.roots[cell.toInt()]
        else if (cell and 1L != 0L) frame.roots[root] else {
            algebraic.rational(algebraic.sampleBetween(frame.roots.getOrNull(root - 1), frame.roots.getOrNull(root)))
        }
        if (refinement != null && projectedVariables != null &&
            frame.variable !in projectedVariables && frame.variable !in fixed
        ) coordinate = refinement.repair(frame.variable, coordinate) ?: coordinate
        coordinates[frame.variable] = coordinate
        if (work.liftedCells < Long.MAX_VALUE) work.liftedCells++
        val rejected = rejectedRegionAtom(formula, region, coordinates, arithmetic, algebraic, atomLevels, feedback)
        if (rejected != null) {
            capture?.rejected(formula, region, coordinates, rejected, snapshot, work.spentWork, arithmetic)
            // Unprojected coordinates have one fixed sector sample throughout this family's CAD.
            if (rejected in independent) return null
            continue
        }
        if (frame.level + 1 < projection.variableCount) {
            frames.addLast(liftFrame(projection, frame.level + 1, coordinates, arithmetic, algebraic, fixed))
        } else {
            return checkRegionSample(formula, region, coordinates, boolCount, arithmetic, algebraic)
        }
    }
    return null
}

private fun independentRegionAtoms(
    formula: PolynomialFormula,
    region: PolynomialBooleanRegion,
    family: Set<Polynomial>,
    fixed: Map<Int, BigFraction>,
    arithmetic: PolynomialArithmetic,
): Set<Int> {
    val projectedVariables = HashSet<Int>()
    for (polynomial in family) {
        for (variable in polynomial.variables) {
            arithmetic.step()
            projectedVariables.add(variable)
        }
    }
    val independent = HashSet<Int>()
    for (index in region.atoms.keys) {
        arithmetic.step()
        val polynomial = formula.atoms[index].polynomial
        if (polynomial !in family && polynomial.variables.none {
                arithmetic.step()
                it in projectedVariables && it !in fixed
            }
        ) independent.add(index)
    }
    return independent
}

private class CadFrame(
    val level: Int,
    val variable: Int,
    val roots: List<AlgebraicReal>,
    val point: AlgebraicReal? = null,
    val rootsOnly: Boolean = false,
) {
    val cellCount: Long = if (point != null) 1 else if (rootsOnly) roots.size.toLong() else 2L * roots.size + 1
    var nextCell: Long = 0
}

private fun liftFrame(
    projection: PolynomialProjection,
    level: Int,
    coordinates: Map<Int, AlgebraicReal>,
    arithmetic: PolynomialArithmetic,
    algebraic: AlgebraicArithmetic,
    fixed: Map<Int, BigFraction>,
): CadFrame {
    val variable = projection.variable(level)
    fixed[variable]?.let { return CadFrame(level, variable, emptyList(), algebraic.rational(it)) }
    projection.section(level)?.let {
        return CadFrame(level, variable, emptyList(), algebraic.evaluate(it, coordinates))
    }
    projection.rootSection(level)?.let {
        val specialized = algebraic.specialize(it.polynomial, variable, coordinates)
        // A lost leading degree violates this region's source nonzero premises.
        val roots = if (specialized.degree == it.degree) algebraic.roots(specialized) else emptyList()
        return CadFrame(level, variable, roots, rootsOnly = true)
    }
    val roots = mutableListOf<AlgebraicReal>()
    for (polynomial in projection.polynomials(level)) {
        arithmetic.step()
        val specialized = algebraic.specialize(polynomial, variable, coordinates)
        if (!specialized.isZero) roots.addAll(algebraic.roots(specialized))
    }
    roots.sortWith(Comparator { left, right -> algebraic.compare(left, right) })
    val distinct = mutableListOf<AlgebraicReal>()
    for (root in roots) {
        arithmetic.step()
        if (distinct.isEmpty() || algebraic.compare(distinct.last(), root) != 0) distinct.add(root)
    }
    return CadFrame(level, variable, distinct)
}

internal fun regionFeasible(
    formula: PolynomialFormula,
    region: PolynomialBooleanRegion,
    coordinates: Map<Int, AlgebraicReal>,
    arithmetic: PolynomialArithmetic,
    algebraic: AlgebraicArithmetic,
    atomLevels: Map<Int, Int>? = null,
): Boolean = rejectedRegionAtom(formula, region, coordinates, arithmetic, algebraic, atomLevels) == null

private fun rejectedRegionAtom(
    formula: PolynomialFormula,
    region: PolynomialBooleanRegion,
    coordinates: Map<Int, AlgebraicReal>,
    arithmetic: PolynomialArithmetic,
    algebraic: AlgebraicArithmetic,
    atomLevels: Map<Int, Int>? = null,
    feedback: ((PolynomialAtom, Map<Int, AlgebraicReal>) -> Boolean)? = null,
): Int? {
    for ((index, required) in region.atoms) {
        arithmetic.step()
        val atom = formula.atoms[index]
        val level = atomLevels?.getValue(index) ?: atom.polynomial.mainVariable
        if (level != null && level >= coordinates.size) {
            if (atomLevels != null) {
                val signs = try {
                    partialRegionSigns(atom.polynomial, coordinates, arithmetic, algebraic)
                } catch (_: PolynomialResourceLimit) {
                    ALL_SIGNS
                }
                val allowed = relationSigns(atom.relation)
                if (signs and (if (required) allowed else ALL_SIGNS xor allowed) == 0) return index
            }
            continue
        }
        if (required && feedback?.invoke(atom, coordinates) == true) return index
        val sign = algebraic.signAt(atom.polynomial, coordinates)
        if (atom.holds(sign) != required) return index
    }
    return null
}

private fun partialRegionSigns(
    polynomial: Polynomial,
    coordinates: Map<Int, AlgebraicReal>,
    arithmetic: PolynomialArithmetic,
    algebraic: AlgebraicArithmetic,
): Int {
    var constant = algebraic.rational(BigFraction.ZERO)
    var positiveTerms = false
    var negativeTerms = false
    for ((monomial, coefficient) in polynomial.terms) {
        arithmetic.step()
        var value = algebraic.rational(coefficient)
        var pending = false
        var odd = false
        for (variable in monomial.variables) {
            arithmetic.step()
            val exponent = monomial.exponent(variable)
            val coordinate = coordinates[variable]
            if (coordinate == null) {
                pending = true
                if (exponent and 1 != 0) odd = true
            } else {
                value = algebraic.multiply(value, algebraic.power(coordinate, exponent))
            }
        }
        val sign = algebraic.sign(value)
        if (sign == 0) continue
        if (!pending) {
            constant = algebraic.add(constant, value)
        } else {
            if (odd) return ALL_SIGNS
            if (sign > 0) positiveTerms = true else negativeTerms = true
        }
    }
    return evenCompletionSigns(algebraic.sign(constant), positiveTerms, negativeTerms)
}

private fun evenCompletionSigns(constant: Int, positiveTerms: Boolean, negativeTerms: Boolean): Int = when {
    !positiveTerms && !negativeTerms -> when {
        constant < 0 -> NEGATIVE_SIGN
        constant > 0 -> POSITIVE_SIGN
        else -> ZERO_SIGN
    }
    !negativeTerms && constant >= 0 -> if (constant > 0) POSITIVE_SIGN else ZERO_SIGN or POSITIVE_SIGN
    !positiveTerms && constant <= 0 -> if (constant < 0) NEGATIVE_SIGN else ZERO_SIGN or NEGATIVE_SIGN
    else -> ALL_SIGNS
}

internal fun checkRegionSample(
    formula: PolynomialFormula,
    region: PolynomialBooleanRegion,
    coordinates: Map<Int, AlgebraicReal>,
    boolCount: Int,
    arithmetic: PolynomialArithmetic,
    algebraic: AlgebraicArithmetic,
): QfNraAssignment {
    val atoms = IntArray(formula.atoms.size) { index ->
        arithmetic.step()
        val atom = formula.atoms[index]
        val sign = algebraic.signAt(atom.polynomial, coordinates)
        if (atom.holds(sign)) PolynomialFormula.TRUE else PolynomialFormula.FALSE
    }
    val bools = BooleanArray(boolCount) {
        arithmetic.step()
        region.bools[it] ?: false
    }
    val values = IntArray(boolCount) {
        arithmetic.step()
        if (bools[it]) PolynomialFormula.TRUE else PolynomialFormula.FALSE
    }
    check(formula.truth(values, atoms, arithmetic) == PolynomialFormula.TRUE)
    arithmetic.step()
    return QfNraAssignment(bools, List(coordinates.size) {
        arithmetic.step()
        coordinates.getValue(it)
    })
}

internal fun checkSample(
    formula: PolynomialFormula,
    coordinates: Map<Int, AlgebraicReal>,
    boolCount: Int,
    arithmetic: PolynomialArithmetic,
    algebraic: AlgebraicArithmetic,
    rejected: ((AlgebraicArithmetic.PointEvaluation, IntArray) -> Unit)? = null,
    reusePowers: Boolean = false,
): QfNraAssignment? {
    val evaluation = if (reusePowers) algebraic.pointEvaluation(coordinates) else null
    val signs = if (rejected == null) null else {
        repeat(formula.atoms.size + 1) { arithmetic.step() }
        IntArray(formula.atoms.size)
    }
    val atomValues = IntArray(formula.atoms.size) { index ->
        arithmetic.step()
        val atom = formula.atoms[index]
        val sign = evaluation?.signAt(atom.polynomial) ?: algebraic.signAt(atom.polynomial, coordinates)
        if (signs != null) {
            arithmetic.step()
            signs[index] = sign
        }
        if (atom.holds(sign)) {
            PolynomialFormula.TRUE
        } else PolynomialFormula.FALSE
    }
    val bools = checkedBooleans(formula, boolCount, atomValues, arithmetic) ?: run {
        if (signs != null) {
            arithmetic.step()
            rejected?.invoke(requireNotNull(evaluation), signs)
        }
        return null
    }
    arithmetic.step()
    return QfNraAssignment(bools, List(evaluation?.coordinateCount ?: coordinates.size) {
        arithmetic.step()
        evaluation?.coordinate(it) ?: coordinates.getValue(it)
    })
}

internal fun checkLineSample(
    formula: PolynomialFormula,
    point: AffineLineEvaluation.Point,
    boolCount: Int,
    arithmetic: PolynomialArithmetic,
    algebraic: AlgebraicArithmetic,
    rejected: ((AlgebraicArithmetic.PointEvaluation, IntArray) -> Unit)? = null,
): QfNraAssignment? {
    val signs = if (rejected == null) null else {
        repeat(formula.atoms.size + 1) { arithmetic.step() }
        IntArray(formula.atoms.size)
    }
    val atomValues = IntArray(formula.atoms.size) { index ->
        arithmetic.step()
        val atom = formula.atoms[index]
        val sign = point.sign(atom.polynomial)
        if (signs != null) {
            arithmetic.step()
            signs[index] = sign
        }
        if (atom.holds(sign)) PolynomialFormula.TRUE else PolynomialFormula.FALSE
    }
    val bools = checkedBooleans(formula, boolCount, atomValues, arithmetic) ?: run {
        if (signs != null) {
            val coordinates = (0 until point.coordinateCount).associateWith { id ->
                arithmetic.step()
                point.coordinate(id)
            }
            val captured = algebraic.pointEvaluation(coordinates)
            arithmetic.step()
            rejected?.invoke(captured, signs)
        }
        return null
    }
    arithmetic.step()
    return QfNraAssignment(bools, List(point.coordinateCount) { id ->
        arithmetic.step()
        point.coordinate(id)
    })
}

private fun checkedBooleans(
    formula: PolynomialFormula,
    boolCount: Int,
    atomValues: IntArray,
    arithmetic: PolynomialArithmetic,
): BooleanArray? {
    val bools = solveBooleans(formula, boolCount, atomValues, arithmetic) ?: return null
    val complete = IntArray(bools.size) {
        arithmetic.step()
        if (bools[it]) PolynomialFormula.TRUE else PolynomialFormula.FALSE
    }
    check(formula.truth(complete, atomValues, arithmetic) == PolynomialFormula.TRUE)
    return bools
}

private fun solveBooleans(
    formula: PolynomialFormula,
    boolCount: Int,
    atomValues: IntArray,
    arithmetic: PolynomialArithmetic,
): BooleanArray? {
    val values = IntArray(boolCount) {
        arithmetic.step()
        PolynomialFormula.UNASSIGNED
    }
    val decisions = IntArrayDeque()
    while (true) {
        arithmetic.step()
        when (formula.truth(values, atomValues, arithmetic)) {
            PolynomialFormula.TRUE -> return BooleanArray(boolCount) {
                arithmetic.step()
                values[it] == PolynomialFormula.TRUE
            }
            PolynomialFormula.UNASSIGNED -> {
                val variable = formula.booleanVariables.first {
                    arithmetic.step()
                    values[it] == PolynomialFormula.UNASSIGNED
                }
                values[variable] = PolynomialFormula.FALSE
                decisions.addLast(variable)
            }
            else -> {
                var changed = false
                while (decisions.isNotEmpty()) {
                    arithmetic.step()
                    val variable = decisions[decisions.size - 1]
                    if (values[variable] == PolynomialFormula.FALSE) {
                        values[variable] = PolynomialFormula.TRUE
                        changed = true
                        break
                    }
                    values[variable] = PolynomialFormula.UNASSIGNED
                    decisions.removeLast()
                }
                if (!changed) return null
            }
        }
    }
}
