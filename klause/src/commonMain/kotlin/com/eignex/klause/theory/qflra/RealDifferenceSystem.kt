package com.eignex.klause.theory.qflra

import com.eignex.klause.arithmetic.difference.DifferenceGraph
import com.eignex.klause.arithmetic.difference.Potentials
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.Term
import com.eignex.klause.ir.linearRows
import com.eignex.klause.lp.ExactRowForm
import com.eignex.klause.lp.asFraction
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.klause.theory.EncodedTheoryFactor
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.compareTo
import com.eignex.klause.util.toLong

internal class RealDifferenceSystem private constructor(
    private val graph: DifferenceGraph,
    private val guards: IntArray,
    private val scale: Long,
) {
    private var potentials: LongArray? = null

    fun check(bools: IntArray, stop: Cancellation): Result {
        val active = BooleanArray(guards.size) { index ->
            val guard = guards[index]
            guard == ALWAYS || bools[Lit.variable(guard)] == if (Lit.isPositive(guard)) 1 else 0
        }
        var cycle: IntArray? = null
        return when (val result = graph.potentials(active, stop::invoke, potentials) { cycle = it }) {
            Potentials.Abandoned -> Result.Interrupted
            Potentials.Infeasible -> {
                // ALWAYS edges are immutable source rows and declared bounds, not scoped LP assertions.
                val clause = cycle?.takeIf { edges -> edges.all { active[it] } }?.asSequence()
                    ?.map { guards[it] }?.filter { it != ALWAYS }?.map(Lit::negate)
                    ?.distinct()?.sorted()?.toList()?.toIntArray()
                if (stop()) Result.Interrupted else Result.Infeasible(clause)
            }
            is Potentials.Found -> {
                if (stop()) return Result.Interrupted
                val zero = result.values.last()
                val normalized = LongArray(graph.numVars) { result.values[it] - zero }
                potentials = normalized.takeIf { values ->
                    values.all { it in -Long.MAX_VALUE / 8L..Long.MAX_VALUE / 8L }
                }
                Result.Feasible { List(graph.numVars - 1) { column ->
                    BigFraction.of(bigIntOf(result.values[column] - zero), bigIntOf(scale))
                } }
            }
        }
    }

    sealed interface Result {
        class Feasible(point: () -> List<BigFraction>) : Result {
            val point by lazy(point)
        }
        class Infeasible(val clause: IntArray?) : Result
        data object Interrupted : Result
    }

    companion object {
        private const val ALWAYS = -1

        fun prepare(
            model: Problem,
            forms: List<List<ExactRowForm>>,
            stop: Cancellation = Cancellation.Never,
        ): RealDifferenceSystem? {
            if (stop()) return null
            if (model.numIntVars != 0 || model.numRealVars == 0 || model.numRealVars > 10000) return null
            val zero = model.numRealVars
            val edges = ArrayList<Edge>()
            for (real in 0 until model.numRealVars) {
                if (stop()) return null
                if (model.realLower[real].isFinite() &&
                    !append(exactBound(real, model.realLower[real], false), ALWAYS, zero, edges, stop)
                ) return null
                if (model.realUpper[real].isFinite() &&
                    !append(exactBound(real, model.realUpper[real], true), ALWAYS, zero, edges, stop)
                ) return null
            }
            for ((factorIndex, factor) in model.factors.withIndex()) {
                val source = (factor as? EncodedTheoryFactor)?.takeIf {
                    it.linearForm === it.source.linearForm
                }?.source ?: factor
                if (source is Clause || source is Cardinality) continue
                if (factor.linearForm is LinearForm.Disjunction) return null
                for ((rowIndex, row) in factor.linearRows.withIndex()) {
                    if (stop()) return null
                    if ((0 until row.size).any { Term.isBool(row.ref(it)) }) return null
                    val truths = if (row.activator == LinearRow.ALWAYS) listOf(true) else listOf(true, false)
                    for (truth in truths) {
                        val comparison = forms[factorIndex][rowIndex].comparison(truth) { false }
                        if (comparison.op == LinearOp.NE) return null
                        val rows = ArrayList<ExactRationalInequality>(2)
                        comparison.rowsInto(rows)
                        val guard = if (row.activator == LinearRow.ALWAYS) ALWAYS else Lit.make(row.activator, truth)
                        for (inequality in rows) if (!append(inequality, guard, zero, edges, stop)) return null
                    }
                }
            }
            if (edges.isEmpty() || edges.size > 100000) return null
            var denominator = 1L
            for (edge in edges) {
                stop.charge(1)
                if (stop()) return null
                if (edge.denominator == 1L) continue
                var left = denominator
                var right = edge.denominator
                while (right != 0L) {
                    stop.charge(1)
                    if (stop()) return null
                    val remainder = left % right
                    left = right
                    right = remainder
                }
                val multiple = edge.denominator / left
                if (denominator > Long.MAX_VALUE / multiple) return null
                denominator *= multiple
            }
            // Clearing denominators makes cycle sums integral. With n vertices, multiplying by n+1
            // preserves positive simple cycles after subtracting one per strict edge.
            val cycleScale = zero.toLong() + 2L
            if (denominator > Long.MAX_VALUE / cycleScale) return null
            val scale = cycleScale * denominator
            val room = Long.MAX_VALUE / (4L * (zero + 2L) * (edges.size + 1L))
            val graph = DifferenceGraph(zero + 1)
            for (edge in edges) {
                stop.charge(1)
                if (stop()) return null
                val multiplier = cycleScale * (denominator / edge.denominator)
                if (edge.bound < -room / multiplier || edge.bound > room / multiplier) return null
                graph.addEdge(edge.source, edge.target, edge.bound * multiplier - if (edge.strict) 1L else 0L)
            }
            if (stop()) return null
            return RealDifferenceSystem(graph, edges.map { it.guard }.toIntArray(), scale)
        }

        private fun exactBound(column: Int, value: Double, upper: Boolean): ExactRationalInequality =
            ExactRationalInequality(
                intArrayOf(column),
                listOf(if (upper) BigFraction.ONE else BigFraction.ONE.negated()),
                if (upper) value.asFraction() else value.asFraction().negated(),
            )

        private fun append(
            row: ExactRationalInequality,
            guard: Int,
            zero: Int,
            edges: MutableList<Edge>,
            stop: Cancellation,
        ): Boolean {
            stop.charge(1)
            if (stop()) return false
            if (row.columns.size > 2) return false
            if (row.columns.isEmpty()) {
                edges += Edge(zero, zero, if (row.rhs.signum() < 0) -1L else 0L,
                    row.strict && row.rhs.isZero, guard)
                return true
            }
            val coefficient = row.coefficients[0]
            if (coefficient.isZero ||
                (row.columns.size == 2 && coefficient != row.coefficients[1].negated())
            ) return false
            val positive = coefficient.signum() > 0
            val magnitude = if (positive) coefficient else coefficient.negated()
            val bound = row.rhs * magnitude.reciprocal()
            if (bound.den > bigIntOf(Long.MAX_VALUE) || bound.num < bigIntOf(Long.MIN_VALUE) ||
                bound.num > bigIntOf(Long.MAX_VALUE)
            ) return false
            val first = row.columns[0]
            val second = row.columns.getOrNull(1) ?: zero
            edges += Edge(if (positive) second else first, if (positive) first else second,
                bound.num.toLong(), row.strict, guard, bound.den.toLong())
            return true
        }
    }
}

private class Edge(
    val source: Int,
    val target: Int,
    val bound: Long,
    val strict: Boolean,
    val guard: Int,
    val denominator: Long = 1L,
)
