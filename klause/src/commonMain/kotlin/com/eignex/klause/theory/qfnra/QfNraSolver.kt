package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.AffineLineEvaluation
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
    val booleanTheoryRegions: Long? = null,
    val booleanTheoryAtoms: Long? = null,
    val booleanTheoryConstantRefutations: Long? = null,
    val booleanTheoryResourceRefusals: Long? = null,
    val lastBooleanTheoryResource: PolynomialResource? = null,
    val equationalSections: Long? = null,
    val equationalRootLevels: Long? = null,
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
            var refuted = false
            val witness = work.probe {
                refuted = realCount <= MAX_WITNESS_REALS && (
                    refutePolynomialSigns(normalized, arithmetic, MAX_WITNESS_ATOMS, MAX_WITNESS_TERMS) ||
                        refutePolynomialMagnitudes(normalized, arithmetic, MAX_WITNESS_ATOMS, MAX_WITNESS_TERMS,
                            MAX_WITNESS_DEGREE, MAX_WITNESS_BITS) ||
                        refutePolynomialConsequences(normalized, arithmetic, MAX_WITNESS_ATOMS, MAX_WITNESS_TERMS,
                            MAX_WITNESS_BITS) ||
                        (boolCount <= MAX_WITNESS_BOOLS && normalized.refuteBooleans(arithmetic, MAX_WITNESS_ATOMS,
                            MAX_WITNESS_TERMS, MAX_WITNESS_BITS))
                    )
                if (refuted) null else WitnessProbe(normalized, boolCount, arithmetic, algebraic, work)
                    .probeWitness(realCount, fixed, work::coupled)
            }
            if (refuted || witness != null) {
                arithmetic.step()
                yield(if (refuted) QfNraResult.Unsatisfiable(work.snapshot())
                    else QfNraResult.Satisfiable(checkNotNull(witness), work.snapshot()))
                return@sequence
            }
            yield(null)
            work.equationalSections = 0
            work.equationalRootLevels = 0
            work.booleanTheoryRegions = 0
            work.booleanTheoryAtoms = 0
            work.booleanTheoryConstantRefutations = 0
            work.booleanTheoryResourceRefusals = 0
            val regions = normalized.booleanRegions(arithmetic)
            var incomplete: PolynomialResource? = null
            while (regions.hasNext()) {
                val region = regions.next()
                if (region == null) {
                    yield(null)
                    continue
                }
                val count = checkNotNull(work.booleanTheoryRegions)
                work.booleanTheoryRegions = count + minOf(1L, Long.MAX_VALUE - count)
                val atoms = checkNotNull(work.booleanTheoryAtoms)
                work.booleanTheoryAtoms = atoms + minOf(region.atoms.size.toLong(), Long.MAX_VALUE - atoms)
                try {
                    if (!regionFeasible(normalized, region, emptyMap(), arithmetic, algebraic)) {
                        val constants = checkNotNull(work.booleanTheoryConstantRefutations)
                        work.booleanTheoryConstantRefutations = constants + minOf(1L, Long.MAX_VALUE - constants)
                        continue
                    }
                    val nonzero = HashSet<Int>()
                    val polynomials = region.atoms.map { (index, truth) ->
                        arithmetic.step()
                        val atom = normalized.atoms[index]
                        if (atom.holds(0) != truth) {
                            val term = atom.polynomial.terms.singleOrNull()
                            if (term != null) for (variable in term.monomial.variables) {
                                arithmetic.step()
                                nonzero.add(variable)
                            }
                        }
                        atom.polynomial
                    }
                    val equations = region.atoms.mapNotNull { (index, truth) ->
                        arithmetic.step()
                        val atom = normalized.atoms[index]
                        if ((atom.relation == PolynomialRelation.EQ && truth) ||
                            (atom.relation == PolynomialRelation.NE && !truth)
                        ) atom.polynomial else null
                    }
                    val order = polynomialVariableOrder(polynomials, realCount, arithmetic)
                    val pending = PolynomialProjection.prepare(polynomials, realCount, arithmetic,
                        equations, order, nonzero, onRootSection = {
                            val roots = checkNotNull(work.equationalRootLevels)
                            work.equationalRootLevels = roots + minOf(1L, Long.MAX_VALUE - roots)
                        }) {
                        val sections = checkNotNull(work.equationalSections)
                        work.equationalSections = sections + minOf(1L, Long.MAX_VALUE - sections)
                    }
                    var projection: PolynomialProjection? = null
                    while (projection == null) {
                        projection = pending.next()
                        if (projection == null) yield(null)
                    }
                    if (projection.inconsistentEquations) continue
                    work.projectedPolynomials += minOf(projection.polynomialCount,
                        Long.MAX_VALUE - work.projectedPolynomials)
                    yield(null)
                    val assignment = searchCells(normalized, region, projection, boolCount,
                        arithmetic, algebraic, work, fixed)
                    if (assignment != null) {
                        arithmetic.step()
                        yield(QfNraResult.Satisfiable(assignment, work.snapshot()))
                        return@sequence
                    }
                } catch (stop: PolynomialResourceLimit) {
                    // An incomplete region can be skipped for SAT search, but withholds source exhaustion.
                    if (incomplete == null) incomplete = stop.resource
                    val refusals = checkNotNull(work.booleanTheoryResourceRefusals)
                    work.booleanTheoryResourceRefusals = refusals + minOf(1L, Long.MAX_VALUE - refusals)
                    work.lastBooleanTheoryResource = stop.resource
                    yield(null)
                }
            }
            arithmetic.step()
            yield(if (incomplete == null) QfNraResult.Unsatisfiable(work.snapshot())
                else QfNraResult.Unknown(QfNraReason.ResourceLimit, work.snapshot(), incomplete))
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
    var booleanTheoryRegions: Long? = null
    var booleanTheoryAtoms: Long? = null
    var booleanTheoryConstantRefutations: Long? = null
    var booleanTheoryResourceRefusals: Long? = null
    var lastBooleanTheoryResource: PolynomialResource? = null
    var equationalSections: Long? = null
    var equationalRootLevels: Long? = null
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
        dyadicAttempts, dyadicBases, coupledWorkDeclines, dyadicPreExtractionDeclines,
        booleanTheoryRegions, booleanTheoryAtoms, booleanTheoryConstantRefutations,
        booleanTheoryResourceRefusals, lastBooleanTheoryResource, equationalSections, equationalRootLevels)
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
                val guidePayloads = if (guideIndices.indices.all { index ->
                        arithmetic.step()
                        formula.atoms[guideIndices[index]].polynomial === conjunctive[index].polynomial &&
                            sourcePayloads[guideIndices[index]].size == guideTermCounts[index]
                    }
                ) {
                    repeat(3 * guideIndices.size + 1) { arithmetic.step() }
                    guideIndices.map { index -> arithmetic.step(); sourcePayloads[index] }
                } else null
                if (guidePayloads != null) {
                    admittedTerms = Triple(
                        conjunctive.toList(), guideIndices.toList(), guidePayloads,
                    )
                }
                try {
                    coupledPhase {
                        definitionWitness(conjunctive, realCount, fixed)
                            ?: coupled(conjunctive, point, fixed, guideIndices, guideTermCounts)
                    }?.let { return it }
                } finally {
                    admittedTerms = null
                }
                if (guidePayloads != null && axes.size > 1) {
                    supportWitness(conjunctive, guidePayloads, realCount, fixed)?.let { return it }
                }
            } else if (nonlinear) {
                coupledPhase {
                    formula.witnessAtoms(arithmetic, MAX_WITNESS_ATOMS)?.let {
                        definitionWitness(it, realCount, fixed)
                    }
                }?.let { return it }
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
                                var score = 0
                                candidate(proposed) { signs ->
                                    for ((index, comparison) in formula.atoms.withIndex()) {
                                        arithmetic.step()
                                        if (comparison.holds(signs[index])) score++
                                    }
                                }?.let { return it }
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
            if (nonlinear && conjunctive == null && !exhausted && queries < MAX_WITNESS_AXES) {
                return probeInequalityWitness(
                    formula.atoms, realCount, arithmetic, algebraic, fixed, terms, termCounts, this,
                )
            }
            return null
        } finally {
            admittedTerms = null
            coupledTerms = null
        }
    }

    private fun definitionWitness(
        atoms: List<PolynomialAtom>,
        realCount: Int,
        fixed: Map<Int, BigFraction>,
    ): QfNraAssignment? {
        if (exhausted) return null
        return try {
            val plan = PolynomialWitnessDefinitions(
                arithmetic, MAX_WITNESS_TERMS, MAX_WITNESS_DEGREE, MAX_WITNESS_BITS,
            ).prepare(atoms, realCount, fixed) ?: return null
            val point = plan.point() ?: return null
            candidate(captureRational(point)) ?: definitionBoundaries(plan, fixed)
        } catch (_: PolynomialResourceLimit) {
            null
        }
    }

    private fun captureRational(point: Map<Int, BigFraction>): Map<Int, AlgebraicReal> {
        val captured = LinkedHashMap<Int, AlgebraicReal>()
        for ((variable, value) in point) {
            arithmetic.step()
            captured[variable] = algebraic.rational(value)
        }
        return captured
    }

    private fun supportWitness(
        atoms: List<PolynomialAtom>,
        payloads: List<List<PolynomialTerm>>,
        realCount: Int,
        fixed: Map<Int, BigFraction>,
    ): QfNraAssignment? {
        if (exhausted || queries == MAX_WITNESS_AXES) return null
        return try {
            val point = PolynomialWitnessSupports(
                arithmetic, MAX_WITNESS_TERMS, MAX_WITNESS_DEGREE, MAX_WITNESS_BITS, MAX_WITNESS_PASSES,
            )
                .point(atoms, payloads, realCount, fixed, takeAxis = {
                    arithmetic.step()
                    if (queries == MAX_WITNESS_AXES) false else { queries++; true }
                }, takeProposal = {
                    arithmetic.step()
                    if (proposals >= MAX_WITNESS_PROPOSALS - 1) false else { proposals++; true }
                }) ?: return null
            candidate(captureRational(point))
        } catch (_: PolynomialResourceLimit) {
            null
        }
    }

    private fun definitionBoundaries(
        plan: PolynomialWitnessDefinitions.Plan,
        fixed: Map<Int, BigFraction>,
    ): QfNraAssignment? {
        for (atom in plan.residualAtoms) {
            arithmetic.step()
            val payload = atom.polynomial.terms
            val nonlinear = payload.any { term ->
                arithmetic.step()
                val variables = term.monomial.variables
                variables.size > 1 || variables.any {
                    arithmetic.step()
                    term.monomial.exponent(it) > 1
                }
            }
            if (!nonlinear) continue
            for (variable in atom.polynomial.variables) {
                arithmetic.step()
                if (variable in fixed) continue
                if (queries >= MAX_WITNESS_AXES / 2 || proposals >= MAX_WITNESS_PROPOSALS / 2) return null
                queries++
                val axis = plan.axis(atom.polynomial, variable) ?: return null
                if (axis.isZero || axis.degree <= 0) continue
                val boundaries = ArrayList<AlgebraicReal?>()
                for (root in algebraic.roots(axis)) { arithmetic.step(); boundaries.add(root) }
                val seen = HashSet<BigFraction>()
                while (boundaries.any { arithmetic.step(); it != null }) {
                    for (index in boundaries.indices) {
                        arithmetic.step()
                        if (exhausted || proposals >= MAX_WITNESS_PROPOSALS / 2) return null
                        var root = boundaries[index] ?: continue
                        repeat(MAX_WITNESS_PASSES) {
                            arithmetic.step()
                            if (root.rational == null) root = algebraic.refine(root)
                        }
                        boundaries[index] = if (root.rational == null) root else null
                        for (value in listOf(root.lowerBound, root.upperBound)) {
                            arithmetic.step()
                            if (!seen.add(value)) continue
                            if (exhausted || proposals >= MAX_WITNESS_PROPOSALS / 2) return null
                            val proposed = plan.point(variable, value) ?: return null
                            candidate(captureRational(proposed))?.let { return it }
                        }
                    }
                }
            }
        }
        return null
    }

    fun candidate(point: Map<Int, AlgebraicReal>): QfNraAssignment? = candidate(point, null)

    private fun candidate(point: Map<Int, AlgebraicReal>, rank: ((IntArray) -> Unit)?): QfNraAssignment? {
        if (exhausted) return null
        arithmetic.step()
        proposals++
        val context = retentionGuides
        return checkSample(
            formula, point, boolCount, arithmetic, algebraic,
            if (context == null && rank == null) null else { captured, signs ->
                if (context != null) retainRejectedPoint(captured, signs, context)
                rank?.invoke(signs)
            },
            reusePowers = true,
        )
    }

    private fun candidate(point: AffineLineEvaluation.Point): QfNraAssignment? {
        if (exhausted) return null
        arithmetic.step()
        proposals++
        val context = retentionGuides
        return checkLineSample(
            formula, point, boolCount, arithmetic, algebraic,
            if (context == null) null else { captured, signs -> retainRejectedPoint(captured, signs, context) },
        )
    }

    private fun retainRejectedPoint(
        point: AlgebraicArithmetic.PointEvaluation,
        signs: IntArray,
        context: Pair<List<PolynomialAtom>, List<Int>>,
    ) {
        try {
            val variables = retentionVariables ?: return
            if (point.coordinateCount != retentionCount) return
            var nonzero = 0
            for (variable in variables) {
                arithmetic.step()
                if (algebraic.sign(point.coordinate(variable)) != 0) nonzero++
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
                snapshot[id] = point.coordinate(id)
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
                val variables = term.monomial.variables.filter {
                    arithmetic.step()
                    if (it !in seed.indices) return null
                    it !in fixed
                }
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
        val weights = DyadicWeights(seed.size)
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
                val chosen = dyadicLeadingTerm(terms, orientation, weights) ?: return null
                arithmetic.step()
                selected[index] = chosen
                changed = dyadicGuide(terms, chosen, orientation, weights, active, fixed, true)
                    ?.let { changed || it } ?: return null
            }
            if (!changed) break
        }
        for (index in atoms.indices) {
            arithmetic.step()
            if (selected[index] < 0) continue
            dyadicGuide(payloads[index], selected[index],
                dyadicOrientation(atoms[index].relation, payloads[index]),
                weights, active, fixed, false) ?: return null
        }
        repeat(seed.size + 1) { arithmetic.step() }
        val base = seed.mapIndexed { id, value ->
            arithmetic.step()
            if (id in active && id !in fixed) dyadicValue(weights[id]) else value
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
        weights: DyadicWeights,
    ): Int? {
        var selected: Int? = null
        var greatest = Long.MIN_VALUE
        for ((index, term) in terms.withIndex()) {
            arithmetic.step()
            if (term.coefficient.signum() * orientation <= 0) continue
            val weight = weights.weight(term.monomial) ?: return null
            if (selected == null || weight > greatest) {
                arithmetic.step()
                selected = index
                greatest = weight
            }
        }
        return selected
    }

    private data class DyadicWeightEntry(val revision: Long, val value: Long)

    private inner class DyadicWeights(val size: Int) {
        init { arithmetic.step() }

        private val exponents = IntArray(size)
        private var revision = 0L
        private val cache = HashMap<Monomial, DyadicWeightEntry>()

        operator fun get(variable: Int): Int = exponents[variable]

        fun replace(variable: Int, exponent: Int) {
            arithmetic.step()
            check(revision < Long.MAX_VALUE)
            arithmetic.step()
            exponents[variable] = exponent
            revision++
            arithmetic.step()
        }

        fun weight(monomial: Monomial): Long? {
            arithmetic.step()
            val retained = cache[monomial]
            arithmetic.step()
            if (retained != null && retained.revision == revision) return retained.value
            val variables = monomial.variables
            repeat(variables.size) { arithmetic.step() }
            var weight = 0L
            for (id in variables) {
                arithmetic.step()
                if (id !in exponents.indices) return null
                val degree = monomial.exponent(id).toLong()
                val exponent = exponents[id].toLong()
                if (degree < 0 || exponent !in -(MAX_WITNESS_BITS - 1L)..(MAX_WITNESS_BITS - 1L)) return null
                arithmetic.step()
                // An Int degree times an admitted exponent fits Long before the checked sum.
                val product = degree * exponent
                weight = dyadicSum(weight, product) ?: return null
            }
            arithmetic.step()
            val completed = DyadicWeightEntry(revision, weight)
            arithmetic.step()
            cache[monomial] = completed
            return weight
        }
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
        weights: DyadicWeights,
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
            val left = weights.weight(positive.monomial) ?: return null
            val right = weights.weight(negative.monomial) ?: return null
            val gap = dyadicSum(left, -right) ?: return null
            if (gap >= margin) continue
            if (!repair) return null
            val deficit = dyadicSum(margin, -gap) ?: return null
            if (!repairDyadicRow(positive.monomial, negative.monomial, deficit,
                    weights, active, fixed)) return null
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
        weights: DyadicWeights,
        active: Set<Int>,
        fixed: Map<Int, BigFraction>,
    ): Boolean {
        var selected = -1
        var greatest = 0L
        var replacement = 0
        for (id in 0 until weights.size) {
            arithmetic.step()
            if (id !in active || id in fixed) continue
            arithmetic.step()
            val coefficient = positive.exponent(id).toLong() - negative.exponent(id)
            val magnitude = if (coefficient < 0) -coefficient else coefficient
            if (magnitude == 0L || magnitude <= greatest) continue
            arithmetic.step()
            val movement = (deficit - 1) / magnitude + 1
            val value = dyadicSum(weights[id].toLong(), if (coefficient > 0) movement else -movement)
                ?: continue
            if (value !in -(MAX_WITNESS_BITS - 1L)..(MAX_WITNESS_BITS - 1L)) continue
            arithmetic.step()
            selected = id
            greatest = magnitude
            replacement = value.toInt()
        }
        if (selected < 0) return false
        weights.replace(selected, replacement)
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
        val line = AffineLineEvaluation(base, direction, algebraic)
        for (cell in coupledCells(roots)) {
            if (proposals >= proposalEnd) return null
            val root = cell / 2
            val sample = if (cell and 1 != 0) roots[root] else algebraic.rational(
                algebraic.sampleBetween(roots.getOrNull(root - 1), roots.getOrNull(root)),
            )
            candidate(line.at(sample))?.let { return it }
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

// Depth-first lifting over cylindrical algebraic decomposition cells: per coordinate, the projection
// polynomials' roots split the line into sections and sectors, each sampled once. Collins, "Quantifier
// elimination for real closed fields by cylindrical algebraic decomposition", LNCS 33, 1975.
// https://doi.org/10.1007/3-540-07407-4_17
private fun searchCells(
    formula: PolynomialFormula,
    region: PolynomialBooleanRegion,
    projection: PolynomialProjection,
    boolCount: Int,
    arithmetic: PolynomialArithmetic,
    algebraic: AlgebraicArithmetic,
    work: QfNraWork,
    fixed: Map<Int, BigFraction>,
): QfNraAssignment? {
    val coordinates = HashMap<Int, AlgebraicReal>()
    if (projection.variableCount == 0) {
        return checkRegionSample(formula, region, coordinates, boolCount, arithmetic, algebraic)
    }
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
        val coordinate = frame.point ?: if (frame.rootsOnly) frame.roots[cell.toInt()]
        else if (cell and 1L != 0L) frame.roots[root] else {
            algebraic.rational(algebraic.sampleBetween(frame.roots.getOrNull(root - 1), frame.roots.getOrNull(root)))
        }
        coordinates[frame.variable] = coordinate
        if (work.liftedCells < Long.MAX_VALUE) work.liftedCells++
        if (frame.level + 1 < projection.variableCount) {
            if (regionFeasible(formula, region, coordinates, arithmetic, algebraic, atomLevels)) {
                frames.addLast(liftFrame(projection, frame.level + 1, coordinates, arithmetic, algebraic, fixed))
            }
        } else {
            val assignment = if (regionFeasible(formula, region, coordinates, arithmetic, algebraic, atomLevels)) {
                checkRegionSample(formula, region, coordinates, boolCount, arithmetic, algebraic)
            } else null
            if (assignment != null) return assignment
        }
    }
    return null
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

private fun regionFeasible(
    formula: PolynomialFormula,
    region: PolynomialBooleanRegion,
    coordinates: Map<Int, AlgebraicReal>,
    arithmetic: PolynomialArithmetic,
    algebraic: AlgebraicArithmetic,
    atomLevels: Map<Int, Int>? = null,
): Boolean {
    for ((index, required) in region.atoms) {
        arithmetic.step()
        val atom = formula.atoms[index]
        val variable = atom.polynomial.mainVariable
        if (variable != null && (atomLevels?.getValue(index) ?: variable) >= coordinates.size) continue
        val sign = algebraic.sign(algebraic.evaluate(atom.polynomial, coordinates))
        if (atom.holds(sign) != required) return false
    }
    return true
}

private fun checkRegionSample(
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
        val sign = algebraic.sign(algebraic.evaluate(atom.polynomial, coordinates))
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

private fun checkSample(
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
        val value = evaluation?.evaluate(atom.polynomial) ?: algebraic.evaluate(atom.polynomial, coordinates)
        val sign = algebraic.sign(value)
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

private fun checkLineSample(
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
