package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.AlgebraicReal
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.arithmetic.polynomial.PolynomialLimits
import com.eignex.klause.arithmetic.polynomial.PolynomialResource
import com.eignex.klause.arithmetic.polynomial.PolynomialResourceLimit
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.ir.requireVariableCounts
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import com.eignex.klause.util.magnitudeBitLength

internal data class QfNraParams(
    val cancellation: Cancellation = Cancellation.Never,
    val maximumWork: Long? = null,
    val polynomialLimits: PolynomialLimits = PolynomialLimits(),
) {
    init { require(maximumWork == null || maximumWork >= 0) }
}

internal data class QfNraStats(val projectedPolynomials: Long, val liftedCells: Long, val workSpent: Long)

internal enum class QfNraReason { Interrupted, WorkLimit, ResourceLimit }

internal class QfNraAssignment(bools: BooleanArray, reals: List<AlgebraicReal>) {
    private val booleanValues = bools.copyOf()
    private val realValues = reals.toTypedArray()
    val boolCount: Int get() = booleanValues.size
    val realCount: Int get() = realValues.size
    val bools: BooleanArray get() = booleanValues.copyOf()
    val reals: List<AlgebraicReal> get() = realValues.toList()
    fun boolValue(variable: Int): Boolean = booleanValues[variable]
    fun realValue(variable: Int): AlgebraicReal = realValues[variable]
}

internal sealed interface QfNraResult {
    val stats: QfNraStats
    data class Satisfiable(val assignment: QfNraAssignment, override val stats: QfNraStats) : QfNraResult
    data class Unsatisfiable(override val stats: QfNraStats) : QfNraResult
    data class Unknown(
        val reason: QfNraReason,
        override val stats: QfNraStats,
        val resource: PolynomialResource? = null,
    ) : QfNraResult
}

internal fun solveQfNra(
    formula: PolynomialRealFormula,
    boolCount: Int,
    realCount: Int,
    params: QfNraParams = QfNraParams(),
    condition: PolynomialCondition = PolynomialCondition(),
): QfNraResult {
    val work = QfNraWork(params)
    val arithmetic = PolynomialArithmetic(work, params.polynomialLimits)
    val algebraic = AlgebraicArithmetic(arithmetic)
    return try {
        arithmetic.step()
        val conditioned = condition.apply(formula, boolCount, realCount, arithmetic::step)
        conditioned.requireVariableCounts(boolCount, realCount, arithmetic::step)
        val fixed = condition.lower.filter { (id, value) ->
            arithmetic.step()
            condition.upper[id] == value
        }
        val normalized = PolynomialFormula.normalize(conditioned, arithmetic, fixed)
        val witness = work.probe {
            probeWitness(normalized, boolCount, realCount, arithmetic, algebraic, fixed)
        }
        if (witness != null) {
            arithmetic.step()
            return QfNraResult.Satisfiable(witness, work.snapshot())
        }
        val polynomials = normalized.atoms.map {
            arithmetic.step()
            it.polynomial
        }
        val order = satisfactionLiftingOrder(polynomials, realCount, arithmetic)
        val projection = PolynomialProjection.compute(polynomials, realCount, arithmetic, order)
        work.projectedPolynomials = projection.polynomialCount
        val assignment = searchCells(normalized, projection, boolCount, arithmetic, algebraic, work, fixed)
        arithmetic.step()
        if (assignment == null) QfNraResult.Unsatisfiable(work.snapshot())
        else QfNraResult.Satisfiable(assignment, work.snapshot())
    } catch (_: PolynomialInterrupted) {
        val reason = if (work.limitReached) QfNraReason.WorkLimit else QfNraReason.Interrupted
        QfNraResult.Unknown(reason, work.snapshot())
    } catch (stop: PolynomialResourceLimit) {
        QfNraResult.Unknown(QfNraReason.ResourceLimit, work.snapshot(), stop.resource)
    }
}

private class QfNraWork(private val params: QfNraParams) : Cancellation, WorkMeter {
    var projectedPolynomials: Long = 0
    var liftedCells: Long = 0
    private var spent: Long = 0
    private var probeEnd: Long? = null
    private var probeParentStopped = false
    val limitReached: Boolean get() = params.maximumWork?.let { spent >= it } == true
    override fun isCancelled(): Boolean {
        val stopped = limitReached || params.cancellation()
        if (probeEnd != null && stopped) probeParentStopped = true
        return stopped || probeEnd?.let { spent >= it } == true
    }
    override fun deadline() = params.cancellation.deadline()
    override fun workMeter(): WorkMeter = this
    override fun charge(units: Long) {
        require(units >= 0)
        spent = if (units > Long.MAX_VALUE - spent) Long.MAX_VALUE else spent + units
        params.cancellation.charge(units)
    }
    fun probe(block: () -> QfNraAssignment?): QfNraAssignment? {
        check(probeEnd == null)
        probeEnd = spent + minOf(MAX_WITNESS_WORK, Long.MAX_VALUE - spent)
        probeParentStopped = false
        return try {
            block()
        } catch (stop: PolynomialInterrupted) {
            // An enclosing stop remains terminal even if its predicate later clears.
            if (probeParentStopped) throw stop
            null
        } catch (_: PolynomialResourceLimit) {
            null
        } finally {
            probeEnd = null
        }
    }
    fun snapshot(): QfNraStats = QfNraStats(projectedPolynomials, liftedCells, spent)
}

private const val MAX_WITNESS_WORK = 65_536L
private const val MAX_WITNESS_REALS = 32
private const val MAX_WITNESS_BOOLS = 64
private const val MAX_WITNESS_ATOMS = 128
private const val MAX_WITNESS_TERMS = 2048
private const val MAX_WITNESS_DEGREE = 8
private const val MAX_WITNESS_BITS = 4096
private const val MAX_WITNESS_AXES = 32
private const val MAX_WITNESS_PROPOSALS = 64
private const val MAX_WITNESS_PASSES = 4

private fun probeWitness(
    formula: PolynomialFormula,
    boolCount: Int,
    realCount: Int,
    arithmetic: PolynomialArithmetic,
    algebraic: AlgebraicArithmetic,
    fixed: Map<Int, BigFraction>,
): QfNraAssignment? {
    arithmetic.step()
    if (realCount > MAX_WITNESS_REALS || boolCount > MAX_WITNESS_BOOLS ||
        formula.atoms.size > MAX_WITNESS_ATOMS
    ) return null
    var terms = 0
    val axes = ArrayList<Triple<PolynomialAtom, List<Int>, Int>>()
    for (atom in formula.atoms) {
        arithmetic.step()
        val payload = atom.polynomial.terms
        val count = payload.size
        if (count > MAX_WITNESS_TERMS - terms) return null
        for (term in payload) {
            arithmetic.step()
            if (term.coefficient.num.magnitudeBitLength() > MAX_WITNESS_BITS ||
                term.coefficient.den.magnitudeBitLength() > MAX_WITNESS_BITS
            ) return null
        }
        terms += count
        repeat(count * (realCount + 1) + realCount + 1) { arithmetic.step() }
        if (atom.relation == PolynomialRelation.EQ) {
            val variables = atom.polynomial.variables.filter { it !in fixed }
            axes.add(Triple(atom, variables, count))
        }
    }
    var proposals = 0
    var attemptedAxes = 0
    fun candidate(point: Map<Int, AlgebraicReal>): QfNraAssignment? {
        arithmetic.step()
        proposals++
        return checkSample(formula, point, boolCount, arithmetic, algebraic)
    }
    for (center in listOf(BigFraction.ZERO, BigFraction.ONE, BigFraction.MINUS_ONE)) {
        val point = LinkedHashMap<Int, AlgebraicReal>()
        for (id in 0 until realCount) {
            arithmetic.step()
            point[id] = algebraic.rational(fixed[id] ?: center)
        }
        if (proposals == MAX_WITNESS_PROPOSALS) return null
        candidate(point)?.let { return it }
        for (pass in 0 until MAX_WITNESS_PASSES) {
            var repaired = false
            for ((atom, variables, termCount) in axes) {
                arithmetic.step()
                if (algebraic.sign(algebraic.evaluate(atom.polynomial, point)) == 0) continue
                var best: Map<Int, AlgebraicReal>? = null
                var bestScore = -1
                for (variable in variables) {
                    repeat(termCount + 1) { arithmetic.step() }
                    if (atom.polynomial.degree(variable) > MAX_WITNESS_DEGREE) continue
                    if (attemptedAxes == MAX_WITNESS_AXES) return null
                    attemptedAxes++
                    val residual = algebraic.specialize(atom.polynomial, variable, point)
                    if (residual.isZero || residual.degree <= 0) continue
                    // An original coordinate-degree bound limits optional proposals, not kernel representation limits.
                    val roots = algebraic.roots(residual)
                    for (root in roots) {
                        if (proposals == MAX_WITNESS_PROPOSALS) return null
                        repeat(realCount + 1) { arithmetic.step() }
                        val proposed = LinkedHashMap(point)
                        proposed[variable] = root
                        candidate(proposed)?.let { return it }
                        var score = 0
                        for (comparison in formula.atoms) {
                            arithmetic.step()
                            if (comparison.holds(algebraic.sign(algebraic.evaluate(comparison.polynomial, proposed)))) {
                                score++
                            }
                        }
                        if (score > bestScore) {
                            best = proposed
                            bestScore = score
                        }
                    }
                }
                best?.let {
                    repeat(realCount + 1) { arithmetic.step() }
                    point.putAll(it)
                    repaired = true
                }
            }
            if (!repaired) break
        }
    }
    return null
}

private fun searchCells(
    formula: PolynomialFormula,
    projection: PolynomialProjection,
    boolCount: Int,
    arithmetic: PolynomialArithmetic,
    algebraic: AlgebraicArithmetic,
    work: QfNraWork,
    fixed: Map<Int, BigFraction>,
): QfNraAssignment? {
    val coordinates = HashMap<Int, AlgebraicReal>()
    if (projection.variableCount == 0) {
        return checkSample(formula, coordinates, boolCount, arithmetic, algebraic)
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
        val coordinate = frame.fixed ?: if (cell and 1L != 0L) frame.roots[root] else {
            algebraic.rational(algebraic.sampleBetween(frame.roots.getOrNull(root - 1), frame.roots.getOrNull(root)))
        }
        coordinates[frame.variable] = coordinate
        if (work.liftedCells < Long.MAX_VALUE) work.liftedCells++
        if (frame.level + 1 < projection.variableCount) {
            if (prefixFeasible(formula, coordinates, boolCount, arithmetic, algebraic)) {
                frames.addLast(liftFrame(projection, frame.level + 1, coordinates, arithmetic, algebraic, fixed))
            }
        } else {
            val assignment = checkSample(formula, coordinates, boolCount, arithmetic, algebraic)
            if (assignment != null) return assignment
        }
    }
    return null
}

private class CadFrame(
    val level: Int,
    val variable: Int,
    val roots: List<AlgebraicReal>,
    val fixed: AlgebraicReal? = null,
) {
    val cellCount: Long = if (fixed == null) 2L * roots.size + 1 else 1
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
    val variable = projection.liftingVariable(level)
    fixed[variable]?.let { return CadFrame(level, variable, emptyList(), algebraic.rational(it)) }
    val roots = mutableListOf<AlgebraicReal>()
    for (polynomial in projection.polynomials(variable)) {
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

private fun prefixFeasible(
    formula: PolynomialFormula,
    coordinates: Map<Int, AlgebraicReal>,
    boolCount: Int,
    arithmetic: PolynomialArithmetic,
    algebraic: AlgebraicArithmetic,
): Boolean {
    val atoms = IntArray(formula.atoms.size) { index ->
        arithmetic.step()
        val atom = formula.atoms[index]
        val assigned = atom.polynomial.variables.all { variable ->
            arithmetic.step()
            variable in coordinates
        }
        if (!assigned) PolynomialFormula.UNASSIGNED
        else if (atom.holds(algebraic.sign(algebraic.evaluate(atom.polynomial, coordinates)))) PolynomialFormula.TRUE
        else PolynomialFormula.FALSE
    }
    val bools = IntArray(boolCount) {
        arithmetic.step()
        PolynomialFormula.UNASSIGNED
    }
    return formula.truth(bools, atoms, arithmetic) != PolynomialFormula.FALSE
}

private fun checkSample(
    formula: PolynomialFormula,
    coordinates: Map<Int, AlgebraicReal>,
    boolCount: Int,
    arithmetic: PolynomialArithmetic,
    algebraic: AlgebraicArithmetic,
): QfNraAssignment? {
    val atomValues = IntArray(formula.atoms.size) { index ->
        arithmetic.step()
        val atom = formula.atoms[index]
        if (atom.holds(algebraic.sign(algebraic.evaluate(atom.polynomial, coordinates)))) {
            PolynomialFormula.TRUE
        } else PolynomialFormula.FALSE
    }
    val bools = solveBooleans(formula, boolCount, atomValues, arithmetic) ?: return null
    val complete = IntArray(bools.size) {
        arithmetic.step()
        if (bools[it]) PolynomialFormula.TRUE else PolynomialFormula.FALSE
    }
    check(formula.truth(complete, atomValues, arithmetic) == PolynomialFormula.TRUE)
    arithmetic.step()
    return QfNraAssignment(bools, List(coordinates.size) {
        arithmetic.step()
        coordinates.getValue(it)
    })
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
    val decisions = ArrayDeque<Int>()
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
                    val variable = decisions.last()
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
