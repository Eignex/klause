package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.Monomial
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialResourceLimit
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.lp.engine.CertifiedLpResult
import com.eignex.klause.lp.engine.LpVerdict
import com.eignex.klause.lp.relaxation.CertifiedEnvelopeLp
import com.eignex.klause.lp.relaxation.EnvelopeInterval
import com.eignex.klause.lp.relaxation.envelopeRow
import com.eignex.klause.lp.relaxation.productEnvelopes
import com.eignex.klause.lp.relaxation.squareEnvelopes
import com.eignex.klause.lp.relaxation.squareTangent
import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BigFraction

internal class PolynomialLpRelaxation private constructor(
    private val lp: CertifiedEnvelopeLp,
    private val squares: List<Pair<Int, Int>>,
    private val products: List<PolynomialEnvelopeProduct>,
    private val bounds: MutableList<EnvelopeInterval>,
    private val arithmetic: PolynomialArithmetic,
) : AutoCloseable {
    private val tangents = HashSet<Pair<Int, BigFraction>>()

    fun solve(column: Int? = null, upper: Boolean = false): CertifiedLpResult? =
        arithmetic.execution.handoff { lp.solve(column, upper) }

    fun refine(point: List<BigFraction>): Boolean {
        val rows = ArrayList<ExactRationalInequality>()
        for ((operand, result) in squares) {
            arithmetic.step(point[operand], point[operand])
            if (point[result] >= point[operand] * point[operand]) continue
            if (tangents.add(operand to point[operand])) rows += squareTangent(operand, result, point[operand])
        }
        return rows.isNotEmpty() && append(rows)
    }

    fun tightenSquares(onQuery: () -> Unit): PolynomialLpTightening {
        var changed = false
        for ((operand, result) in squares.asReversed()) {
            arithmetic.step()
            if (bounds[operand].lower != null && bounds[operand].upper != null) continue
            val solved = solve(result, upper = true)
            onQuery()
            arithmetic.step()
            if (solved?.verdict == LpVerdict.INFEASIBLE) return PolynomialLpTightening(changed, infeasible = true)
            val upper = solved?.lowerBound?.negated() ?: continue
            if (upper < BigFraction.ZERO) return PolynomialLpTightening(changed, infeasible = true)
            // For u >= 0, sqrt(u) <= max(1, u); zero has the singleton preimage {0}.
            val radius = if (upper.isZero || upper > BigFraction.ONE) upper else BigFraction.ONE
            val old = bounds[operand]
            val lo = old.lower?.let { if (it > radius.negated()) it else radius.negated() } ?: radius.negated()
            val hi = old.upper?.let { if (it < radius) it else radius } ?: radius
            if (old == EnvelopeInterval(lo, hi)) continue
            bounds[operand] = EnvelopeInterval(lo, hi)
            if (!arithmetic.execution.handoff { lp.narrow(operand, bounds[operand]) }) break
            changed = true
        }
        if (changed) {
            val rows = ArrayList<ExactRationalInequality>()
            for (product in products) {
                arithmetic.step()
                val image = if (product.left == product.right) bounds[product.left].square()
                else bounds[product.left].product(bounds[product.right])
                val previous = bounds[product.result]
                val interval = EnvelopeInterval(
                    image.lower?.let { lo -> previous.lower?.let { if (it > lo) it else lo } ?: lo } ?: previous.lower,
                    image.upper?.let { hi -> previous.upper?.let { if (it < hi) it else hi } ?: hi } ?: previous.upper,
                )
                bounds[product.result] = interval
                if (!arithmetic.execution.handoff { lp.narrow(product.result, interval) }) break
                rows += if (product.left == product.right) {
                    squareEnvelopes(product.left, product.result, bounds[product.left])
                } else {
                    productEnvelopes(product.left, product.right, product.result,
                        bounds[product.left], bounds[product.right])
                }
            }
            append(rows)
        }
        return PolynomialLpTightening(changed)
    }

    fun split(point: List<BigFraction>): Pair<Int, BigFraction>? {
        var selected: Int? = null
        var width = BigFraction.ZERO
        for (product in products) {
            arithmetic.step(point[product.left], point[product.right])
            if (point[product.result] == point[product.left] * point[product.right]) continue
            for (variable in product.variables) {
                arithmetic.step()
                val lo = bounds[variable].lower ?: continue
                val hi = bounds[variable].upper ?: continue
                val candidate = hi - lo
                if (candidate > width) {
                    selected = variable
                    width = candidate
                }
            }
        }
        val variable = selected ?: return null
        val interval = bounds[variable]
        val middle = (checkNotNull(interval.lower) + checkNotNull(interval.upper)) * BigFraction.ofLong(2).reciprocal()
        return variable to middle
    }

    override fun close() = lp.close()

    private fun append(rows: List<ExactRationalInequality>): Boolean {
        val count = lp.rows.toLong() + rows.size
        arithmetic.matrixCells(count, lp.variables.toLong() + count + 1)
        return arithmetic.execution.handoff { lp.append(rows) }
    }

    companion object {
        fun prepare(
            atoms: List<PolynomialAtom>,
            variables: Int,
            arithmetic: PolynomialArithmetic,
            control: ExecutionControl,
            windows: Map<Int, EnvelopeInterval> = emptyMap(),
        ): PolynomialLpRelaxation? {
            val builder = PolynomialEnvelopeBuilder(atoms, variables, arithmetic, windows)
            try {
                if (!builder.prepare()) return null
                arithmetic.matrixCells(builder.rows.size.toLong(), builder.bounds.size.toLong() + builder.rows.size + 1)
            } catch (_: PolynomialResourceLimit) {
                return null
            }
            val lp = arithmetic.execution.handoff {
                CertifiedEnvelopeLp.prepare(builder.bounds, builder.rows, control)
            } ?: return null
            return PolynomialLpRelaxation(lp, builder.squares, builder.products, builder.bounds, arithmetic)
        }
    }
}

internal class PolynomialLpTightening(val changed: Boolean, val infeasible: Boolean = false)

private class PolynomialEnvelopeProduct(val left: Int, val right: Int, val result: Int, val variables: List<Int>)

private class PolynomialEnvelopeBuilder(
    private val atoms: List<PolynomialAtom>,
    private val variables: Int,
    private val arithmetic: PolynomialArithmetic,
    private val windows: Map<Int, EnvelopeInterval>,
) {
    val bounds = ArrayList<EnvelopeInterval>()
    val rows = ArrayList<ExactRationalInequality>()
    val squares = ArrayList<Pair<Int, Int>>()
    val products = ArrayList<PolynomialEnvelopeProduct>()
    private val columns = HashMap<Monomial, Int>()

    fun prepare(): Boolean {
        val nonlinear = atoms.any { atom ->
            arithmetic.step()
            atom.polynomial.terms.any { term ->
                arithmetic.step()
                term.monomial.variableCount > 1 || term.monomial.variables.any { term.monomial.exponent(it) > 1 }
            }
        }
        if (!nonlinear) return false
        arithmetic.matrixCells(1, variables.toLong())
        repeat(variables) { variable ->
            arithmetic.step()
            bounds += EnvelopeInterval()
            columns[Monomial.of(mapOf(variable to 1))] = variable
        }
        for (atom in atoms) collectBound(atom)
        for ((variable, window) in windows) {
            arithmetic.step()
            val previous = bounds[variable]
            bounds[variable] = EnvelopeInterval(
                window.lower?.let { lo -> previous.lower?.let { if (it > lo) it else lo } ?: lo } ?: previous.lower,
                window.upper?.let { hi -> previous.upper?.let { if (it < hi) it else hi } ?: hi } ?: previous.upper,
            )
        }
        for (atom in atoms) {
            arithmetic.step()
            if (atom.relation == PolynomialRelation.NE) continue
            arithmetic.matrixCells(rows.size.toLong() + 2, bounds.size.toLong() + rows.size + 3)
            var constant = BigFraction.ZERO
            val terms = ArrayList<Pair<Int, BigFraction>>()
            for ((monomial, coefficient) in atom.polynomial.terms) {
                arithmetic.step()
                if (monomial == Monomial.ONE) constant = coefficient
                else terms += column(monomial) to coefficient
            }
            when (atom.relation) {
                PolynomialRelation.LE, PolynomialRelation.LT ->
                    rows += envelopeRow(terms, constant.negated(), atom.relation == PolynomialRelation.LT)
                PolynomialRelation.GE, PolynomialRelation.GT -> rows += envelopeRow(
                    terms.map { it.first to it.second.negated() }, constant, atom.relation == PolynomialRelation.GT)
                PolynomialRelation.EQ -> {
                    rows += envelopeRow(terms, constant.negated())
                    rows += envelopeRow(terms.map { it.first to it.second.negated() }, constant)
                }
                PolynomialRelation.NE -> Unit
            }
        }
        return columns.size > variables
    }

    private fun collectBound(atom: PolynomialAtom) {
        arithmetic.step()
        val terms = atom.polynomial.terms
        val term = terms.singleOrNull { it.monomial != Monomial.ONE } ?: return
        val variable = term.monomial.variables.singleOrNull() ?: return
        if (term.monomial.exponent(variable) != 1 || atom.relation == PolynomialRelation.NE) return
        val constant = atom.polynomial.coefficient(Monomial.ONE)
        arithmetic.step(constant, term.coefficient)
        val endpoint = constant.negated() * term.coefficient.reciprocal()
        val lower = atom.relation == PolynomialRelation.EQ ||
            if (term.coefficient.signum() > 0) atom.relation in listOf(PolynomialRelation.GE, PolynomialRelation.GT)
            else atom.relation in listOf(PolynomialRelation.LE, PolynomialRelation.LT)
        val upper = atom.relation == PolynomialRelation.EQ || !lower
        val previous = bounds[variable]
        bounds[variable] = EnvelopeInterval(
            if (lower) previous.lower?.let { if (it > endpoint) it else endpoint } ?: endpoint else previous.lower,
            if (upper) previous.upper?.let { if (it < endpoint) it else endpoint } ?: endpoint else previous.upper,
        )
    }

    private fun column(monomial: Monomial): Int {
        arithmetic.step()
        columns[monomial]?.let { return it }
        val powers = monomial.variables.associateWith {
            arithmetic.step()
            monomial.exponent(it)
        }
        val half = powers.mapValues { it.value / 2 }.filterValues { it > 0 }.ifEmpty {
            powers.entries.take(powers.size / 2).associate { it.toPair() }
        }
        val leftMonomial = Monomial.of(half)
        val rightMonomial = checkNotNull(monomial.quotient(leftMonomial))
        val left = column(leftMonomial)
        val right = column(rightMonomial)
        val result = bounds.size
        arithmetic.step()
        arithmetic.matrixCells(rows.size.toLong() + 4, bounds.size.toLong() + rows.size + 6)
        if (left == right) {
            bounds += bounds[left].square()
            rows += squareEnvelopes(left, result, bounds[left])
            squares += left to result
        } else {
            bounds += bounds[left].product(bounds[right])
            rows += productEnvelopes(left, right, result, bounds[left], bounds[right])
        }
        products += PolynomialEnvelopeProduct(left, right, result, monomial.variables)
        columns[monomial] = result
        return result
    }
}
