package com.eignex.klause.theory.qflra

import com.eignex.klause.arithmetic.difference.DifferenceGraph
import com.eignex.klause.arithmetic.difference.Potentials
import com.eignex.klause.ir.IntegerConstants
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.Term
import com.eignex.klause.lp.ExactRowForm
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.compareTo
import com.eignex.klause.util.toLong

internal class IntegerDifferenceConflicts private constructor(
    private val graph: DifferenceGraph,
    private val guards: IntArray,
) {
    private var potentials: LongArray? = null

    fun check(bools: IntArray, stop: IntegerDifferenceAccounting): Result {
        stop.count("QUERIES")
        return checkWithin(bools, stop).also { result ->
            stop.count(when (result) {
                Result.NoConflict -> "NO_CONFLICT"
                Result.Unavailable -> "UNAVAILABLE"
                Result.Interrupted -> "INTERRUPTED"
                is Result.Conflict -> "CONFLICT_RESULTS"
            })
        }
    }

    private fun checkWithin(bools: IntArray, stop: IntegerDifferenceAccounting): Result {
        stop.charge(guards.size.toLong())
        if (stop()) return Result.Interrupted
        val active = BooleanArray(guards.size)
        for (index in guards.indices) {
            if (index % 8192 == 0 && stop()) return Result.Interrupted
            val guard = guards[index]
            active[index] = guard == ALWAYS ||
                bools[Lit.variable(guard)] == if (Lit.isPositive(guard)) 1 else 0
        }
        var cycle: IntArray? = null
        return when (val result = graph.potentials(active, stop::invoke, potentials,
            onNegativeCycle = { cycle = it }, onWork = stop::charge)) {
            Potentials.Abandoned -> if (stop()) Result.Interrupted else Result.Unavailable
            Potentials.Infeasible -> conflict(cycle, active, stop)
            is Potentials.Found -> {
                val next = normalized(result.values, stop)
                if (stop()) Result.Interrupted else {
                    potentials = next
                    Result.NoConflict
                }
            }
        }
    }

    private fun conflict(cycle: IntArray?, active: BooleanArray, stop: IntegerDifferenceAccounting): Result {
        if (cycle == null) return Result.Unavailable
        stop.charge(cycle.size.toLong())
        if (stop()) return Result.Interrupted
        val literals = HashSet<Int>()
        for (index in cycle.indices) {
            if (index % 8192 == 0 && stop()) return Result.Interrupted
            val edge = cycle[index]
            if (edge !in guards.indices || !active[edge]) return Result.Unavailable
            if (guards[edge] != ALWAYS) literals += Lit.negate(guards[edge])
        }
        // Sorting and copying are charged before their bounded library operations.
        val sortLevels = if (literals.isEmpty()) 0 else 32 - literals.size.countLeadingZeroBits()
        stop.charge(literals.size.toLong() * (sortLevels + 1L))
        if (stop()) return Result.Interrupted
        val clause = literals.sorted().toIntArray()
        if (stop()) return Result.Interrupted
        stop.count("NEGATIVE_CYCLES")
        return Result.Conflict(clause)
    }

    private fun normalized(values: LongArray, stop: Cancellation): LongArray? {
        stop.charge(values.size.toLong())
        if (stop()) return null
        val zero = values.last()
        val next = LongArray(values.size)
        for (index in values.indices) {
            if (index % 8192 == 0 && stop()) return null
            val value = values[index]
            if ((zero > 0 && value < Long.MIN_VALUE + zero) ||
                (zero < 0 && value > Long.MAX_VALUE + zero)
            ) return null
            val difference = value - zero
            if (difference !in -Long.MAX_VALUE / 8L..Long.MAX_VALUE / 8L) return null
            next[index] = difference
        }
        return next
    }

    sealed interface Result {
        class Conflict(val clause: IntArray) : Result
        data object NoConflict : Result
        data object Unavailable : Result
        data object Interrupted : Result
    }

    class Row(val row: LinearRow, val form: ExactRowForm, val implied: Boolean)

    companion object {
        private const val ALWAYS = LinearRow.ALWAYS

        fun prepare(
            model: Problem,
            rows: Sequence<Row>,
            stop: IntegerDifferenceAccounting,
        ): IntegerDifferenceConflicts? {
            stop.charge(1L)
            if (stop() || model.numRealVars != 0 || model.numIntVars !in 1..10000) return null
            return IntegerDifferencePreparation(model, stop).build(rows)?.let { (graph, guards) ->
                IntegerDifferenceConflicts(graph, guards)
            }
        }
    }
}

private class IntegerDifferencePreparation(private val model: Problem, private val stop: IntegerDifferenceAccounting) {
    private val edges = ArrayList<IntegerDifferenceEdge>()
    private val zero = model.numIntVars

    fun build(rows: Sequence<IntegerDifferenceConflicts.Row>): Pair<DifferenceGraph, IntArray>? {
        stop.count("PREPARATIONS")
        if (!bounds()) return null
        for (input in rows) {
            stop.charge(1L)
            stop.count("ROW_VISITS")
            if (stop()) return null
            if (!eligible(input)) {
                stop.count("SKIPPED_ROWS")
                if (stop()) return null
                continue
            }
            stop.count("ELIGIBLE_ROWS")
            if (!comparisons(input)) return null
        }
        if (edges.isEmpty() || edges.size > 100000 || stop()) return null
        return project()
    }

    private fun eligible(input: IntegerDifferenceConflicts.Row): Boolean {
        val row = input.row
        if (!input.implied || row.constants !is IntegerConstants || row.size > 2) return false
        for (term in 0 until row.size) {
            stop.charge(1L)
            stop.count("TERM_VISITS")
            if (stop() || !Term.isInt(row.ref(term))) return false
        }
        return true
    }

    private fun bounds(): Boolean {
        val bounds = model.intBounds
        for (column in 0 until zero) {
            stop.charge(3L)
            stop.count("BOUND_COLUMN_VISITS")
            if (stop()) return false
            if (bounds.hasLower(column)) {
                val bound = BigFraction.ofLong(bounds.lower(column)).negated()
                val row = ExactRationalInequality(intArrayOf(column), listOf(BigFraction.MINUS_ONE), bound)
                if (!append(row, ALWAYS)) return false
            }
            if (bounds.hasUpper(column)) {
                val bound = BigFraction.ofLong(bounds.upper(column))
                val row = ExactRationalInequality(intArrayOf(column), listOf(BigFraction.ONE), bound)
                if (!append(row, ALWAYS)) return false
            }
            if (stop()) return false
        }
        return true
    }

    private fun comparisons(input: IntegerDifferenceConflicts.Row): Boolean {
        val row = input.row
        val alternatives = if (row.activator == ALWAYS) 1 else 2
        repeat(alternatives) { alternative ->
            stop.charge(1L)
            if (stop()) return false
            val truth = alternative == 0
            val comparison = input.form.comparison(truth) { false }
            if (comparison.op == LinearOp.NE) {
                stop.count("SKIPPED_DISEQUALITIES")
            } else {
                val inequalities = ArrayList<ExactRationalInequality>(2)
                comparison.rowsInto(inequalities)
                val guard = if (row.activator == ALWAYS) ALWAYS else Lit.make(row.activator, truth)
                for (inequality in inequalities) if (!append(inequality, guard)) return false
            }
        }
        return edges.size <= 100000 && !stop()
    }

    private fun append(row: ExactRationalInequality, guard: Int): Boolean {
        stop.charge(1L)
        stop.count("EDGE_ATTEMPTS")
        if (stop()) return false
        val edge = edge(row, guard)
        if (stop()) return false
        if (edge == null) stop.count("SKIPPED_EDGES") else edges += edge
        return edges.size <= 100000
    }

    private fun edge(row: ExactRationalInequality, guard: Int): IntegerDifferenceEdge? {
        if (row.columns.isEmpty()) return IntegerDifferenceEdge(zero, zero,
            if (row.rhs.signum() < 0) -1L else 0L, row.strict && row.rhs.isZero, guard)
        val coefficient = row.coefficients[0]
        if (coefficient.isZero ||
            (row.columns.size == 2 && coefficient != row.coefficients[1].negated())
        ) return null
        val positive = coefficient.signum() > 0
        val magnitude = if (positive) coefficient else coefficient.negated()
        val bound = row.rhs * magnitude.reciprocal()
        if (bound.den != bigIntOf(1) || bound.num < bigIntOf(Long.MIN_VALUE) ||
            bound.num > bigIntOf(Long.MAX_VALUE)
        ) return null
        val first = row.columns[0]
        val second = row.columns.getOrNull(1) ?: zero
        return IntegerDifferenceEdge(if (positive) second else first, if (positive) first else second,
            bound.num.toLong(), row.strict, guard)
    }

    private fun project(): Pair<DifferenceGraph, IntArray>? {
        // This is the existing difference projection's conservative room, before omitting unsafe edges.
        val scale = zero.toLong() + 2L
        val room = Long.MAX_VALUE / (4L * (zero + 2L) * (edges.size + 1L))
        val graph = DifferenceGraph(zero + 1)
        val guards = ArrayList<Int>()
        for (edge in edges) {
            stop.charge(1L)
            if (stop()) return null
            if (edge.bound < -room / scale || edge.bound > room / scale) {
                stop.count("SKIPPED_UNSAFE_EDGES")
                continue
            }
            graph.addEdge(edge.source, edge.target, edge.bound * scale - if (edge.strict) 1L else 0L)
            guards += edge.guard
            stop.count("RETAINED_EDGES")
        }
        stop.charge(guards.size.toLong())
        if (stop() || guards.isEmpty()) return null
        val result = graph to guards.toIntArray()
        return result.takeUnless { stop() }
    }

    companion object {
        private const val ALWAYS = LinearRow.ALWAYS
    }
}

private class IntegerDifferenceEdge(
    val source: Int,
    val target: Int,
    val bound: Long,
    val strict: Boolean,
    val guard: Int,
)

internal class IntegerDifferenceAccounting(
    private val delegate: Cancellation,
    private val observe: (String, Long) -> Unit,
) : Cancellation {
    private val counts = HashMap<String, Long>()
    private val meter = delegate.workMeter()?.let { original -> WorkMeter { units ->
        forward(units) { original.charge(units) }
    } }

    init {
        count(if (meter == null) "METER_ABSENT_OPERATIONS" else "METER_PRESENT_OPERATIONS")
    }

    override fun isCancelled(): Boolean = delegate.isCancelled()
    override fun deadline() = delegate.deadline()
    override fun workMeter(): WorkMeter? = meter
    override fun charge(units: Long) = forward(units) { delegate.charge(units) }

    private inline fun forward(units: Long, action: () -> Unit) {
        count("FORWARDED_CHARGE_UNITS", units)
        action()
        count("COMPLETED_CHARGE_UNITS", units)
    }

    fun count(key: String, units: Long = 1L) {
        val prior = counts[key] ?: 0L
        counts[key] = prior + minOf(units, Long.MAX_VALUE - prior)
    }

    fun flush() {
        for ((key, units) in counts) observe("INTEGER_DIFFERENCE_$key", units)
    }
}
