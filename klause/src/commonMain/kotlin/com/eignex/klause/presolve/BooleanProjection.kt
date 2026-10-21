package com.eignex.klause.presolve

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.IntArrayList

internal object BooleanProjection {
    private const val MAX_RESOLUTION_PAIRS = 4_096

    fun projectSource(problem: Problem, objectiveBoolVars: Set<Int>, cancellation: Cancellation): SourceDelta =
        Projection(problem, objectiveBoolVars, cancellation).run()

    private class Row(val origin: Int, val literals: IntArray, val factor: Clause, var live: Boolean = true)

    private class Resolution(val literals: IntArray?)

    private class Projection(
        private val problem: Problem,
        private val protectedBooleans: Set<Int>,
        private val cancellation: Cancellation,
    ) {
        private val rows = ArrayList<Row>()
        private val occurrences = HashMap<Int, IntArrayList>()
        private val dropped = IntArrayList()
        private val representatives = ArrayList<Clause>()
        private val rebuilds = ArrayList<RebuildStep.SatisfyClauses>()

        fun run(): SourceDelta {
            val eligible = prepare() ?: return SourceDelta()
            for (variable in eligible.indices) {
                if (!spend(1)) return finish()
                if (!eligible[variable]) continue
                val positive = gather(Lit.make(variable, true)) ?: return finish()
                val negative = gather(Lit.make(variable, false)) ?: return finish()
                val count = positive.size + negative.size
                if (count == 0) continue
                if (count == 1 && rows[(positive + negative).single()].literals.size == 1) continue
                if (positive.size.toLong() * negative.size > MAX_RESOLUTION_PAIRS) continue
                val resolvents = resolvents(variable, positive, negative, count) ?: return finish()
                if (resolvents.infeasible) return SourceDelta(infeasible = true)
                if (resolvents.unbounded) continue
                if (!commit(variable, positive + negative, resolvents.clauses)) return finish()
            }
            return finish()
        }

        private fun prepare(): BooleanArray? {
            if (!spend(4L * problem.numBoolVars + 4L * problem.numFactors)) return null
            val eligible = BooleanArray(problem.numBoolVars)
            val clean = BooleanArray(problem.numFactors)
            for (variable in eligible.indices) {
                if (!spend(1)) return null
                eligible[variable] = variable !in protectedBooleans
            }
            for ((index, factor) in problem.factors.withIndex()) {
                if (!spend(1)) return null
                var clause = factor is Clause
                if (factor is Clause) {
                    for (literal in factor.literals) {
                        if (!spend(1)) return null
                        if (Lit.variable(literal) >= eligible.size) clause = false
                    }
                }
                clean[index] = clause
                if (clause) continue
                // Invariant-only readers need protection even when absent from a deductive occurrence index.
                for (variable in factor.boolVars) {
                    if (!spend(1)) return null
                    if (variable in eligible.indices) eligible[variable] = false
                }
            }
            for ((index, factor) in problem.factors.withIndex()) {
                if (!spend(1)) return null
                if (!clean[index]) continue
                val clause = factor as Clause
                if (clause.tautological) {
                    dropped.add(index)
                    continue
                }
                val size = clause.literals.size
                val depth = Int.SIZE_BITS - size.countLeadingZeroBits()
                if (!spend(size.toLong() * (depth + 8) + 4)) return null
                val literals = clause.literals.sortedArray()
                if (cancellation()) return null
                val slot = rows.size
                rows.add(Row(index, literals, clause))
                for (literal in literals) {
                    if (!spend(2)) return null
                    occurrences.getOrPut(literal) { IntArrayList() }.add(slot)
                }
            }
            // Final scans and original-factor output storage remain available after a stopped transaction.
            if (!spend(4L * rows.size + 4L * problem.numFactors)) return null
            return eligible
        }

        private fun gather(literal: Int): List<Int>? {
            val slots = occurrences[literal] ?: return emptyList()
            if (!spend(slots.size.toLong() + 1)) return null
            val live = ArrayList<Int>()
            for (k in 0 until slots.size) {
                if (!spend(1)) return null
                val slot = slots[k]
                if (rows[slot].live) live.add(slot)
            }
            return live
        }

        private class Resolvents(
            val clauses: List<IntArray>,
            val infeasible: Boolean = false,
            val unbounded: Boolean = false,
        )

        private fun resolvents(variable: Int, positive: List<Int>, negative: List<Int>, count: Int): Resolvents? {
            val clauses = ArrayList<IntArray>()
            val seen = HashMap<Int, ArrayList<IntArray>>()
            for (p in positive) {
                for (n in negative) {
                    if (!spend(1)) return null
                    val result = resolve(rows[p].literals, rows[n].literals, variable) ?: return null
                    val literals = result.literals ?: continue
                    if (literals.isEmpty()) {
                        return if (cancellation()) null else Resolvents(emptyList(), infeasible = true)
                    }
                    val fresh = remember(literals, seen) ?: return null
                    if (fresh) clauses.add(literals)
                    // The fixed representative consumes one output clause too.
                    if (clauses.size >= count) return Resolvents(emptyList(), unbounded = true)
                }
            }
            return Resolvents(clauses)
        }

        private fun resolve(left: IntArray, right: IntArray, variable: Int): Resolution? {
            val capacity = left.size.toLong() + right.size
            if (capacity > Int.MAX_VALUE || !spend(2 * capacity + 1)) return null
            val out = IntArray(capacity.toInt())
            var a = 0
            var b = 0
            var size = 0
            while (a < left.size || b < right.size) {
                if (!spend(1)) return null
                val literal = if (b == right.size || (a < left.size && left[a] <= right[b])) left[a++] else right[b++]
                if (Lit.variable(literal) == variable) continue
                if (size > 0 && out[size - 1] == literal) continue
                if (size > 0 && out[size - 1] == Lit.negate(literal)) return Resolution(null)
                out[size++] = literal
            }
            if (cancellation()) return null
            val result = out.copyOf(size)
            return if (cancellation()) null else Resolution(result)
        }

        private fun remember(literals: IntArray, seen: HashMap<Int, ArrayList<IntArray>>): Boolean? {
            if (!spend(literals.size.toLong() + 1)) return null
            val hash = literals.contentHashCode()
            if (cancellation()) return null
            val bucket = seen.getOrPut(hash) { ArrayList() }
            for (other in bucket) {
                if (!spend(1)) return null
                if (other.size != literals.size) continue
                var same = true
                for (k in literals.indices) {
                    if (!spend(1)) return null
                    if (other[k] != literals[k]) {
                        same = false
                        break
                    }
                }
                if (same) return false
            }
            bucket.add(literals)
            return true
        }

        private fun commit(variable: Int, removed: List<Int>, resolvents: List<IntArray>): Boolean {
            if (!spend(removed.size.toLong() * 3 + 32)) return false
            val clauses = ArrayList<IntArray>(removed.size)
            for (slot in removed) {
                val literals = rows[slot].literals
                // Reserve immutable rebuild copies made by the source mapping and its composed lift.
                if (!spend(16L * literals.size + 8)) return false
                clauses.add(literals)
            }
            val added = ArrayList<Row>(resolvents.size)
            for (literals in resolvents) {
                if (!spend(16L * literals.size + 8)) return false
                val factor = Clause(literals)
                if (cancellation()) return false
                added.add(Row(-1, literals, factor))
            }
            val unit = Clause(intArrayOf(Lit.make(variable, false)))
            val rebuild = RebuildStep.SatisfyClauses(variable, clauses)
            if (cancellation()) return false
            // One fully prepared transaction; no containing stop can publish half an occurrence family.
            for (slot in removed) {
                val row = rows[slot]
                row.live = false
                if (row.origin >= 0) dropped.add(row.origin)
            }
            for (row in added) {
                val slot = rows.size
                rows.add(row)
                for (literal in row.literals) occurrences.getOrPut(literal) { IntArrayList() }.add(slot)
            }
            representatives.add(unit)
            rebuilds.add(rebuild)
            cancellation.recordSourceBooleanProjection()
            return true
        }

        private fun spend(units: Long): Boolean {
            if (cancellation()) return false
            cancellation.charge(units)
            return !cancellation()
        }

        private fun finish(): SourceDelta {
            val added = ArrayList<Factor>()
            for (row in rows) if (row.live && row.origin < 0) added.add(row.factor)
            added.addAll(representatives)
            return SourceDelta(
                droppedIndices = dropped.toIntArray(),
                addedFactors = added,
                rebuild = SourceRebuilds(rebuilds.asReversed()),
            )
        }
    }
}
