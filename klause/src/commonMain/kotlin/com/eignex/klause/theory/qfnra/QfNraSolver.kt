package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.AlgebraicReal
import com.eignex.klause.arithmetic.polynomial.Monomial
import com.eignex.klause.arithmetic.polynomial.Polynomial
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.arithmetic.polynomial.PolynomialLimits
import com.eignex.klause.arithmetic.polynomial.PolynomialResource
import com.eignex.klause.arithmetic.polynomial.PolynomialResourceLimit
import com.eignex.klause.arithmetic.polynomial.PolynomialTerm
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.ir.requireVariableCounts
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.magnitudeBitLength
import com.eignex.klause.util.shl

internal data class QfNraParams(
    val cancellation: Cancellation = Cancellation.Never,
    val maximumWork: Long? = null,
    val polynomialLimits: PolynomialLimits = PolynomialLimits(),
) {
    init { require(maximumWork == null || maximumWork >= 0) }
}

internal data class QfNraStats(
    val projectedPolynomials: Long,
    val liftedCells: Long,
    val workSpent: Long,
    val dyadicAttempts: Long? = null,
    val dyadicBases: Long? = null,
    val coupledWorkDeclines: Long? = null,
    val dyadicPreExtractionDeclines: Long? = null,
)

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
                WitnessProbe(normalized, boolCount, arithmetic, algebraic, work)
                    .probeWitness(realCount, fixed, work::coupled)
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

internal class QfNraWork(private val params: QfNraParams) : Cancellation, WorkMeter {
    var projectedPolynomials: Long = 0
    var liftedCells: Long = 0
    var dyadicAttempts: Long = 0
    var dyadicBases: Long = 0
    var dyadicPreExtraction = false
    private var coupledWorkDeclines: Long = 0
    private var dyadicPreExtractionDeclines: Long = 0
    private var spent: Long = 0
    private var probeEnd: Long? = null
    private var coupledEnd: Long? = null
    private var probeStart: Long = 0
    private var probeParentStopped = false
    val limitReached: Boolean get() = params.maximumWork?.let { spent >= it } == true
    override fun isCancelled(): Boolean {
        val stopped = limitReached || params.cancellation()
        if (probeEnd != null && stopped) probeParentStopped = true
        return stopped || probeEnd?.let { spent >= it } == true || coupledEnd?.let { spent >= it } == true
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
        probeStart = spent
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
    fun coupled(block: () -> QfNraAssignment?): QfNraAssignment? {
        check(probeEnd != null && coupledEnd == null)
        coupledEnd = probeStart + minOf(MAX_WITNESS_WORK / 2, Long.MAX_VALUE - probeStart)
        return try {
            block()
        } catch (stop: PolynomialInterrupted) {
            if (probeParentStopped) throw stop
            if (coupledEnd?.let { spent >= it } == true) {
                coupledWorkDeclines += minOf(1L, Long.MAX_VALUE - coupledWorkDeclines)
                if (dyadicPreExtraction) {
                    dyadicPreExtractionDeclines += minOf(1L, Long.MAX_VALUE - dyadicPreExtractionDeclines)
                }
            }
            null
        } finally {
            coupledEnd = null
            dyadicPreExtraction = false
        }
    }
    fun snapshot(): QfNraStats = QfNraStats(projectedPolynomials, liftedCells, spent,
        dyadicAttempts, dyadicBases, coupledWorkDeclines, dyadicPreExtractionDeclines)
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

internal fun probeInequalityWitness(
    guides: List<PolynomialAtom>,
    realCount: Int,
    arithmetic: PolynomialArithmetic,
    algebraic: AlgebraicArithmetic,
    fixed: Map<Int, BigFraction>,
    terms: Int,
    termCounts: Map<Polynomial, Int>,
    probe: WitnessProbe,
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
    val retained = probe.takeRejectedInequalityPoint(realCount, fixed)
    for ((index, center) in listOf(BigFraction.ONE, BigFraction.ZERO, BigFraction.MINUS_ONE).withIndex()) {
        val point = LinkedHashMap<Int, AlgebraicReal>()
        for (id in 0 until realCount) {
            arithmetic.step()
            point[id] = if (index == 0 && retained != null) retained.getValue(id) else {
                algebraic.rational(fixed[id] ?: if (byCoordinate[id].isEmpty()) BigFraction.ZERO else center)
            }
        }
        if (probe.exhausted) return null
        if (index != 0 || retained == null) probe.candidate(point)?.let { return it }
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

internal class WitnessProbe(
    private val formula: PolynomialFormula,
    private val boolCount: Int,
    private val arithmetic: PolynomialArithmetic,
    private val algebraic: AlgebraicArithmetic,
    private val work: QfNraWork? = null,
) {
    private var proposals = 0
    private var queries = 0
    private var retentionGuides: Pair<List<PolynomialAtom>, List<Int>>? = null
    private var retentionVariables: Set<Int>? = null
    private var retentionCount = 0
    private var rejectedCoupled: Pair<Int, Map<Int, AlgebraicReal>>? = null
    private var admittedTerms: Triple<List<PolynomialAtom>, List<Int>, List<List<PolynomialTerm>>>? = null
    private var coupledTerms: List<List<PolynomialTerm>>? = null
    val exhausted: Boolean get() = proposals == MAX_WITNESS_PROPOSALS

    fun probeWitness(
        realCount: Int,
        fixed: Map<Int, BigFraction>,
        coupledPhase: (() -> QfNraAssignment?) -> QfNraAssignment?,
    ): QfNraAssignment? {
        check(admittedTerms == null && coupledTerms == null)
        try {
            arithmetic.step()
            if (realCount > MAX_WITNESS_REALS || boolCount > MAX_WITNESS_BOOLS ||
                formula.atoms.size > MAX_WITNESS_ATOMS
            ) return null
            var terms = 0
            var nonlinear = false
            val termCounts = HashMap<Polynomial, Int>()
            repeat(formula.atoms.size + 1) { arithmetic.step() }
            val sourceTermCounts = ArrayList<Int>()
            repeat(formula.atoms.size + 1) { arithmetic.step() }
            val sourcePayloads = ArrayList<List<PolynomialTerm>>()
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
                arithmetic.step()
                sourceTermCounts.add(count)
                arithmetic.step()
                sourcePayloads.add(payload)
                repeat(count * (realCount + 1) + realCount + 1) { arithmetic.step() }
                if (atom.relation == PolynomialRelation.EQ) {
                    val variables = atom.polynomial.variables.filter { it !in fixed }
                    axes.add(Triple(atom, variables, count))
                }
            }
            val guideIndices = ArrayList<Int>()
            val conjunctive = formula.conjunctiveAtoms(arithmetic, MAX_WITNESS_ATOMS, guideIndices)
            if (nonlinear && conjunctive != null) {
                val point = List(realCount) { id ->
                    arithmetic.step()
                    fixed[id] ?: BigFraction.ZERO
                }
                candidate(point.mapIndexed { id, value -> id to algebraic.rational(value) }.toMap())?.let { return it }
                repeat(2 * guideIndices.size + 1) { arithmetic.step() }
                val guideTermCounts = guideIndices.map { index ->
                    arithmetic.step()
                    sourceTermCounts[index]
                }
                if (guideIndices.indices.all { index ->
                        arithmetic.step()
                        formula.atoms[guideIndices[index]].polynomial === conjunctive[index].polynomial &&
                            sourcePayloads[guideIndices[index]].size == guideTermCounts[index]
                    }
                ) {
                    repeat(3 * guideIndices.size + 1) { arithmetic.step() }
                    admittedTerms = Triple(
                        conjunctive.toList(), guideIndices.toList(), guideIndices.map { index ->
                            arithmetic.step()
                            sourcePayloads[index]
                        },
                    )
                }
                try {
                    coupledPhase { coupled(conjunctive, point, fixed, guideIndices, guideTermCounts) }
                        ?.let { return it }
                } finally {
                    admittedTerms = null
                }
            }
            if (nonlinear && conjunctive != null && conjunctive.none {
                    arithmetic.step()
                    it.relation == PolynomialRelation.EQ && !it.polynomial.isZero
                }
            ) {
                return probeInequalityWitness(
                    conjunctive, realCount, arithmetic, algebraic, fixed, terms, termCounts, this,
                )
            }
            for (center in listOf(BigFraction.ZERO, BigFraction.ONE, BigFraction.MINUS_ONE)) {
                val point = LinkedHashMap<Int, AlgebraicReal>()
                for (id in 0 until realCount) {
                    arithmetic.step()
                    point[id] = algebraic.rational(fixed[id] ?: center)
                }
                if (exhausted) return null
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
                            // An original coordinate-degree bound limits optional proposals,
                            // not kernel representation limits.
                            val roots = roots(atom.polynomial, variable, point) ?: return null
                            for (root in roots) {
                                if (exhausted) return null
                                repeat(realCount + 1) { arithmetic.step() }
                                val proposed = LinkedHashMap(point)
                                proposed[variable] = root
                                candidate(proposed)?.let { return it }
                                var score = 0
                                for (comparison in formula.atoms) {
                                    arithmetic.step()
                                    if (comparison.holds(
                                            algebraic.sign(algebraic.evaluate(comparison.polynomial, proposed)),
                                        )
                                    ) {
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
        } finally {
            admittedTerms = null
            coupledTerms = null
        }
    }

    fun candidate(point: Map<Int, AlgebraicReal>): QfNraAssignment? {
        if (exhausted) return null
        arithmetic.step()
        proposals++
        val context = retentionGuides
        return checkSample(formula, point, boolCount, arithmetic, algebraic,
            if (context == null) null else { signs -> retainRejectedPoint(point, signs, context) })
    }

    private fun retainRejectedPoint(
        point: Map<Int, AlgebraicReal>,
        signs: IntArray,
        context: Pair<List<PolynomialAtom>, List<Int>>,
    ) {
        try {
            val variables = retentionVariables ?: return
            if (point.size != retentionCount) return
            var nonzero = 0
            for (variable in variables) {
                arithmetic.step()
                if (algebraic.sign(point.getValue(variable)) != 0) nonzero++
                if (nonzero == 2) break
            }
            if (nonzero < 2) return
            var score = 0
            for (index in context.first.indices) {
                arithmetic.step()
                if (context.first[index].holds(signs[context.second[index]])) score++
            }
            arithmetic.step()
            if (score <= (rejectedCoupled?.first ?: -1)) return
            val snapshot = LinkedHashMap<Int, AlgebraicReal>()
            for (id in 0 until retentionCount) {
                arithmetic.step()
                snapshot[id] = point.getValue(id)
            }
            arithmetic.step()
            rejectedCoupled = score to snapshot
        } catch (_: PolynomialResourceLimit) {
            // An incomplete optional rank cannot replace a previously completed point.
        }
    }

    fun takeRejectedInequalityPoint(realCount: Int, fixed: Map<Int, BigFraction>): Map<Int, AlgebraicReal>? {
        arithmetic.step()
        if (exhausted || queries == MAX_WITNESS_AXES) {
            rejectedCoupled = null
            return null
        }
        val snapshot = rejectedCoupled?.second
        rejectedCoupled = null
        if (snapshot == null || snapshot.size != realCount) return null
        for ((id, value) in fixed) {
            arithmetic.step()
            if (algebraic.compare(snapshot.getValue(id), algebraic.rational(value)) != 0) return null
        }
        arithmetic.step()
        return snapshot
    }

    fun roots(
        polynomial: Polynomial,
        variable: Int,
        point: Map<Int, AlgebraicReal>,
    ): List<AlgebraicReal>? {
        if (queries == MAX_WITNESS_AXES) return null
        queries++
        val residual = algebraic.specialize(polynomial, variable, point)
        return if (residual.isZero || residual.degree <= 0) emptyList() else algebraic.roots(residual)
    }

    fun coupled(
        atoms: List<PolynomialAtom>,
        seed: List<BigFraction>,
        fixed: Map<Int, BigFraction>,
        atomIndices: List<Int>? = null,
        atomTermCounts: List<Int>? = null,
    ): QfNraAssignment? {
        check(retentionGuides == null && coupledTerms == null)
        val captured = admittedTerms
        admittedTerms = null
        try {
            if (captured != null && atomIndices != null && atomTermCounts != null) {
                arithmetic.step()
                val complete = captured.first.size == atoms.size && captured.second.size == atoms.size &&
                    captured.third.size == atoms.size && atomIndices.size == atoms.size &&
                    atomTermCounts.size == atoms.size && atoms.indices.all { index ->
                        arithmetic.step()
                        captured.first[index] === atoms[index] && captured.second[index] == atomIndices[index] &&
                            captured.third[index].size == atomTermCounts[index] &&
                            formula.atoms.getOrNull(atomIndices[index])?.polynomial === atoms[index].polynomial
                    }
                if (complete) coupledTerms = captured.third
            }
            if (atomIndices != null && atoms.none {
                    arithmetic.step()
                    it.relation == PolynomialRelation.EQ && !it.polynomial.isZero
                }
            ) {
                require(atomIndices.size == atoms.size)
                for (index in atomIndices) {
                    arithmetic.step()
                    require(index in formula.atoms.indices)
                }
                repeat(atoms.size + atomIndices.size) { arithmetic.step() }
                retentionGuides = atoms.toList() to atomIndices.toList()
                retentionCount = seed.size
            }
            return try {
                coupledDirections(atoms, seed, fixed, atomTermCounts)
            } catch (_: PolynomialResourceLimit) {
                null
            } finally {
                retentionGuides = null
                retentionVariables = null
            }
        } finally {
            admittedTerms = null
            coupledTerms = null
        }
    }

    private fun coupledDirections(
        atoms: List<PolynomialAtom>,
        seed: List<BigFraction>,
        fixed: Map<Int, BigFraction>,
        atomTermCounts: List<Int>?,
    ): QfNraAssignment? {
        if (queries >= MAX_WITNESS_AXES / 2 || proposals >= MAX_WITNESS_PROPOSALS / 2) return null
        val active = LinkedHashSet<Int>()
        val groups = LinkedHashSet<List<Int>>()
        val affine = ArrayList<Polynomial>()
        for ((index, atom) in atoms.withIndex()) {
            arithmetic.step()
            val payload = coupledTerms?.let { arithmetic.step(); it[index] } ?: atom.polynomial.terms
            var linear = true
            for (term in payload) {
                arithmetic.step()
                val variables = term.monomial.variables.filter { arithmetic.step(); it !in fixed }
                active.addAll(variables)
                if (variables.size > 1) groups.add(variables)
                if (variables.size > 1 || variables.any { term.monomial.exponent(it) != 1 }) linear = false
            }
            if (linear && atom.relation == PolynomialRelation.EQ && !atom.polynomial.isZero) {
                affine.add(atom.polynomial)
            }
        }
        if (active.size < 2) return null
        if (retentionGuides != null) {
            repeat(active.size) { arithmetic.step() }
            retentionVariables = active.toSet()
        }
        val variables = active.sorted()
        val rows = affineRows(affine, variables) ?: return null
        val pivots = rows.map { row ->
            variables.indices.first { column -> arithmetic.step(); !row[column].isZero }
        }
        val free = variables.indices.filter { arithmetic.step(); it !in pivots }
        val scaled = if (queries == 0 && atomTermCounts != null && affine.isEmpty() && atoms.none {
                arithmetic.step()
                it.relation == PolynomialRelation.EQ && !it.polynomial.isZero
            }
        ) try {
            work?.let {
                it.dyadicAttempts += minOf(1L, Long.MAX_VALUE - it.dyadicAttempts)
                it.dyadicPreExtraction = true
            }
            dyadicBase(atoms, atomTermCounts, seed, active, fixed).also { base ->
                work?.let {
                    it.dyadicPreExtraction = false
                    if (base != null) it.dyadicBases += minOf(1L, Long.MAX_VALUE - it.dyadicBases)
                }
            }
        } catch (_: PolynomialResourceLimit) {
            work?.dyadicPreExtraction = false
            null
        } else null
        val one = scaled ?: seed.mapIndexed { id, value ->
            arithmetic.step()
            if (id in active && id !in fixed) BigFraction.ONE else value
        }
        val bases = listOf(one, seed).map { completeCoupledBase(it, variables, rows, pivots, free) }
        val guides = coupledGuides(atoms)
        val seen = ArrayList<Pair<List<BigFraction>, List<BigFraction>>>()
        fun offer(center: Int, group: List<Int>, alternating: Boolean): QfNraAssignment? {
            if (queries >= MAX_WITNESS_AXES / 2 || proposals >= MAX_WITNESS_PROPOSALS / 2) return null
            val base = bases[center] ?: return null
            val direction = coupledDirection(seed.size, group, alternating, variables, rows, pivots, free)
                ?: return null
            return offerCoupledLine(guides, base, direction, seen)
        }
        bases[0]?.let { base ->
            if (proposals >= MAX_WITNESS_PROPOSALS / 2) return null
            candidate(base.mapIndexed { id, value -> id to algebraic.rational(value) }.toMap())?.let { return it }
        }
        offer(0, variables, true)?.let { return it }
        for (group in groups) {
            if (queries >= MAX_WITNESS_AXES / 2 || proposals >= MAX_WITNESS_PROPOSALS / 2) return null
            val previous = seen.size
            offer(0, group, false)?.let { return it }
            if (seen.size > previous) break
        }
        if (rows.isNotEmpty()) bases[1]?.let { base ->
            if (proposals >= MAX_WITNESS_PROPOSALS / 2) return null
            candidate(base.mapIndexed { id, value -> id to algebraic.rational(value) }.toMap())?.let { return it }
        }
        offer(1, variables, false)?.let { return it }
        offer(0, variables, false)?.let { return it }
        val orderedGroups = LinkedHashSet<List<Int>>()
        orderedGroups.add(variables)
        orderedGroups.addAll(groups)
        for (column in free) orderedGroups.add(listOf(variables[column]))
        for (group in orderedGroups) for (alternating in listOf(false, true)) for (center in bases.indices) {
            if (queries >= MAX_WITNESS_AXES / 2 || proposals >= MAX_WITNESS_PROPOSALS / 2) return null
            offer(center, group, alternating)?.let { return it }
        }
        return null
    }

    private fun dyadicBase(
        atoms: List<PolynomialAtom>,
        counts: List<Int>,
        seed: List<BigFraction>,
        active: Set<Int>,
        fixed: Map<Int, BigFraction>,
    ): List<BigFraction>? {
        arithmetic.step()
        if (counts.size != atoms.size || seed.size > MAX_WITNESS_REALS) return null
        repeat(2 * atoms.size + seed.size + 3) { arithmetic.step() }
        val payloads = ArrayList<List<PolynomialTerm>>()
        val selected = IntArray(atoms.size) { -1 }
        val exponents = IntArray(seed.size)
        var total = 0
        for ((index, atom) in atoms.withIndex()) {
            arithmetic.step()
            val count = counts[index]
            if (count < 0 || count > MAX_WITNESS_TERMS - total) return null
            total += count
            val terms = coupledTerms?.let { arithmetic.step(); it[index] } ?: run {
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
                val chosen = dyadicLeadingTerm(terms, orientation, exponents) ?: return null
                arithmetic.step()
                selected[index] = chosen
                changed = dyadicGuide(terms, chosen, orientation, exponents, active, fixed, true)
                    ?.let { changed || it } ?: return null
            }
            if (!changed) break
        }
        for (index in atoms.indices) {
            arithmetic.step()
            if (selected[index] < 0) continue
            dyadicGuide(payloads[index], selected[index],
                dyadicOrientation(atoms[index].relation, payloads[index]),
                exponents, active, fixed, false) ?: return null
        }
        repeat(seed.size + 1) { arithmetic.step() }
        val base = seed.mapIndexed { id, value ->
            arithmetic.step()
            if (id in active && id !in fixed) dyadicValue(exponents[id]) else value
        }
        arithmetic.step()
        return base.takeIf { it.all(::admittedFraction) }
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
        exponents: IntArray,
    ): Int? {
        var selected: Int? = null
        var greatest = Long.MIN_VALUE
        for ((index, term) in terms.withIndex()) {
            arithmetic.step()
            if (term.coefficient.signum() * orientation <= 0) continue
            val weight = dyadicWeight(term.monomial, exponents) ?: return null
            if (selected == null || weight > greatest) {
                arithmetic.step()
                selected = index
                greatest = weight
            }
        }
        return selected
    }

    private fun dyadicWeight(monomial: Monomial, exponents: IntArray): Long? {
        var weight = 0L
        for (id in exponents.indices) {
            arithmetic.step()
            val degree = monomial.exponent(id).toLong()
            val exponent = exponents[id].toLong()
            if (degree < 0 || exponent !in -(MAX_WITNESS_BITS - 1L)..(MAX_WITNESS_BITS - 1L)) return null
            arithmetic.step()
            // An Int degree times an admitted exponent fits Long before the checked sum.
            val product = degree * exponent
            weight = dyadicSum(weight, product) ?: return null
        }
        return weight
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
        exponents: IntArray,
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
            val left = dyadicWeight(positive.monomial, exponents) ?: return null
            val right = dyadicWeight(negative.monomial, exponents) ?: return null
            val gap = dyadicSum(left, -right) ?: return null
            if (gap >= margin) continue
            if (!repair) return null
            val deficit = dyadicSum(margin, -gap) ?: return null
            if (!repairDyadicRow(positive.monomial, negative.monomial, deficit,
                    exponents, active, fixed)) return null
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
        exponents: IntArray,
        active: Set<Int>,
        fixed: Map<Int, BigFraction>,
    ): Boolean {
        var selected = -1
        var greatest = 0L
        var replacement = 0
        for (id in exponents.indices) {
            arithmetic.step()
            if (id !in active || id in fixed) continue
            arithmetic.step()
            val coefficient = positive.exponent(id).toLong() - negative.exponent(id)
            val magnitude = if (coefficient < 0) -coefficient else coefficient
            if (magnitude == 0L || magnitude <= greatest) continue
            arithmetic.step()
            val movement = (deficit - 1) / magnitude + 1
            val value = dyadicSum(exponents[id].toLong(), if (coefficient > 0) movement else -movement)
                ?: continue
            if (value !in -(MAX_WITNESS_BITS - 1L)..(MAX_WITNESS_BITS - 1L)) continue
            arithmetic.step()
            selected = id
            greatest = magnitude
            replacement = value.toInt()
        }
        if (selected < 0) return false
        arithmetic.step()
        exponents[selected] = replacement
        arithmetic.step()
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

    private fun completeCoupledBase(
        seed: List<BigFraction>,
        variables: List<Int>,
        rows: List<Array<BigFraction>>,
        pivots: List<Int>,
        free: List<Int>,
    ): List<BigFraction>? {
        val base = seed.toMutableList()
        for ((index, row) in rows.withIndex()) {
            var value = row.last()
            for (column in free) {
                arithmetic.step()
                val product = row[column] * base[variables[column]]
                if (!admittedFraction(product)) return null
                arithmetic.step()
                value -= product
                if (!admittedFraction(value)) return null
            }
            base[variables[pivots[index]]] = value
        }
        return base.takeIf { it.all(::admittedFraction) }
    }

    private fun coupledDirection(
        size: Int,
        group: List<Int>,
        alternating: Boolean,
        variables: List<Int>,
        rows: List<Array<BigFraction>>,
        pivots: List<Int>,
        free: List<Int>,
    ): List<BigFraction>? {
        val direction = MutableList(size) { arithmetic.step(); BigFraction.ZERO }
        for ((index, variable) in group.withIndex()) {
            repeat(variables.size + free.size + 1) { arithmetic.step() }
            if (variables.indexOf(variable) in free) {
                direction[variable] = if (alternating && index and 1 != 0) {
                    BigFraction.MINUS_ONE
                } else BigFraction.ONE
            }
        }
        for ((index, row) in rows.withIndex()) {
            var value = BigFraction.ZERO
            for (column in free) {
                arithmetic.step()
                val product = row[column] * direction[variables[column]]
                if (!admittedFraction(product)) return null
                arithmetic.step()
                value -= product
                if (!admittedFraction(value)) return null
            }
            direction[variables[pivots[index]]] = value
        }
        return direction.takeIf { it.count { value -> arithmetic.step(); !value.isZero } >= 2 }
    }

    private fun offerCoupledLine(
        atoms: List<PolynomialAtom>,
        base: List<BigFraction>,
        direction: List<BigFraction>,
        seen: MutableList<Pair<List<BigFraction>, List<BigFraction>>>,
    ): QfNraAssignment? {
        for ((previousBase, previousDirection) in seen) {
            arithmetic.step()
            if (sameCoupledPoint(base, previousBase) && sameCoupledPoint(direction, previousDirection)) return null
        }
        seen.add(base to direction)
        return coupledLine(atoms, base, direction)
    }

    private fun sameCoupledPoint(left: List<BigFraction>, right: List<BigFraction>): Boolean {
        for (id in left.indices) {
            arithmetic.step()
            val words = (left[id].num.magnitudeBitLength() + left[id].den.magnitudeBitLength() +
                right[id].num.magnitudeBitLength() + right[id].den.magnitudeBitLength()) / 64
            repeat(words + 1) { arithmetic.step() }
            if (left[id] != right[id]) return false
        }
        return true
    }

    private fun affineRows(polynomials: List<Polynomial>, variables: List<Int>): List<Array<BigFraction>>? {
        if (polynomials.isEmpty()) return emptyList()
        arithmetic.matrixCells(polynomials.size.toLong(), variables.size.toLong() + 1)
        val rows = polynomials.map { polynomial ->
            Array(variables.size + 1) { column ->
                arithmetic.step()
                if (column == variables.size) polynomial.coefficient(Monomial.ONE).negated()
                else polynomial.coefficient(Monomial.of(mapOf(variables[column] to 1)))
            }
        }.toMutableList()
        var rank = 0
        for (column in variables.indices) {
            val pivot = (rank until rows.size).firstOrNull { arithmetic.step(); !rows[it][column].isZero }
                ?: continue
            val swap = rows[rank]
            rows[rank] = rows[pivot]
            rows[pivot] = swap
            arithmetic.step()
            val inverse = rows[rank][column].reciprocal()
            if (!admittedFraction(inverse)) return null
            for (entry in column..variables.size) {
                arithmetic.step()
                rows[rank][entry] = rows[rank][entry] * inverse
                if (!admittedFraction(rows[rank][entry])) return null
            }
            for (row in rows.indices) {
                arithmetic.step()
                if (row == rank || rows[row][column].isZero) continue
                val factor = rows[row][column]
                for (entry in column..variables.size) {
                    arithmetic.step()
                    val product = factor * rows[rank][entry]
                    if (!admittedFraction(product)) return null
                    arithmetic.step()
                    rows[row][entry] = rows[row][entry] - product
                    if (!admittedFraction(rows[row][entry])) return null
                }
            }
            rank++
        }
        for (row in rank until rows.size) {
            arithmetic.step()
            if (!rows[row].last().isZero) return null
        }
        return rows.take(rank)
    }

    private fun admittedFraction(value: BigFraction): Boolean {
        arithmetic.step()
        return value.num.magnitudeBitLength() <= MAX_WITNESS_BITS &&
            value.den.magnitudeBitLength() <= MAX_WITNESS_BITS
    }

    private fun coupledGuides(atoms: List<PolynomialAtom>): List<PolynomialAtom> {
        val nonlinear = ArrayList<PolynomialAtom>()
        val affine = ArrayList<PolynomialAtom>()
        val bounds = ArrayList<PolynomialAtom>()
        for ((index, atom) in atoms.withIndex()) {
            var curved = false
            val variables = LinkedHashSet<Int>()
            val payload = coupledTerms?.let { arithmetic.step(); it[index] } ?: atom.polynomial.terms
            for (term in payload) {
                arithmetic.step()
                val ids = term.monomial.variables
                variables.addAll(ids)
                if (ids.size > 1) curved = true
                for (id in ids) {
                    arithmetic.step()
                    if (term.monomial.exponent(id) > 1) curved = true
                }
            }
            when {
                curved -> nonlinear.add(atom)
                variables.size > 1 -> affine.add(atom)
                else -> bounds.add(atom)
            }
        }
        return nonlinear + affine + bounds
    }

    private fun chargeWitnessPolynomial(polynomial: Polynomial) {
        for (term in polynomial.terms) {
            arithmetic.step()
            repeat(term.monomial.variables.size + 1) { arithmetic.step() }
            val words = (term.coefficient.num.magnitudeBitLength() +
                term.coefficient.den.magnitudeBitLength()) / 64
            repeat(words + 1) { arithmetic.step() }
        }
    }

    private fun coupledLine(
        atoms: List<PolynomialAtom>,
        base: List<BigFraction>,
        direction: List<BigFraction>,
    ): QfNraAssignment? {
        // The private parameter lies outside every original ID, so substitutions cannot capture each other.
        val parameter = base.size
        if (base.any { !admittedFraction(it) } || direction.any { !admittedFraction(it) }) return null
        val replacements = base.indices.map { id ->
            arithmetic.add(
                Polynomial.constant(base[id]), arithmetic.scale(Polynomial.variable(parameter), direction[id]),
            )
        }
        val queryEnd = minOf(MAX_WITNESS_AXES / 2, queries + MAX_WITNESS_AXES / 8)
        val proposalEnd = minOf(MAX_WITNESS_PROPOSALS / 2, proposals + MAX_WITNESS_PROPOSALS / 8)
        val seen = ArrayList<Polynomial>()
        val boundaries = ArrayList<AlgebraicReal>()
        guides@ for (atom in atoms) {
            if (queries >= queryEnd) break
            var degree = 0
            for (term in atom.polynomial.terms) {
                var sum = 0
                for (id in term.monomial.variables) {
                    arithmetic.step()
                    if (!direction[id].isZero) {
                        val exponent = term.monomial.exponent(id)
                        if (exponent > MAX_WITNESS_DEGREE - sum) continue@guides
                        sum += exponent
                    }
                }
                degree = maxOf(degree, sum)
            }
            if (degree == 0) continue
            try {
                var residual = atom.polynomial
                for (id in atom.polynomial.variables) {
                    arithmetic.step()
                    residual = arithmetic.substitute(residual, id, replacements[id])
                    if (residual.terms.any { !admittedFraction(it.coefficient) }) continue@guides
                }
                chargeWitnessPolynomial(residual)
                if (residual.isZero || residual.degree(parameter) <= 0) continue
                if (seen.any {
                        chargeWitnessPolynomial(it)
                        it == residual
                    }
                ) continue
                seen.add(residual)
                for (root in roots(residual, parameter, emptyMap()) ?: return null) {
                    arithmetic.step()
                    boundaries.add(root)
                }
            } catch (_: PolynomialResourceLimit) {
                // A declined guide withholds no assertion from the complete point check.
            }
        }
        val roots = distinctRoots(boundaries)
        for (cell in coupledCells(roots)) {
            if (proposals >= proposalEnd) return null
            val root = cell / 2
            val sample = if (cell and 1 != 0) roots[root] else algebraic.rational(
                algebraic.sampleBetween(roots.getOrNull(root - 1), roots.getOrNull(root)),
            )
            val point = base.indices.associateWith { id ->
                arithmetic.step()
                if (direction[id].isZero) algebraic.rational(base[id]) else algebraic.add(
                    algebraic.rational(base[id]), algebraic.multiply(algebraic.rational(direction[id]), sample),
                )
            }
            candidate(point)?.let { return it }
        }
        return null
    }

    private fun coupledCells(roots: List<AlgebraicReal>): List<Int> {
        val zero = algebraic.rational(BigFraction.ZERO)
        var middle = 2 * roots.size
        var zeroSection = -1
        for ((index, root) in roots.withIndex()) {
            val comparison = algebraic.compare(root, zero)
            if (comparison >= 0) {
                middle = 2 * index
                if (comparison == 0) {
                    zeroSection = middle + 1
                    middle++
                }
                break
            }
        }
        val cells = ArrayList<Int>()
        for (distance in 0..2 * roots.size) {
            arithmetic.step()
            val right = middle + distance
            val left = middle - distance
            if (right <= 2 * roots.size && right != zeroSection) cells.add(right)
            if (distance != 0 && left >= 0 && left != zeroSection) cells.add(left)
        }
        return cells
    }

    fun boundaries(
        atoms: List<Pair<PolynomialAtom, Int>>,
        variable: Int,
        point: Map<Int, AlgebraicReal>,
    ): List<AlgebraicReal>? {
        arithmetic.step()
        val seen = ArrayList<Polynomial>()
        val boundaries = ArrayList<AlgebraicReal>()
        for ((atom, termCount) in atoms) {
            arithmetic.step()
            repeat(termCount + 1) { arithmetic.step() }
            if (atom.polynomial.degree(variable) > MAX_WITNESS_DEGREE) continue
            chargeWitnessPolynomial(atom.polynomial)
            if (seen.any {
                    chargeWitnessPolynomial(it)
                    it == atom.polynomial
                }
            ) continue
            val roots = roots(atom.polynomial, variable, point) ?: return null
            arithmetic.step()
            seen.add(atom.polynomial)
            for (root in roots) {
                arithmetic.step()
                boundaries.add(root)
            }
        }
        return distinctRoots(boundaries)
    }

    private fun distinctRoots(roots: MutableList<AlgebraicReal>): List<AlgebraicReal> {
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
    rejected: ((IntArray) -> Unit)? = null,
): QfNraAssignment? {
    val signs = if (rejected == null) null else {
        repeat(formula.atoms.size + 1) { arithmetic.step() }
        IntArray(formula.atoms.size)
    }
    val atomValues = IntArray(formula.atoms.size) { index ->
        arithmetic.step()
        val atom = formula.atoms[index]
        val sign = algebraic.sign(algebraic.evaluate(atom.polynomial, coordinates))
        if (signs != null) {
            arithmetic.step()
            signs[index] = sign
        }
        if (atom.holds(sign)) {
            PolynomialFormula.TRUE
        } else PolynomialFormula.FALSE
    }
    val bools = solveBooleans(formula, boolCount, atomValues, arithmetic) ?: run {
        if (signs != null) {
            arithmetic.step()
            rejected?.invoke(signs)
        }
        return null
    }
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
