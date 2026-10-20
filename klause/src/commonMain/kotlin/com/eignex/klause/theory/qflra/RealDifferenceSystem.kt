package com.eignex.klause.theory.qflra

import com.eignex.klause.arithmetic.difference.DifferenceEdge
import com.eignex.klause.arithmetic.difference.DifferenceFragment
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
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.theory.EncodedTheoryFactor
import com.eignex.klause.theory.difference.DifferenceGuardPropagation
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.compareTo
import com.eignex.klause.util.toLong

internal class RealDifferenceSystem private constructor(
    private val graph: DifferenceGraph,
    private val guards: IntArray,
    private val scale: Long,
    private val rowOrigins: IntArray?,
    private val guardEdges: List<DifferenceEdge>?,
) {
    private var potentials: LongArray? = null
    private var latestPoint: LongArray? = null
    private var booleanVariables = 0
    private var guardPreparationAttempted = false
    private var guardFragment: DifferenceFragment? = null
    private var guardPropagation: DifferenceGuardPropagation? = null

    fun check(bools: IntArray, stop: Cancellation, onWork: ((Long) -> Unit)? = null): Result {
        latestPoint = null
        booleanVariables = bools.size
        onWork?.invoke(guards.size.toLong() + graph.numVars)
        val active = BooleanArray(guards.size) { index ->
            val guard = guards[index]
            guard == ALWAYS || bools[Lit.variable(guard)] == if (Lit.isPositive(guard)) 1 else 0
        }
        var cycle: IntArray? = null
        val result = graph.potentials(active, stop::invoke, potentials,
            onNegativeCycle = { cycle = it }, onWork = onWork)
        return when (result) {
            Potentials.Abandoned -> Result.Interrupted
            Potentials.Infeasible -> {
                // ALWAYS edges are immutable source rows and declared bounds, not scoped LP assertions.
                val clause = cycle?.takeIf { edges -> edges.all { active[it] } }?.asSequence()
                    ?.map { guards[it] }?.filter { it != ALWAYS }?.map(Lit::negate)
                    ?.distinct()?.sorted()?.toList()?.toIntArray()
                val rows = rowOrigins?.let { origins ->
                    val size = cycle?.size ?: 0
                    onWork?.invoke(size.toLong() * (Int.SIZE_BITS - size.countLeadingZeroBits() + 4L))
                    cycle?.map { origins[it] }?.distinct()?.sorted()?.toIntArray()
                }
                if (stop()) Result.Interrupted else Result.Infeasible(clause, rows)
            }
            is Potentials.Found -> {
                if (stop()) return Result.Interrupted
                val zero = result.values.last()
                val normalized = LongArray(graph.numVars) { result.values[it] - zero }
                potentials = normalized.takeIf { values ->
                    values.all { it in -Long.MAX_VALUE / 8L..Long.MAX_VALUE / 8L }
                }
                latestPoint = normalized
                Result.Feasible { List(graph.numVars - 1) { column ->
                    BigFraction.of(bigIntOf(result.values[column] - zero), bigIntOf(scale))
                } }
            }
        }
    }

    fun implyGuards(context: SearchContext, stop: Cancellation, onImplied: (Int) -> Unit): ComponentResult {
        val edges = guardEdges ?: return ComponentResult.Consistent
        val point = latestPoint ?: return ComponentResult.Consistent
        if (stop()) return ComponentResult.Indeterminate
        if (!guardPreparationAttempted) {
            stop.charge(edges.size * 8L + graph.numVars * 32L)
            if (stop()) return ComponentResult.Indeterminate
            val fragment = DifferenceFragment(edges)
            val propagation = DifferenceGuardPropagation.create(fragment, booleanVariables, stop)
            if (stop()) return ComponentResult.Indeterminate
            guardFragment = fragment
            guardPropagation = propagation
            guardPreparationAttempted = true
        }
        val propagation = guardPropagation ?: return ComponentResult.Consistent
        val fragment = checkNotNull(guardFragment)
        stop.charge(fragment.numNodes.toLong())
        if (stop()) return ComponentResult.Indeterminate
        val values = LongArray(fragment.numNodes) { node ->
            point[if (node == fragment.zeroNode) graph.numVars - 1 else fragment.nodes[node]]
        }
        return propagation.propagate(fragment, values, context, stop, onImplied)
    }

    fun retractGuards() {
        latestPoint = null
        guardPropagation?.retract()
    }

    sealed interface Result {
        class Feasible(point: () -> List<BigFraction>) : Result {
            val point by lazy(point)
        }
        class Infeasible(val clause: IntArray?, val rowSupport: IntArray? = null) : Result
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
            return build(zero, edges, stop)
        }

        fun prepareRows(
            realColumns: Int,
            rows: List<ExactRationalInequality>,
            stop: Cancellation,
        ): RealDifferenceSystem? {
            if (realColumns !in 1..10000 || rows.size > 100000 || stop()) return null
            val edges = ArrayList<Edge>()
            for ((index, row) in rows.withIndex()) {
                if (row.columns.any { it !in 0 until realColumns } ||
                    !append(row, ALWAYS, realColumns, edges, stop, index)
                ) return null
            }
            return build(realColumns, edges, stop)
        }

        fun prepareNecessary(
            model: Problem,
            forms: List<List<ExactRowForm>>,
            stop: Cancellation,
            onStage: ((String) -> Unit)? = null,
        ): RealDifferenceRefuter? {
            if (model.numIntVars != 0 || model.numRealVars !in 1..10000 ||
                model.factors.size > 100000 || stop()
            ) return null
            onStage?.invoke("INPUT_N_${model.numRealVars}_F_${model.factors.size}")
            val zero = model.numRealVars
            val work = MixedPointArithmetic(stop)
            val edges = ArrayList<Edge>()
            var entries = 0L
            fun appendSelected(row: ExactRationalInequality, guard: Int): Boolean {
                entries += row.columns.size + 1L
                stop.charge(8L * (row.columns.size + 1L))
                if (entries > 65536L || edges.size >= 65536) {
                    onStage?.invoke("STORAGE_ENTRIES_${entries}_EDGES_${edges.size}")
                    return false
                }
                if (stop() ||
                    row.columns.size != row.coefficients.size || row.columns.any { it !in 0 until zero } ||
                    !work.admitted(row.rhs) || !row.coefficients.all(work::admitted)
                ) return false
                if (row.columns.size > 2) return true
                if (row.columns.isEmpty()) return append(row, guard, zero, edges, stop)
                val first = row.coefficients[0]
                if (first.isZero || (row.columns.size == 2 && first != row.coefficients[1].negated())) return true
                val magnitude = if (first.signum() > 0) first else first.negated()
                val bound = work.quotient(row.rhs, magnitude) ?: return !stop()
                val sign = if (first.signum() > 0) BigFraction.ONE else BigFraction.MINUS_ONE
                val normalized = ExactRationalInequality(row.columns,
                    if (row.columns.size == 1) listOf(sign) else listOf(sign, sign.negated()), bound, row.strict)
                append(normalized, guard, zero, edges, stop)
                return !stop()
            }
            for (real in 0 until zero) {
                stop.charge(2L)
                if (stop()) return null
                if (model.realLower[real].isFinite() &&
                    !appendSelected(exactBound(real, model.realLower[real], false), ALWAYS)
                ) return null
                if (model.realUpper[real].isFinite() &&
                    !appendSelected(exactBound(real, model.realUpper[real], true), ALWAYS)
                ) return null
            }
            val boundEdges = edges.size
            for ((factorIndex, factor) in model.factors.withIndex()) {
                stop.charge(1L)
                if (stop()) return null
                val source = (factor as? EncodedTheoryFactor)?.takeIf {
                    it.linearForm === it.source.linearForm
                }?.source ?: factor
                if (source is Clause || source is Cardinality || factor.linearForm is LinearForm.Disjunction) continue
                for ((rowIndex, row) in factor.linearRows.withIndex()) {
                    stop.charge(4L * row.size + 1L)
                    if (stop() || (row.activator != LinearRow.ALWAYS && row.activator !in 0 until model.numBoolVars)) {
                        return null
                    }
                    if ((0 until row.size).any { Term.isBool(row.ref(it)) }) continue
                    val truths = if (row.activator == LinearRow.ALWAYS) listOf(true) else listOf(true, false)
                    for (truth in truths) {
                        val comparison = forms[factorIndex][rowIndex].comparison(truth) { false }
                        if (comparison.terms.keys.any { it !in 0 until zero }) return null
                        if (comparison.terms.size > 2) continue
                        if (comparison.op == LinearOp.NE && comparison.terms.isNotEmpty()) continue
                        val rows = ArrayList<ExactRationalInequality>(2)
                        comparison.rowsInto(rows)
                        val guard = if (row.activator == LinearRow.ALWAYS) ALWAYS else Lit.make(row.activator, truth)
                        for (inequality in rows) if (!appendSelected(inequality, guard)) return null
                    }
                }
            }
            if (edges.size == boundEdges || stop()) return null
            onStage?.invoke("PREPARED_N_${zero}_E_${edges.size}_S_$entries")
            return build(zero, edges, stop)?.let(::RealDifferenceRefuter)
        }

        fun refutesSubset(
            realColumns: Int,
            rows: List<ExactRationalInequality>,
            stop: Cancellation,
            onSupport: ((IntArray) -> Unit)? = null,
        ): Boolean {
            if (realColumns !in 1..10000 || rows.size > 65536 || stop()) return false
            val work = MixedPointArithmetic(stop)
            val edges = ArrayList<Edge>()
            var entries = 0L
            for ((index, row) in rows.withIndex()) {
                entries += row.columns.size + 1L
                stop.charge(8L * (row.columns.size + 1L))
                if (entries > 65536L || stop() || row.columns.size != row.coefficients.size ||
                    row.columns.any { it !in 0 until realColumns } ||
                    row.columns.indices.any { it > 0 && row.columns[it - 1] >= row.columns[it] } ||
                    !work.admitted(row.rhs) || !row.coefficients.all(work::admitted)
                ) return false
                if (row.columns.isEmpty()) {
                    if (!append(row, ALWAYS, realColumns, edges, stop, index)) return false
                    continue
                }
                if (row.columns.size > 2) continue
                val first = row.coefficients[0]
                if (first.isZero || (row.columns.size == 2 && first != row.coefficients[1].negated())) continue
                val positive = first.signum() > 0
                val magnitude = if (positive) first else first.negated()
                val bound = work.quotient(row.rhs, magnitude) ?: continue
                val sign = if (positive) BigFraction.ONE else BigFraction.MINUS_ONE
                val normalized = ExactRationalInequality(row.columns,
                    if (row.columns.size == 1) listOf(sign) else listOf(sign, sign.negated()), bound, row.strict)
                if (!append(normalized, ALWAYS, realColumns, edges, stop, index) && stop()) return false
            }
            if (stop()) return false
            val graph = build(realColumns, edges, stop) ?: return false
            val result = graph.check(intArrayOf(), stop) { stop.charge(it) }
            if (stop() || result !is Result.Infeasible) return false
            result.rowSupport?.let { onSupport?.invoke(it) }
            return !stop()
        }

        private fun build(zero: Int, edges: List<Edge>, stop: Cancellation): RealDifferenceSystem? {
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
            val guarded = if (edges.size <= 65536 && edges.any { it.guard != ALWAYS }) {
                stop.charge(edges.size * 8L)
                if (stop()) return null
                ArrayList<DifferenceEdge>(edges.size)
            } else null
            for (edge in edges) {
                stop.charge(1)
                if (stop()) return null
                val multiplier = cycleScale * (denominator / edge.denominator)
                if (edge.bound < -room / multiplier || edge.bound > room / multiplier) return null
                val bound = edge.bound * multiplier - if (edge.strict) 1L else 0L
                graph.addEdge(edge.source, edge.target, bound)
                guarded?.add(DifferenceEdge(
                    if (edge.source == zero) DifferenceFragment.ZERO else edge.source,
                    if (edge.target == zero) DifferenceFragment.ZERO else edge.target,
                    bound, edge.guard,
                ))
            }
            if (stop()) return null
            stop.charge(2L * edges.size)
            if (stop()) return null
            val origins = if (edges.all { it.origin >= 0 }) edges.map { it.origin }.toIntArray() else null
            return RealDifferenceSystem(graph, edges.map { it.guard }.toIntArray(), scale, origins, guarded)
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
            origin: Int = -1,
        ): Boolean {
            stop.charge(1)
            if (stop()) return false
            if (row.columns.size > 2) return false
            if (row.columns.isEmpty()) {
                edges += Edge(zero, zero, if (row.rhs.signum() < 0) -1L else 0L,
                    row.strict && row.rhs.isZero, guard, origin = origin)
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
                bound.num.toLong(), row.strict, guard, bound.den.toLong(), origin)
            return true
        }
    }
}

internal class RealDifferenceRefuter(private val system: RealDifferenceSystem) {
    fun implyGuards(context: SearchContext, stop: Cancellation, onImplied: (Int) -> Unit): ComponentResult =
        system.implyGuards(context, stop, onImplied)

    fun retractGuards() = system.retractGuards()

    fun conflict(
        bools: IntArray,
        stop: Cancellation,
        onProposal: ((() -> List<BigFraction>) -> Unit)? = null,
    ): RealDifferenceSystem.Result.Infeasible? {
        val result = system.check(bools, stop) { stop.charge(it) }
        if (result is RealDifferenceSystem.Result.Feasible && !stop()) onProposal?.invoke { result.point }
        return (result as? RealDifferenceSystem.Result.Infeasible)?.takeUnless { stop() }
    }
}

private class Edge(
    val source: Int,
    val target: Int,
    val bound: Long,
    val strict: Boolean,
    val guard: Int,
    val denominator: Long = 1L,
    val origin: Int = -1,
)
