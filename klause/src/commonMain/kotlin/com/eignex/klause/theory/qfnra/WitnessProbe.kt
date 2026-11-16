package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.AffineLineEvaluation
import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.AlgebraicReal
import com.eignex.klause.arithmetic.polynomial.Monomial
import com.eignex.klause.arithmetic.polynomial.Polynomial
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialResourceLimit
import com.eignex.klause.arithmetic.polynomial.PolynomialTerm
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.magnitudeBitLength
import com.eignex.util.numbers.shl

internal const val MAX_WITNESS_WORK = 65_536L
internal const val MAX_WITNESS_REALS = 32
internal const val MAX_WITNESS_BOOLS = 64
internal const val MAX_WITNESS_ATOMS = 128
internal const val MAX_WITNESS_TERMS = 2048
internal const val MAX_WITNESS_DEGREE = 8
internal const val MAX_WITNESS_BITS = 4096
private const val MAX_WITNESS_AXES = 32
private const val MAX_WITNESS_PROPOSALS = 64
internal const val MAX_WITNESS_PASSES = 4

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
    var failureCapture: PolynomialFailureCapture? = null
    var captureSnapshot: Long = 0
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
                    guideIndices.map { index ->
                        arithmetic.step()
                        sourcePayloads[index]
                    }
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
                    if (queries == MAX_WITNESS_AXES) false else {
                        queries++
                        true
                    }
                }, takeProposal = {
                    arithmetic.step()
                    if (proposals >= MAX_WITNESS_PROPOSALS - 1) false else {
                        proposals++
                        true
                    }
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
                for (root in algebraic.roots(axis)) {
                    arithmetic.step()
                    boundaries.add(root)
                }
                val seen = HashSet<BigFraction>()
                while (boundaries.any {
                        arithmetic.step()
                        it != null
                    }) {
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
        val result = checkSample(
            formula, point, boolCount, arithmetic, algebraic,
            if (context == null && rank == null && failureCapture == null) null else { captured, signs ->
                if (context != null) retainRejectedPoint(captured, signs, context)
                rank?.invoke(signs)
                failureCapture?.rejected(formula, null, point, null, captureSnapshot,
                    work?.spentWork ?: 0, arithmetic, signs)
            },
            reusePowers = true,
        )
        return result
    }

    private fun candidate(point: AffineLineEvaluation.Point): QfNraAssignment? {
        if (exhausted) return null
        arithmetic.step()
        proposals++
        val context = retentionGuides
        return checkLineSample(
            formula, point, boolCount, arithmetic, algebraic,
            if (context == null && failureCapture == null) null else { captured, signs ->
                if (context != null) retainRejectedPoint(captured, signs, context)
                failureCapture?.let { capture ->
                    val coordinates = (0 until captured.coordinateCount).associateWith {
                        arithmetic.step()
                        captured.coordinate(it)
                    }
                    capture.rejected(formula, null, coordinates, null, captureSnapshot,
                        work?.spentWork ?: 0, arithmetic, signs)
                }
            },
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
            val payload = coupledTerms?.let {
                arithmetic.step()
                it[index]
            } ?: atom.polynomial.terms
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
            variables.indices.first { column ->
                arithmetic.step()
                !row[column].isZero
            }
        }
        val free = variables.indices.filter {
            arithmetic.step()
            it !in pivots
        }
        val scaled = if (queries == 0 && atomTermCounts != null && affine.isEmpty() && atoms.none {
                arithmetic.step()
                it.relation == PolynomialRelation.EQ && !it.polynomial.isZero
            }
        ) try {
            work?.let {
                it.dyadicAttempts += minOf(1L, Long.MAX_VALUE - it.dyadicAttempts)
                it.dyadicPreExtraction = true
            }
            DyadicWitnessBase(arithmetic).base(atoms, atomTermCounts, seed, active, fixed, coupledTerms, work).also { base ->
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
        for (group in orderedGroups) {
            for (alternating in listOf(false, true)) {
                for (center in bases.indices) {
                    if (queries >= MAX_WITNESS_AXES / 2 || proposals >= MAX_WITNESS_PROPOSALS / 2) return null
                    offer(center, group, alternating)?.let { return it }
                }
            }
        }
        return null
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
                if (!arithmetic.admitsWitnessFraction(product)) return null
                arithmetic.step()
                value -= product
                if (!arithmetic.admitsWitnessFraction(value)) return null
            }
            base[variables[pivots[index]]] = value
        }
        return base.takeIf { it.all(arithmetic::admitsWitnessFraction) }
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
        val direction = MutableList(size) {
            arithmetic.step()
            BigFraction.ZERO
        }
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
                if (!arithmetic.admitsWitnessFraction(product)) return null
                arithmetic.step()
                value -= product
                if (!arithmetic.admitsWitnessFraction(value)) return null
            }
            direction[variables[pivots[index]]] = value
        }
        return direction.takeIf {
            it.count { value ->
                arithmetic.step()
                !value.isZero
            } >= 2
        }
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
            val pivot = (rank until rows.size).firstOrNull {
                arithmetic.step()
                !rows[it][column].isZero
            }
                ?: continue
            val swap = rows[rank]
            rows[rank] = rows[pivot]
            rows[pivot] = swap
            arithmetic.step()
            val inverse = rows[rank][column].reciprocal()
            if (!arithmetic.admitsWitnessFraction(inverse)) return null
            for (entry in column..variables.size) {
                arithmetic.step()
                rows[rank][entry] = rows[rank][entry] * inverse
                if (!arithmetic.admitsWitnessFraction(rows[rank][entry])) return null
            }
            for (row in rows.indices) {
                arithmetic.step()
                if (row == rank || rows[row][column].isZero) continue
                val factor = rows[row][column]
                for (entry in column..variables.size) {
                    arithmetic.step()
                    val product = factor * rows[rank][entry]
                    if (!arithmetic.admitsWitnessFraction(product)) return null
                    arithmetic.step()
                    rows[row][entry] = rows[row][entry] - product
                    if (!arithmetic.admitsWitnessFraction(rows[row][entry])) return null
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

    private fun coupledGuides(atoms: List<PolynomialAtom>): List<PolynomialAtom> {
        val nonlinear = ArrayList<PolynomialAtom>()
        val affine = ArrayList<PolynomialAtom>()
        val bounds = ArrayList<PolynomialAtom>()
        for ((index, atom) in atoms.withIndex()) {
            var curved = false
            val variables = LinkedHashSet<Int>()
            val payload = coupledTerms?.let {
                arithmetic.step()
                it[index]
            } ?: atom.polynomial.terms
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
        if (base.any { !arithmetic.admitsWitnessFraction(it) } || direction.any { !arithmetic.admitsWitnessFraction(it) }) return null
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
                    if (residual.terms.any { !arithmetic.admitsWitnessFraction(it.coefficient) }) continue@guides
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
