package com.eignex.klause.solver

import com.eignex.klause.arithmetic.difference.DifferenceEdge
import com.eignex.klause.arithmetic.difference.DifferenceFragment
import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.factor.table.RealElement
import com.eignex.klause.ir.IntegerConstants
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.Term
import com.eignex.klause.lp.asFraction
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.difference.DifferenceIndexGuard
import com.eignex.klause.propagation.difference.DifferenceSystem
import com.eignex.klause.propagation.withAppendedFactor
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.fitsLong
import com.eignex.klause.util.toLongExact

internal fun BakedProblem.withRealDifferenceSystem(stop: Cancellation): BakedProblem {
    if (numRealVars !in 1..10000 || semantics.isApproximate) return this
    val system = RealDifferenceFragment(this, stop).prepare() ?: return this
    return withAppendedFactor(system)
}

private class RealDifferenceFragment(private val model: Problem, private val stop: Cancellation) {
    private class Edge(
        val source: Int,
        val target: Int,
        val bound: BigFraction,
        val strict: Boolean,
        val guard: Int,
        val selector: DifferenceIndexGuard?,
        val domain: Boolean,
    )

    private val edges = ArrayList<Edge>()
    private var visits = 0

    private fun visit(count: Int = 1): Boolean {
        stop.charge(count.toLong())
        visits += count
        return visits <= 65536 && !stop()
    }

    fun prepare(): DifferenceSystem? {
        if (stop()) return null
        for (factor in model.factors) {
            if (!visit()) return null
            when (factor) {
                is ExactLinear -> if (!append(factor)) return null
                is RealElement -> if (!append(factor)) return null
                is DifferenceSystem -> if (factor.privateVertices) return null
            }
        }
        if (edges.isEmpty()) return null
        for (real in 0 until model.numRealVars) {
            if (!visit()) return null
            val lower = model.realLower[real]
            val upper = model.realUpper[real]
            if (lower.isFinite()) add(real, DifferenceFragment.ZERO, lower.asFraction().negated(), domain = true)
            if (upper.isFinite()) add(DifferenceFragment.ZERO, real, upper.asFraction(), domain = true)
        }
        return build()
    }

    private fun add(
        source: Int,
        target: Int,
        bound: BigFraction,
        strict: Boolean = false,
        guard: Int = DifferenceEdge.ALWAYS,
        selector: DifferenceIndexGuard? = null,
        domain: Boolean = false,
    ) {
        if (bound.num.fitsLong() && bound.den.fitsLong()) {
            edges.add(Edge(source, target, bound, strict, guard, selector, domain))
        }
    }

    private fun append(row: ExactLinear): Boolean {
        if (!visit(row.size)) return false
        val constants = row.constants as? IntegerConstants ?: return true
        if (row.size !in 1..2 || (0 until row.size).any { !Term.isReal(row.ref(it)) }) return true
        val firstCoefficient = constants.coeff(0)
        if (firstCoefficient == 0L || firstCoefficient == Long.MIN_VALUE) return true
        if (row.size == 2 && constants.coeff(1) != -firstCoefficient) return true
        val first = Term.realVar(row.ref(0))
        val second = if (row.size == 2) Term.realVar(row.ref(1)) else DifferenceFragment.ZERO
        val source = if (firstCoefficient > 0L) second else first
        val target = if (firstCoefficient > 0L) first else second
        val magnitude = if (firstCoefficient > 0L) firstCoefficient else -firstCoefficient
        val bound = BigFraction.of(bigIntOf(constants.bound), bigIntOf(magnitude))
        val guard = if (row.activator == LinearRow.ALWAYS) DifferenceEdge.ALWAYS else Lit.make(row.activator, true)
        when (row.relation) {
            LinearOp.LE -> {
                add(source, target, bound, row.strict, guard)
                if (guard != DifferenceEdge.ALWAYS) {
                    add(target, source, bound.negated(), !row.strict, Lit.negate(guard))
                }
            }
            LinearOp.GE -> {
                add(target, source, bound.negated(), row.strict, guard)
                if (guard != DifferenceEdge.ALWAYS) add(source, target, bound, !row.strict, Lit.negate(guard))
            }
            LinearOp.EQ -> {
                add(source, target, bound, guard = guard)
                add(target, source, bound.negated(), guard = guard)
            }
            LinearOp.NE -> Unit
        }
        return true
    }

    private fun append(element: RealElement): Boolean {
        for (index in 0 until element.size) {
            if (!visit(3)) return false
            val selector = DifferenceIndexGuard(element.idx, element.indexOffset.toLong() + index)
            val source = if (element.arrIsVars) element.entries[index] else DifferenceFragment.ZERO
            val bound = if (element.arrIsVars) BigFraction.ZERO else element.constants[index].asFraction()
            add(source, element.result, bound, selector = selector)
            add(element.result, source, bound.negated(), selector = selector)
        }
        return true
    }

    private fun build(): DifferenceSystem? {
        if (edges.isEmpty() || !visit(edges.size)) return null
        var denominator = 1L
        for (edge in edges) {
            if (!visit()) return null
            val divisor = edge.bound.den.toLongExact()
            var left = denominator
            var right = divisor
            while (right != 0L) {
                if (!visit()) return null
                val remainder = left % right
                left = right
                right = remainder
            }
            val multiple = divisor / left
            if (denominator > Long.MAX_VALUE / multiple) return null
            denominator *= multiple
        }
        // Integral cycle sums need at least one unit of slack per strict edge in a simple cycle.
        val cycleScale = model.numRealVars.toLong() + 2L
        if (denominator > Long.MAX_VALUE / cycleScale) return null
        val room = Long.MAX_VALUE / (4L * cycleScale * (edges.size + 1L))
        val result = ArrayList<DifferenceEdge>(edges.size)
        val selectors = LinkedHashMap<Int, DifferenceIndexGuard>()
        for (edge in edges) {
            if (!visit()) return null
            val multiplier = cycleScale * (denominator / edge.bound.den.toLongExact())
            val numerator = edge.bound.num.toLongExact()
            if (numerator < -room / multiplier || numerator > room / multiplier) return null
            edge.selector?.let { selectors[result.size] = it }
            result.add(DifferenceEdge(edge.source, edge.target,
                numerator * multiplier - if (edge.strict) 1L else 0L, edge.guard, edge.domain))
        }
        if (stop()) return null
        return DifferenceSystem(result, privateVertices = true, indexGuards = selectors)
    }
}
