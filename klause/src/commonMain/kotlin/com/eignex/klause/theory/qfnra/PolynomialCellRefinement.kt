package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.AlgebraicReal
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.arithmetic.polynomial.PolynomialLimits
import com.eignex.klause.arithmetic.polynomial.PolynomialResourceLimit
import com.eignex.klause.arithmetic.polynomial.RationalPolynomial
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import com.eignex.klause.util.magnitudeBitLength

internal sealed interface PolynomialCellScope {
    val source: PolynomialRealFormula

    class Source private constructor(
        override val source: PolynomialRealFormula,
        val predicates: List<PolynomialAtom>,
    ) : PolynomialCellScope {
        companion object {
            fun prepare(source: PolynomialRealFormula, arithmetic: PolynomialArithmetic): Source =
                Source(source, PolynomialFormula.normalize(source, arithmetic).forcedAtoms(arithmetic)
                    .orEmpty().take(MAX_CELL_PREMISES))
        }
    }
    class Query(
        override val source: PolynomialRealFormula,
        val condition: PolynomialCondition,
        val snapshot: PolynomialFormula,
    ) : PolynomialCellScope
    class Region(
        override val source: PolynomialRealFormula,
        val condition: PolynomialCondition,
        val snapshot: PolynomialFormula,
        val premises: PolynomialBooleanRegion,
        val reduction: Any,
    ) : PolynomialCellScope
}

internal class CheckedPolynomialCell private constructor(
    val variable: Int,
    val predicate: PolynomialAtom,
    val scope: PolynomialCellScope,
    val lower: AlgebraicReal?,
    val upper: AlgebraicReal?,
    val lowerClosed: Boolean,
    val upperClosed: Boolean,
) {
    fun contains(value: AlgebraicReal, algebraic: AlgebraicArithmetic): Boolean {
        lower?.let {
            val comparison = algebraic.compare(value, it)
            if (comparison < 0 || (comparison == 0 && !lowerClosed)) return false
        }
        upper?.let {
            val comparison = algebraic.compare(value, it)
            if (comparison > 0 || (comparison == 0 && !upperClosed)) return false
        }
        return true
    }

    companion object {
        fun rejected(
            predicate: PolynomialAtom,
            scope: PolynomialCellScope,
            value: AlgebraicReal,
            arithmetic: PolynomialArithmetic,
            algebraic: AlgebraicArithmetic,
        ): CheckedPolynomialCell? {
            arithmetic.step()
            if (scope is PolynomialCellScope.Source && scope.predicates.none {
                    arithmetic.step()
                    it == predicate
                }
            ) return null
            if (predicate.polynomial.termCount > MAX_CELL_TERMS) return null
            val terms = predicate.polynomial.terms
            var variable: Int? = null
            var degree = 0
            for (term in terms) {
                arithmetic.step()
                if (term.monomial.variableCount > 1 ||
                    term.coefficient.num.magnitudeBitLength() > MAX_CELL_BITS ||
                    term.coefficient.den.magnitudeBitLength() > MAX_CELL_BITS
                ) return null
                for (id in term.monomial.variables) {
                    arithmetic.step()
                    if (variable != null && variable != id) return null
                    variable = id
                    degree = maxOf(degree, term.monomial.exponent(id))
                    if (degree > MAX_CELL_DEGREE) return null
                }
            }
            val id = variable ?: return null
            val coefficients = MutableList(degree + 1) { BigFraction.ZERO }
            for (term in terms) {
                arithmetic.step()
                coefficients[term.monomial.exponent(id)] = term.coefficient
            }
            val polynomial = RationalPolynomial(coefficients)
            if (predicate.holds(algebraic.signAt(polynomial, value))) return null
            // All roots are isolated before selecting a sign-invariant section or sector.
            val roots = algebraic.roots(polynomial)
            var lower: AlgebraicReal? = null
            for (root in roots) {
                arithmetic.step()
                val comparison = algebraic.compare(value, root)
                if (comparison == 0) {
                    return CheckedPolynomialCell(id, predicate, scope, root, root, true, true)
                }
                if (comparison < 0) {
                    return CheckedPolynomialCell(id, predicate, scope, lower, root, false, false)
                }
                lower = root
            }
            arithmetic.step()
            return CheckedPolynomialCell(id, predicate, scope, lower, null, false, false)
        }
    }
}

internal data class PolynomialCellStats(
    var candidates: Long = 0,
    var checkedCells: Long = 0,
    var repeatedCandidates: Long = 0,
    var repairedCoordinates: Long = 0,
    var declined: Long = 0,
    var work: Long = 0,
)

internal class PolynomialCellRefinement(
    private val source: PolynomialRealFormula,
    private val condition: PolynomialCondition,
    private val parent: QfNraWork,
    limits: PolynomialLimits,
    private val onCell: (CheckedPolynomialCell) -> Unit,
) {
    val stats = PolynomialCellStats()
    private val retained = ArrayList<CheckedPolynomialCell>()
    val cells: List<CheckedPolynomialCell> get() = retained.toList()
    private var parentStopped = false
    private val meter = WorkMeter {
        stats.work += it
        parent.charge(it)
    }
    private val token = object : Cancellation {
        override fun isCancelled(): Boolean {
            if (parent()) parentStopped = true
            return parentStopped || stats.work >= MAX_CELL_WORK
        }
        override fun deadline() = parent.deadline()
        override fun workMeter(): WorkMeter = meter
    }
    private val arithmetic = PolynomialArithmetic(token, limits)
    private val algebraic = AlgebraicArithmetic(arithmetic)
    private var sourceScope: PolynomialCellScope.Source? = null
    private var sourceAtoms: List<PolynomialAtom> = emptyList()
    private var seedAtoms: List<PolynomialAtom> = emptyList()
    private var queryScope: PolynomialCellScope.Query? = null

    fun prepare(normalized: PolynomialFormula) {
        optional {
            sourceScope = PolynomialCellScope.Source.prepare(source, arithmetic)
            sourceAtoms = checkNotNull(sourceScope).predicates
            seedAtoms = normalized.forcedAtoms(arithmetic).orEmpty().take(MAX_CELL_PREMISES)
            queryScope = PolynomialCellScope.Query(source, condition, normalized)
        }
    }

    fun witness(
        realCount: Int,
        fixed: Map<Int, BigFraction>,
        check: (Map<Int, AlgebraicReal>, PolynomialArithmetic, AlgebraicArithmetic) -> QfNraAssignment?,
    ): QfNraAssignment? = optional {
        val point = seed(realCount, fixed) ?: return@optional null
        check(point, arithmetic, algebraic)
    }

    private fun seed(realCount: Int, fixed: Map<Int, BigFraction>): Map<Int, AlgebraicReal>? = optional {
        if (realCount > MAX_CELL_PREMISES || seedAtoms.isEmpty()) return@optional null
        val scope = queryScope ?: return@optional null
        val point = HashMap<Int, AlgebraicReal>()
        for (id in 0 until realCount) {
            arithmetic.step()
            val initial = algebraic.rational(fixed[id] ?: BigFraction.ZERO)
            point[id] = if (id in fixed) initial else repair(id, initial, seedAtoms, scope) ?: return@optional null
        }
        point
    }

    fun region(
        snapshot: PolynomialFormula,
        premises: PolynomialBooleanRegion,
        reduction: PolynomialAffineReduction,
    ): PolynomialCellRegion? = optional {
        if (premises.atoms.size + premises.bools.size > MAX_CELL_PREMISES ||
            reduction.atoms.size > MAX_CELL_PREMISES
        ) return@optional null
        val atoms = reduction.atoms.map { arithmetic.step(); it }
        val scope = PolynomialCellScope.Region(source, condition, snapshot,
            PolynomialBooleanRegion(premises.atoms.mapValues { arithmetic.step(); it.value },
                premises.bools.mapValues { arithmetic.step(); it.value }), Any())
        PolynomialCellRegion(scope, atoms)
    }

    inner class PolynomialCellRegion(
        val scope: PolynomialCellScope.Region,
        private val atoms: List<PolynomialAtom>,
    ) {
        fun repair(variable: Int, value: AlgebraicReal): AlgebraicReal? = optional {
            repair(variable, value, atoms, scope)
        }
    }

    private fun repair(
        variable: Int,
        initial: AlgebraicReal,
        atoms: List<PolynomialAtom>,
        scope: PolynomialCellScope,
    ): AlgebraicReal? {
        val unary = atoms.filter { atom ->
            arithmetic.step()
            atom.polynomial.termCount <= MAX_CELL_TERMS && atom.polynomial.terms.all {
                arithmetic.step()
                it.monomial.variableCount <= 1 && it.monomial.variables.all { id ->
                    arithmetic.step()
                    id == variable && it.monomial.exponent(id) <= MAX_CELL_DEGREE
                }
            } && atom.polynomial.degree(variable) > 0
        }
        if (unary.isEmpty()) return initial
        var value = initial
        while (true) {
            arithmetic.step()
            stats.candidates++
            val rejected = unary.firstOrNull { atom ->
                arithmetic.step()
                atom.holds(algebraic.signAt(atom.polynomial, mapOf(variable to value))).not()
            }
            if (rejected == null) {
                if (algebraic.compare(value, initial) != 0) stats.repairedCoordinates++
                return value
            }
            val support = if (sourceAtoms.any { arithmetic.step(); it == rejected }) checkNotNull(sourceScope) else scope
            val existing = retained.filter {
                arithmetic.step()
                it.variable == variable && (it.scope === scope || it.scope === sourceScope)
            }
            if (existing.any { arithmetic.step(); it.contains(value, algebraic) }) {
                stats.repeatedCandidates++
            } else {
                if (retained.size == MAX_CELL_COUNT) return null
                val cell = CheckedPolynomialCell.rejected(rejected, support, value, arithmetic, algebraic) ?: return null
                arithmetic.step()
                retained.add(cell)
                stats.checkedCells++
                onCell(cell)
            }
            value = next(variable, scope) ?: return null
        }
    }

    private fun next(variable: Int, scope: PolynomialCellScope): AlgebraicReal? {
        val cells = retained.filter {
            arithmetic.step()
            it.variable == variable && (it.scope === scope || it.scope === sourceScope)
        }
        val lower = condition.lower[variable]?.let(algebraic::rational)
        val upper = condition.upper[variable]?.let(algebraic::rational)
        val boundaries = ArrayList<AlgebraicReal>()
        lower?.let { boundaries.add(it) }
        upper?.let { boundaries.add(it) }
        for (cell in cells) {
            arithmetic.step()
            cell.lower?.let { boundaries.add(it) }
            cell.upper?.let { boundaries.add(it) }
        }
        boundaries.sortWith(Comparator(algebraic::compare))
        val distinct = ArrayList<AlgebraicReal>()
        for (boundary in boundaries) {
            arithmetic.step()
            if (distinct.isEmpty() || algebraic.compare(distinct.last(), boundary) != 0) distinct.add(boundary)
        }
        fun accepted(value: AlgebraicReal): Boolean {
            arithmetic.step()
            return (lower == null || algebraic.compare(value, lower) >= 0) &&
                (upper == null || algebraic.compare(value, upper) <= 0) &&
                cells.none { arithmetic.step(); it.contains(value, algebraic) }
        }
        for (boundary in distinct) if (accepted(boundary)) return boundary
        for (index in 0..distinct.size) {
            arithmetic.step()
            val sample = algebraic.rational(algebraic.sampleBetween(distinct.getOrNull(index - 1),
                distinct.getOrNull(index)))
            if (accepted(sample)) return sample
        }
        return null
    }

    private fun <T> optional(block: () -> T): T? {
        if (stats.work >= MAX_CELL_WORK) return null
        return try {
            arithmetic.step()
            block()
        } catch (stop: PolynomialInterrupted) {
            if (parentStopped) throw stop
            stats.declined++
            null
        } catch (_: PolynomialResourceLimit) {
            stats.declined++
            null
        }
    }
}

private const val MAX_CELL_DEGREE = 16
private const val MAX_CELL_TERMS = 64
private const val MAX_CELL_COUNT = 8
private const val MAX_CELL_PREMISES = 64
private const val MAX_CELL_WORK = 16_384L

private const val MAX_CELL_BITS = 4096
