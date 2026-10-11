package com.eignex.klause.backtrack

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.Cancellation
import kotlin.random.Random

internal class ClausePrimalWalk(
    sample: Sample,
    private val clauses: List<Clause>,
    private val costs: List<Int>,
    private val pins: Assumptions,
    seed: Long?,
    private val nodeBudget: NodeBudget?,
    cancellation: Cancellation = Cancellation.Never,
) {
    private val values = sample.bools
    private val occurrences = Array(values.size) { mutableSetOf<Int>() }
    private val counts = IntArray(clauses.size)
    private val weights = LongArray(clauses.size) { 1L }
    private val unsatisfied = IntArray(clauses.size)
    private val positions = IntArray(clauses.size) { -1 }
    private var unsatisfiedSize = 0
    private val random = Random(seed ?: 0L)
    private var saved: BooleanArray? = null
    private var fixed = BooleanArray(values.size)
    private var visits = 0L
    private var trialMoves = 0
    private var contradiction = false

    // Scoring and flips visit clause literals directly rather than invoking the generic local-search evaluator.
    val work: Long get() = visits / PROPAGATION_WORK_PER_NODE

    init {
        visits += 2L * values.size + 4L * clauses.size
        for ((index, clause) in clauses.withIndex()) {
            for (literal in clause.literals) {
                visits++
                occurrences[Lit.variable(literal)].add(index)
                if (Lit.evaluate(literal, values[Lit.variable(literal)])) counts[index]++
            }
            require(counts[index] > 0)
        }
        val costly = BooleanArray(values.size)
        for (variable in costs) {
            visits++
            costly[variable] = true
        }
        for (variable in values.indices) {
            visits++
            if (cancellation()) break
            if (values[variable] && !costly[variable] &&
                pins.boolValueOrNull(variable) == null && score(variable) == 0L
            ) {
                flip(variable)
            }
        }
    }

    fun begin(variable: Int, cancellation: Cancellation) {
        check(saved == null)
        saved = values.copyOf()
        visits += values.size
        trialMoves = 0
        contradiction = false
        flip(variable)
        deriveUnits(cancellation)
    }

    fun advance(cancellation: Cancellation, workLimit: Long): Boolean? {
        val startWork = work
        while (!contradiction && unsatisfiedSize > 0 && trialMoves < values.size) {
            if (cancellation() || (workLimit >= 0L && work - startWork >= workLimit)) return null
            if (nodeBudget?.movesLeft() == 0L) return null
            val clause = clauses[unsatisfied[random.nextInt(unsatisfiedSize)]]
            visits += clause.literals.size
            val candidates = clause.literals.map { Lit.variable(it) }.distinct().filter { !fixed[it] }
            if (candidates.isEmpty()) {
                contradiction = true
                break
            }
            val selected = if (random.nextInt(5) == 0) {
                candidates[random.nextInt(candidates.size)]
            } else {
                var scores = candidates.map { score(it) }
                if (scores.max() <= 0L) {
                    for (index in 0 until unsatisfiedSize) {
                        visits++
                        val id = unsatisfied[index]
                        weights[id] = (weights[id] + 1L).coerceAtMost(1_000_000_000L)
                    }
                    scores = candidates.map { score(it) }
                }
                val best = scores.max()
                val choices = candidates.indices.filter { scores[it] == best }
                candidates[choices[random.nextInt(choices.size)]]
            }
            flip(selected)
            visits++
            trialMoves++
            nodeBudget?.spendMoves(1L)
        }
        val feasible = !contradiction && unsatisfiedSize == 0
        if (!feasible) {
            val previous = checkNotNull(saved)
            for (variable in values.indices) {
                visits++
                if (values[variable] != previous[variable]) flip(variable)
            }
            check(unsatisfiedSize == 0)
        }
        saved = null
        return feasible
    }

    fun sample(): Sample {
        check(saved == null && unsatisfiedSize == 0)
        visits += values.size
        return Sample(values.copyOf(), longArrayOf())
    }

    private fun score(variable: Int): Long {
        var score = 0L
        for (index in occurrences[variable]) {
            val clause = clauses[index]
            visits += clause.literals.size
            val before = counts[index]
            var after = before
            for (literal in clause.literals) {
                if (Lit.variable(literal) == variable) {
                    after += if (Lit.evaluate(literal, values[variable])) -1 else 1
                }
            }
            if (before == 0 && after > 0) score += weights[index]
            if (before > 0 && after == 0) score -= weights[index]
        }
        return score
    }

    private fun flip(variable: Int) {
        values[variable] = !values[variable]
        for (index in occurrences[variable]) {
            val before = counts[index]
            val clause = clauses[index]
            visits += clause.literals.size
            for (literal in clause.literals) {
                if (Lit.variable(literal) == variable) {
                    counts[index] += if (Lit.evaluate(literal, values[variable])) 1 else -1
                }
            }
            if (before > 0 && counts[index] == 0) {
                positions[index] = unsatisfiedSize
                unsatisfied[unsatisfiedSize++] = index
            } else if (before == 0 && counts[index] > 0) {
                val position = positions[index]
                val last = unsatisfied[--unsatisfiedSize]
                unsatisfied[position] = last
                positions[last] = position
                positions[index] = -1
            }
        }
    }

    private fun deriveUnits(cancellation: Cancellation) {
        visits += 2L * values.size + 2L * clauses.size + costs.size + pins.bools.size
        val assignments = IntArray(values.size) { -1 }
        val remaining = IntArray(clauses.size) { clauses[it].literals.size }
        val satisfied = BooleanArray(clauses.size)
        val queue = ArrayDeque<Pair<Int, Boolean>>()
        for (variable in costs) queue.addLast(variable to values[variable])
        for ((variable, value) in pins.bools) queue.addLast(variable to value)
        fixed = BooleanArray(values.size)
        while (queue.isNotEmpty()) {
            if (cancellation()) {
                contradiction = true
                return
            }
            val (variable, value) = queue.removeFirst()
            if (assignments[variable] >= 0) {
                if ((assignments[variable] == 1) != value) contradiction = true
                if (contradiction) return
                continue
            }
            assignments[variable] = if (value) 1 else 0
            fixed[variable] = true
            if (values[variable] != value) flip(variable)
            for (index in occurrences[variable]) {
                val clause = clauses[index]
                visits += 3L * clause.literals.size
                remaining[index] -= clause.literals.count { Lit.variable(it) == variable }
                if (clause.literals.any { Lit.variable(it) == variable && Lit.evaluate(it, value) }) {
                    satisfied[index] = true
                }
                if (satisfied[index]) continue
                if (remaining[index] == 0) {
                    contradiction = true
                    return
                }
                if (remaining[index] == 1) {
                    val literal = clause.literals.first { assignments[Lit.variable(it)] < 0 }
                    queue.addLast(Lit.variable(literal) to Lit.isPositive(literal))
                }
            }
        }
    }
}
