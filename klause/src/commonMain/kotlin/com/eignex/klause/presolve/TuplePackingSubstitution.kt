package com.eignex.klause.presolve

import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.factor.table.Table
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.IntArrayList

internal object TuplePackingSubstitution {
    internal class Limits(
        val work: Int = 1_048_576,
        val cells: Int = 262_144,
        val choices: Int = 65_536,
        val scope: Int = 4_096,
    )

    class Substitution(val problem: BakedProblem, val rebuild: SourceRebuilds, val choices: Int)

    private class Meter(val cancellation: Cancellation, var remaining: Int) {
        fun visit(): Boolean {
            if (remaining <= 0 || cancellation()) return false
            remaining--
            cancellation.charge(1)
            return !cancellation()
        }
    }

    private class Tuple(val values: LongArray) {
        override fun hashCode(): Int = values.contentHashCode()
        override fun equals(other: Any?): Boolean = other is Tuple && values.contentEquals(other.values)
    }

    private class Packing(val scope: IntArray, val tables: List<Pair<Int, Table>>, val distinctId: Int)

    fun substitute(
        problem: BakedProblem,
        objectiveIntVars: Set<Int>,
        cancellation: Cancellation = problem.cancellation,
        limits: Limits = Limits(),
    ): Substitution? {
        val meter = Meter(cancellation, limits.work)
        val occurrences = HashMap<Int, MutableList<Int>>()
        for ((id, factor) in problem.factors.withIndex()) {
            if (!meter.visit()) return null
            for (variable in factor.intVars) {
                if (!meter.visit()) return null
                occurrences.getOrPut(variable) { ArrayList() }.add(id)
            }
        }
        val dropped = HashSet<Int>()
        val additions = ArrayList<Factor>()
        val rebuilds = ArrayList<RebuildStep>()
        val sourceDomains = problem.rootIntDomainsInPlace
        val pinned = HashSet<Int>()
        var nextBool = problem.numBoolVars
        var retainedCells = 0
        for ((id, factor) in problem.factors.withIndex()) {
            if (!meter.visit()) break
            if (factor !is AllDifferent || id in dropped) continue
            val packing = partition(problem, id, factor, occurrences, objectiveIntVars, meter, limits) ?: continue
            val rows = ArrayList<List<Tuple>>()
            var cells = 0
            var choices = 0
            for ((_, table) in packing.tables) {
                val kept = LinkedHashSet<Tuple>()
                for (row in 0 until table.numTuples) {
                    val tuple = LongArray(table.arity)
                    val occupied = HashSet<Long>()
                    var feasible = true
                    for (column in table.xs.indices) {
                        if (!meter.visit()) return null
                        val value = table.tuples[row * table.arity + column]
                        tuple[column] = value
                        if (value !in sourceDomains[table.xs[column]] || !occupied.add(value)) feasible = false
                    }
                    if (feasible) kept.add(Tuple(tuple))
                }
                rows.add(kept.toList())
                choices += kept.size
                cells += kept.size * table.arity
            }
            if (cells > limits.cells - retainedCells || choices > limits.choices - (nextBool - problem.numBoolVars)) {
                continue
            }
            val capacity = LinkedHashMap<Long, IntArrayList>()
            val mandatory = HashMap<Long, Int>()
            val local = ArrayList<Factor>()
            val steps = ArrayList<RebuildStep>()
            var localBool = nextBool
            for ((group, entry) in packing.tables.withIndex()) {
                val table = entry.second
                val tuples = rows[group]
                val literals = IntArray(tuples.size) { Lit.make(localBool++, true) }
                local.add(if (literals.isEmpty()) Clause(intArrayOf()) else Cardinality.exactlyOne(literals))
                val counts = HashMap<Long, Int>()
                for ((row, tuple) in tuples.withIndex()) {
                    for (value in tuple.values) {
                        if (!meter.visit()) return null
                        capacity.getOrPut(value) { IntArrayList() }.add(literals[row])
                        counts[value] = (counts[value] ?: 0) + 1
                    }
                }
                for ((value, count) in counts) {
                    if (!meter.visit()) return null
                    if (count == tuples.size) mandatory[value] = (mandatory[value] ?: 0) + 1
                }
                for (column in table.xs.indices) {
                    if (!meter.visit()) return null
                    val values = LongArray(tuples.size)
                    for (row in tuples.indices) {
                        if (!meter.visit()) return null
                        values[row] = tuples[row].values[column]
                    }
                    steps.add(RebuildStep.TupleValue(table.xs[column], literals, values))
                }
            }
            // Every group chooses a placement. Two groups requiring one value exceed its capacity
            // even when no scalar coordinate has a singleton domain.
            if (mandatory.values.any { it > 1 }) local.add(Clause(intArrayOf()))
            val exactCover = capacity.size == packing.scope.size
            for (literals in capacity.values) {
                if (!meter.visit()) return null
                local.add(if (exactCover) Cardinality.exactlyOne(literals.toIntArray())
                else Cardinality.atMostOne(literals.toIntArray()))
            }
            if (cancellation()) return null
            dropped.add(packing.distinctId)
            packing.tables.forEach { dropped.add(it.first) }
            for (variable in packing.scope) pinned.add(variable)
            additions.addAll(local)
            rebuilds.addAll(steps)
            nextBool = localBool
            retainedCells += cells
        }
        if (dropped.isEmpty() || cancellation()) return null
        repeat(problem.numIntVars) { if (!meter.visit()) return null }
        val domains = sourceDomains.copyOf()
        for (variable in pinned) domains[variable] = IntDomain(domains[variable].min, domains[variable].min)
        val factors = problem.factors.filterIndexed { id, _ -> id !in dropped } + additions
        val rebuilt = PresolveShared.rebuildProblem(problem, factors, domains, numBoolVars = nextBool,
            cancellation = cancellation)
        return Substitution(rebuilt, SourceRebuilds(rebuilds, problem.numBoolVars), nextBool - problem.numBoolVars)
    }

    @Suppress("LongParameterList")
    private fun partition(
        problem: BakedProblem,
        id: Int,
        distinct: AllDifferent,
        occurrences: Map<Int, List<Int>>,
        objectiveIntVars: Set<Int>,
        meter: Meter,
        limits: Limits,
    ): Packing? {
        if (distinct.presents.isNotEmpty() || distinct.exceptSet.isNotEmpty() || distinct.vars.size > limits.scope) {
            return null
        }
        val scope = HashSet<Int>()
        val tables = LinkedHashMap<Int, Table>()
        for (variable in distinct.vars) {
            if (!meter.visit() || variable in objectiveIntVars || !scope.add(variable)) return null
            if (!problem.intBounds.hasLower(variable) || !problem.intBounds.hasUpper(variable)) return null
            val users = occurrences[variable] ?: return null
            if (users.size != 2 || id !in users) return null
            val tableId = users.first { it != id }
            val table = problem.factors[tableId] as? Table ?: return null
            if (table.hi != null || table.tuples.size > limits.cells) return null
            tables[tableId] = table
        }
        if (tables.size < 2) return null
        val covered = HashSet<Int>()
        var cells = 0L
        for (table in tables.values) {
            cells += table.tuples.size
            if (cells > limits.cells) return null
            for (variable in table.xs) {
                if (!meter.visit() || variable !in scope || !covered.add(variable)) return null
            }
        }
        if (covered != scope) return null
        return Packing(distinct.vars, tables.entries.map { it.key to it.value }, id)
    }
}
