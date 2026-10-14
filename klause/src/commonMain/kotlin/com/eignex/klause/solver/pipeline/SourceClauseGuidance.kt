package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.binarySearchInt

// Source definitions supply structure; only the remaining original clauses supply the preference.
internal class SourceClauseGuidance private constructor(
    private var clauses: List<Clause>,
    private var definitions: BooleanArray,
    private val gates: Map<Int, SourceConjunctionHints.Gate>,
    private var ambiguous: Set<Int>,
    private val qualified: MutableSet<Int>,
    private val parents: MutableMap<Int, MutableList<Int>>,
    private val occurrences: MutableMap<Int, MutableList<Int>>,
) : AutoCloseable {
    fun preferredBool(variable: Int, consultation: SourceConjunctionHints.Consultation): Boolean? {
        if (variable in ambiguous || (variable in gates && variable !in qualified)) return null
        if (!consultation.reserve(4)) return null
        val affected = HashSet<Int>()
        val touched = HashSet<Int>()
        val pending = arrayListOf(variable)
        while (pending.isNotEmpty()) {
            if (!consultation.visit() || touched.size + pending.size + affected.size > LIMIT) return null
            val current = pending.removeAt(pending.lastIndex)
            if (current in touched) continue
            if (!consultation.reserve()) return null
            touched += current
            for (id in occurrences[current].orEmpty()) {
                if (!consultation.visit() || touched.size + pending.size + affected.size >= LIMIT) return null
                if (id !in affected) {
                    if (!consultation.reserve()) return null
                    affected += id
                }
            }
            for (parent in parents[current].orEmpty()) {
                if (!consultation.visit() || touched.size + pending.size + affected.size >= LIMIT) return null
                if (parent in qualified && parent !in touched) {
                    if (!consultation.reserve()) return null
                    pending += parent
                }
            }
        }
        if (affected.isEmpty()) return null
        if (!consultation.reserve(4)) return null
        val left = Evaluation(variable, false, consultation)
        val right = Evaluation(variable, true, consultation)
        var leftBroken = 0
        var rightBroken = 0
        var leftSatisfied = 0
        var rightSatisfied = 0
        var supported = false
        for (id in affected) {
            if (!consultation.visit()) return null
            if (definitions[id]) continue
            val a = left.clause(clauses[id]) ?: return null
            val b = right.clause(clauses[id]) ?: return null
            supported = supported || (a.point && b.point)
            if (a.value == false) leftBroken++
            if (b.value == false) rightBroken++
            if (a.value == true) leftSatisfied++
            if (b.value == true) rightSatisfied++
        }
        if (!supported || consultation.declined) return null
        return when {
            leftBroken != rightBroken -> rightBroken < leftBroken
            leftSatisfied != rightSatisfied -> rightSatisfied > leftSatisfied
            else -> null
        }
    }

    override fun close() {
        clauses = emptyList()
        definitions = booleanArrayOf()
        ambiguous = emptySet()
        qualified.clear()
        parents.clear()
        occurrences.clear()
    }

    private class Truth(val value: Boolean?, val point: Boolean)

    private inner class Evaluation(
        private val selected: Int,
        private val value: Boolean,
        private val consultation: SourceConjunctionHints.Consultation,
    ) {
        private val values = HashMap<Int, Truth>()

        fun clause(clause: Clause): Truth? {
            var satisfied = false
            var unresolved = false
            var point = false
            for (literal in clause.literals) {
                if (!consultation.visit()) return null
                val truth = truth(Lit.variable(literal)) ?: return null
                point = point || truth.point
                if (truth.value == null) unresolved = true
                else if (truth.value == Lit.isPositive(literal)) satisfied = true
            }
            if (!consultation.reserve()) return null
            return Truth(if (satisfied) true else if (unresolved) null else false, point)
        }

        private fun truth(variable: Int): Truth? {
            values[variable]?.let { return it }
            if (!consultation.reserve(2)) return null
            val pending = arrayListOf(variable)
            while (pending.isNotEmpty()) {
                if (!consultation.visit() || values.size + pending.size >= LIMIT) return null
                val current = pending.last()
                if (current in values) {
                    pending.removeAt(pending.lastIndex)
                    continue
                }
                val known = consultation.known(current)
                if (known != null && current != selected) {
                    if (!consultation.reserve(2)) return null
                    values[current] = Truth(known, false)
                    continue
                }
                if (consultation.isPrimitive(current)) {
                    val point = consultation.primitive(current)
                    if (!consultation.reserve(2)) return null
                    values[current] = Truth(if (current == selected) value else point, point != null)
                    continue
                }
                val gate = gates[current].takeIf { current in qualified }
                if (gate == null) {
                    if (!consultation.reserve(2)) return null
                    values[current] = Truth(if (current == selected) value else null, false)
                    continue
                }
                var complete = true
                for (input in gate.inputs) {
                    if (!consultation.visit() || values.size + pending.size >= LIMIT) return null
                    val child = Lit.variable(input)
                    if (child !in values) {
                        if (!consultation.reserve()) return null
                        pending += child
                        complete = false
                    }
                }
                if (!complete) continue
                var falseInput = false
                var unknownInput = false
                var point = false
                for (input in gate.inputs) {
                    if (!consultation.visit()) return null
                    val truth = values.getValue(Lit.variable(input))
                    point = point || truth.point
                    if (truth.value == null) unknownInput = true
                    else if (truth.value != Lit.isPositive(input)) falseInput = true
                }
                val conjunction = if (falseInput) false else if (unknownInput) null else true
                val output = conjunction?.let { it == Lit.isPositive(gate.target) }
                if (!consultation.reserve(2)) return null
                values[current] = Truth(if (current == selected) value else output, point)
            }
            return values[variable]
        }
    }

    companion object {
        private const val LIMIT = SourceConjunctionHints.MAX_INDEX_ENTRIES

        fun create(
            clauses: List<Clause>,
            boolCount: Int,
            gates: Map<Int, SourceConjunctionHints.Gate>,
            ambiguous: Set<Int>,
            stop: Cancellation,
            remainingEntries: Int,
            remainingVisits: Long,
        ): SourceClauseGuidance? {
            val budget = Budget(remainingEntries, remainingVisits, stop)
            if (!budget.reserve(clauses.size + 6)) return null
            val definitions = BooleanArray(clauses.size)
            val parents = HashMap<Int, MutableList<Int>>()
            val degrees = HashMap<Int, Int>()
            val ready = ArrayList<Int>()
            for ((variable, gate) in gates) {
                if (!budget.reserve(3)) return null
                var degree = 0
                for (literal in gate.inputs) {
                    if (!budget.visit() || !budget.reserve(1)) return null
                    val input = Lit.variable(literal)
                    if (input !in parents && !budget.reserve(2)) return null
                    parents.getOrPut(input) { ArrayList() }.add(variable)
                    if (input in gates) degree++
                    if (input in ambiguous) degree = LIMIT
                }
                degrees[variable] = degree
                if (degree == 0) ready += variable
            }
            val qualified = HashSet<Int>()
            while (ready.isNotEmpty()) {
                if (!budget.visit()) return null
                val variable = ready.removeAt(ready.lastIndex)
                qualified += variable
                for (parent in parents[variable].orEmpty()) {
                    if (!budget.visit()) return null
                    val degree = degrees.getValue(parent) - 1
                    degrees[parent] = degree
                    if (degree == 0) ready += parent
                }
            }
            for ((id, clause) in clauses.withIndex()) {
                if (!budget.visit()) return null
                if (clause.tautological) continue
                val literals = clause.literals
                for (index in literals.indices) {
                    if (!budget.visit()) return null
                    val literal = literals[index]
                    val variable = Lit.variable(literal)
                    val gate = gates[variable].takeIf { variable in qualified } ?: continue
                    if (literals.size == 2 && literal == Lit.negate(gate.target)) {
                        val input = literals[1 - index]
                        if (!budget.visit(searchWork(gate.inputs.size))) return null
                        if (gate.inputs.binarySearchInt(input) >= 0) definitions[id] = true
                    } else if (literal == gate.target && literals.size == gate.inputs.size + 1) {
                        var complete = true
                        for (other in literals.indices) {
                            if (other == index) continue
                            if (!budget.visit(searchWork(gate.inputs.size))) return null
                            if (gate.inputs.binarySearchInt(Lit.negate(literals[other])) < 0) complete = false
                        }
                        if (complete) definitions[id] = true
                    }
                }
            }
            val occurrences = HashMap<Int, MutableList<Int>>()
            for ((id, clause) in clauses.withIndex()) {
                if (!budget.visit()) return null
                if (definitions[id] || clause.tautological) continue
                for (literal in clause.literals) {
                    if (!budget.visit()) return null
                    if (Lit.variable(literal) !in 0 until boolCount) return null
                }
                for (literal in clause.literals) {
                    if (!budget.reserve(1)) return null
                    val variable = Lit.variable(literal)
                    if (variable !in occurrences && !budget.reserve(2)) return null
                    occurrences.getOrPut(variable) { ArrayList() }.add(id)
                }
            }
            return SourceClauseGuidance(clauses, definitions, gates, ambiguous, qualified, parents, occurrences)
        }

        private fun searchWork(size: Int): Int {
            var width = size
            var levels = 1
            while (width > 0) { width = width ushr 1; levels++ }
            return levels + minOf(size, 16)
        }
    }

    private class Budget(private var entries: Int, private var visits: Long, private val stop: Cancellation) {
        fun visit(count: Int = 1): Boolean {
            if (count > visits) return false
            visits -= count
            stop.charge(count.toLong())
            return !stop()
        }

        fun reserve(count: Int): Boolean {
            if (count > entries || !visit(count)) return false
            entries -= count
            return true
        }
    }
}
