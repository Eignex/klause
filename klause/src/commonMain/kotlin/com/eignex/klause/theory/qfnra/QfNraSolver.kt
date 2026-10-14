package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.AlgebraicReal
import com.eignex.klause.arithmetic.polynomial.Polynomial
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
    val query = QfNraQuery(formula, boolCount, realCount, params, condition)
    while (true) query.next()?.let { return it }
}

internal class QfNraQuery(
    formula: PolynomialRealFormula,
    boolCount: Int,
    realCount: Int,
    params: QfNraParams,
    condition: PolynomialCondition,
) {
    private val work = QfNraWork(params)
    val stats: QfNraStats get() = work.snapshot()
    private var result: QfNraResult? = null
    private val preparation = sequence<QfNraResult?> {
        val arithmetic = PolynomialArithmetic(work, params.polynomialLimits)
        val algebraic = AlgebraicArithmetic(arithmetic)
        try {
            arithmetic.step()
            val conditioned = condition.apply(formula, boolCount, realCount, arithmetic::step)
            conditioned.requireVariableCounts(boolCount, realCount, arithmetic::step)
            val fixed = condition.lower.filter { (id, value) ->
                arithmetic.step()
                condition.upper[id] == value
            }
            val normalized = PolynomialFormula.normalize(conditioned, arithmetic, fixed)
            yield(null)
            val witness = work.probe {
                probeWitness(normalized, boolCount, realCount, arithmetic, algebraic, fixed)
            }
            if (witness != null) {
                arithmetic.step()
                yield(QfNraResult.Satisfiable(witness, work.snapshot()))
                return@sequence
            }
            yield(null)
            val polynomials = normalized.atoms.map {
                arithmetic.step()
                it.polynomial
            }
            val pending = PolynomialProjection.prepare(polynomials, realCount, arithmetic)
            var projection: PolynomialProjection? = null
            while (projection == null) {
                projection = pending.next()
                if (projection == null) yield(null)
            }
            work.projectedPolynomials = projection.polynomialCount
            yield(null)
            val assignment = searchCells(normalized, projection, boolCount, arithmetic, algebraic, work, fixed)
            arithmetic.step()
            yield(if (assignment == null) QfNraResult.Unsatisfiable(work.snapshot())
                else QfNraResult.Satisfiable(assignment, work.snapshot()))
        } catch (_: PolynomialInterrupted) {
            val reason = if (work.limitReached) QfNraReason.WorkLimit else QfNraReason.Interrupted
            yield(QfNraResult.Unknown(reason, work.snapshot()))
        } catch (stop: PolynomialResourceLimit) {
            yield(QfNraResult.Unknown(QfNraReason.ResourceLimit, work.snapshot(), stop.resource))
        }
    }.iterator()

    fun next(): QfNraResult? {
        result?.let { return it }
        return preparation.next().also { result = it }
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
    var nonlinear = false
    val termCounts = HashMap<Polynomial, Int>()
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
            if (!nonlinear) {
                repeat(realCount + 1) { arithmetic.step() }
                val variables = term.monomial.variables
                nonlinear = variables.size > 1 || variables.any {
                    arithmetic.step()
                    term.monomial.exponent(it) > 1
                }
            }
        }
        terms += count
        termCounts[atom.polynomial] = count
        repeat(count * (realCount + 1) + realCount + 1) { arithmetic.step() }
        if (atom.relation == PolynomialRelation.EQ) {
            val variables = atom.polynomial.variables.filter { it !in fixed }
            axes.add(Triple(atom, variables, count))
        }
    }
    val conjunctive = formula.conjunctiveAtoms(arithmetic, MAX_WITNESS_ATOMS)
    if (nonlinear && conjunctive != null && conjunctive.none {
            arithmetic.step()
            it.relation == PolynomialRelation.EQ && !it.polynomial.isZero
        }
    ) {
        return probeInequalityWitness(
            formula, conjunctive, boolCount, realCount, arithmetic, algebraic, fixed, terms, termCounts,
        )
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

private fun probeInequalityWitness(
    formula: PolynomialFormula,
    guides: List<PolynomialAtom>,
    boolCount: Int,
    realCount: Int,
    arithmetic: PolynomialArithmetic,
    algebraic: AlgebraicArithmetic,
    fixed: Map<Int, BigFraction>,
    terms: Int,
    termCounts: Map<Polynomial, Int>,
): QfNraAssignment? {
    repeat(terms * (realCount + 1) + realCount + 1) { arithmetic.step() }
    val byCoordinate = Array(realCount) { ArrayList<Pair<PolynomialAtom, Int>>() }
    for (atom in guides) {
        arithmetic.step()
        for (variable in atom.polynomial.variables) {
            arithmetic.step()
            byCoordinate[variable].add(atom to termCounts.getValue(atom.polynomial))
        }
    }
    val probe = InequalityWitnessProbe(formula, boolCount, arithmetic, algebraic)
    for (center in listOf(BigFraction.ONE, BigFraction.ZERO, BigFraction.MINUS_ONE)) {
        val point = LinkedHashMap<Int, AlgebraicReal>()
        for (id in 0 until realCount) {
            arithmetic.step()
            point[id] = algebraic.rational(fixed[id] ?: if (byCoordinate[id].isEmpty()) BigFraction.ZERO else center)
        }
        if (probe.exhausted) return null
        probe.candidate(point)?.let { return it }
        for (pass in 0 until MAX_WITNESS_PASSES) {
            var repaired = false
            for (variable in 0 until realCount) {
                arithmetic.step()
                if (probe.exhausted) return null
                if (variable in fixed || byCoordinate[variable].isEmpty()) continue
                val roots = probe.boundaries(byCoordinate[variable], variable, point) ?: return null
                var best: Map<Int, AlgebraicReal>? = null
                var bestScore = -1
                for (cell in 0..2 * roots.size) {
                    if (probe.exhausted) return null
                    val root = cell / 2
                    val coordinate = if (cell and 1 != 0) roots[root] else algebraic.rational(
                        algebraic.sampleBetween(roots.getOrNull(root - 1), roots.getOrNull(root)),
                    )
                    repeat(realCount + 1) { arithmetic.step() }
                    val proposed = LinkedHashMap(point)
                    proposed[variable] = coordinate
                    probe.candidate(proposed)?.let { return it }
                    var score = 0
                    for (atom in guides) {
                        arithmetic.step()
                        if (atom.holds(algebraic.sign(algebraic.evaluate(atom.polynomial, proposed)))) score++
                    }
                    if (score > bestScore) {
                        best = proposed
                        bestScore = score
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

private class InequalityWitnessProbe(
    private val formula: PolynomialFormula,
    private val boolCount: Int,
    private val arithmetic: PolynomialArithmetic,
    private val algebraic: AlgebraicArithmetic,
) {
    private var proposals = 0
    private var queries = 0
    val exhausted: Boolean get() = proposals == MAX_WITNESS_PROPOSALS

    fun candidate(point: Map<Int, AlgebraicReal>): QfNraAssignment? {
        arithmetic.step()
        proposals++
        return checkSample(formula, point, boolCount, arithmetic, algebraic)
    }

    fun boundaries(
        atoms: List<Pair<PolynomialAtom, Int>>,
        variable: Int,
        point: Map<Int, AlgebraicReal>,
    ): List<AlgebraicReal>? {
        val roots = ArrayList<AlgebraicReal>()
        for ((atom, termCount) in atoms) {
            arithmetic.step()
            repeat(termCount + 1) { arithmetic.step() }
            if (atom.polynomial.degree(variable) > MAX_WITNESS_DEGREE) continue
            if (queries == MAX_WITNESS_AXES) return null
            queries++
            val residual = algebraic.specialize(atom.polynomial, variable, point)
            if (residual.isZero || residual.degree <= 0) continue
            for (root in algebraic.roots(residual)) {
                arithmetic.step()
                roots.add(root)
            }
        }
        roots.sortWith(Comparator { left, right -> algebraic.compare(left, right) })
        val distinct = ArrayList<AlgebraicReal>()
        for (root in roots) {
            arithmetic.step()
            if (distinct.isEmpty() || algebraic.compare(distinct.last(), root) != 0) distinct.add(root)
        }
        return distinct
    }
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
        if (frame.variable + 1 < projection.variableCount) {
            if (prefixFeasible(formula, coordinates, boolCount, arithmetic, algebraic)) {
                frames.addLast(liftFrame(projection, frame.variable + 1, coordinates, arithmetic, algebraic, fixed))
            }
        } else {
            val assignment = checkSample(formula, coordinates, boolCount, arithmetic, algebraic)
            if (assignment != null) return assignment
        }
    }
    return null
}

private class CadFrame(val variable: Int, val roots: List<AlgebraicReal>, val fixed: AlgebraicReal? = null) {
    val cellCount: Long = if (fixed == null) 2L * roots.size + 1 else 1
    var nextCell: Long = 0
}

private fun liftFrame(
    projection: PolynomialProjection,
    variable: Int,
    coordinates: Map<Int, AlgebraicReal>,
    arithmetic: PolynomialArithmetic,
    algebraic: AlgebraicArithmetic,
    fixed: Map<Int, BigFraction>,
): CadFrame {
    fixed[variable]?.let { return CadFrame(variable, emptyList(), algebraic.rational(it)) }
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
    return CadFrame(variable, distinct)
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
        val variable = atom.polynomial.mainVariable
        if (variable != null && variable >= coordinates.size) PolynomialFormula.UNASSIGNED
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
