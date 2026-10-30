package com.eignex.klause.solver.objective

import com.eignex.klause.ir.Lit
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.localsearch.Move
import com.eignex.klause.solver.Assignment
import com.eignex.klause.util.ExactIntegerSum
import com.eignex.klause.util.IntHashSet

internal class PreparedBooleanObjectiveDelta(val evaluator: BooleanObjectiveDelta?)

internal class BooleanObjectiveDelta private constructor(
    private val terms: IntArray,
    private val coefficients: LongArray,
    private val folds: Array<DefinitionalSweep.SweepNode.BoolFold?>,
    private val offsets: IntArray,
    private val readers: IntArray,
    private val minimize: Boolean,
) {
    fun delta(assignment: Assignment, move: Move, poll: () -> Unit): Double {
        val sum = ExactIntegerSum()
        if (move is Move.BoolFlip) {
            val variable = move.varId
            for (i in offsets[variable] until offsets[variable + 1]) {
                poll()
                val term = readers[i]
                val before = value(term, poll, assignment::boolValue)
                val after = value(term, poll) {
                    assignment.boolValue(it) != (it == variable)
                }
                sum.addDelta(coefficients[term], before, after)
            }
        } else {
            val flipped = IntHashSet()
            fun collect(part: Move) {
                when (part) {
                    is Move.BoolFlip -> if (!flipped.remove(part.varId)) flipped.add(part.varId)
                    is Move.Compound -> part.parts.forEach(::collect)
                    else -> Unit
                }
            }
            collect(move)
            val visited = IntHashSet()
            for (variable in flipped.toIntArray()) {
                for (i in offsets[variable] until offsets[variable + 1]) {
                    poll()
                    val term = readers[i]
                    if (!visited.add(term)) continue
                    val before = value(term, poll, assignment::boolValue)
                    val after = value(term, poll) {
                        assignment.boolValue(it) != (it in flipped)
                    }
                    sum.addDelta(coefficients[term], before, after)
                }
            }
        }
        val delta = sum.toDouble()
        if (delta == 0.0) return 0.0
        return if (minimize) delta else -delta
    }

    private inline fun value(term: Int, poll: () -> Unit, boolOf: (Int) -> Boolean): Long {
        val fold = folds[term] ?: return if (boolOf(terms[term])) 1L else 0L
        for (literal in fold.ins) {
            poll()
            if (boolOf(Lit.variable(literal)) != Lit.isPositive(literal)) {
                if (fold.isAnd) return 0L
            } else if (!fold.isAnd) {
                return 1L
            }
        }
        return if (fold.isAnd) 1L else 0L
    }

    companion object {
        fun preparation(
            definitions: List<FunctionalObjective.Definition>,
            terms: IntArray,
            coefficients: LongArray,
            minimize: Boolean,
            numBooleans: Int,
        ): Sequence<PreparedBooleanObjectiveDelta?> = sequence {
            if (numBooleans !in 0..1_000_000 || terms.size > 250_000 || definitions.size > 250_000) {
                yield(PreparedBooleanObjectiveDelta(null))
                return@sequence
            }
            val outputs = arrayOfNulls<DefinitionalSweep.SweepNode.BoolFold>(numBooleans)
            yield(null)
            for ((index, definition) in definitions.withIndex()) {
                val fold = definition.origin as? DefinitionalSweep.SweepNode.BoolFold
                if (fold == null || fold.out !in outputs.indices || outputs[fold.out] != null) {
                    yield(PreparedBooleanObjectiveDelta(null))
                    return@sequence
                }
                outputs[fold.out] = fold
                if ((index + 1) % 256 == 0) yield(null)
            }
            val counts = IntArray(numBooleans)
            yield(null)
            val seen = IntArray(numBooleans)
            yield(null)
            val folds = arrayOfNulls<DefinitionalSweep.SweepNode.BoolFold>(terms.size)
            yield(null)
            var visits = 0
            var entries = 0
            for (term in terms.indices) {
                val variable = terms[term]
                if (variable !in outputs.indices) {
                    yield(PreparedBooleanObjectiveDelta(null))
                    return@sequence
                }
                val fold = outputs[variable]
                folds[term] = fold
                if (fold == null) {
                    counts[variable]++
                    entries++
                } else {
                    for (literal in fold.ins) {
                        val input = Lit.variable(literal)
                        if (input !in outputs.indices || outputs[input] != null) {
                            yield(PreparedBooleanObjectiveDelta(null))
                            return@sequence
                        }
                        if (seen[input] != term + 1) {
                            seen[input] = term + 1
                            counts[input]++
                            entries++
                        }
                        if (++visits % 256 == 0) yield(null)
                    }
                }
                if (entries > 2_000_000) {
                    yield(PreparedBooleanObjectiveDelta(null))
                    return@sequence
                }
                if ((term + 1) % 256 == 0) yield(null)
            }
            val offsets = IntArray(numBooleans + 1)
            yield(null)
            for (variable in counts.indices) {
                offsets[variable + 1] = offsets[variable] + counts[variable]
                counts[variable] = 0
                seen[variable] = 0
                if ((variable + 1) % 256 == 0) yield(null)
            }
            val readers = IntArray(entries)
            yield(null)
            for (term in terms.indices) {
                val fold = folds[term]
                if (fold == null) {
                    val variable = terms[term]
                    readers[offsets[variable] + counts[variable]++] = term
                } else {
                    for (literal in fold.ins) {
                        val variable = Lit.variable(literal)
                        if (seen[variable] != term + 1) {
                            seen[variable] = term + 1
                            readers[offsets[variable] + counts[variable]++] = term
                        }
                        if (++visits % 256 == 0) yield(null)
                    }
                }
                if ((term + 1) % 256 == 0) yield(null)
            }
            val evaluator = BooleanObjectiveDelta(
                terms, coefficients, folds, offsets, readers, minimize,
            )
            yield(PreparedBooleanObjectiveDelta(evaluator))
        }
    }
}
