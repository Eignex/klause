package com.eignex.klause.formats.smtlib

import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Term
import com.eignex.klause.lowering.PolynomialSourceInterrupted
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.div
import com.eignex.klause.util.fitsLong
import com.eignex.klause.util.magnitudeBitLength
import com.eignex.klause.util.minus
import com.eignex.klause.util.plus
import com.eignex.klause.util.rem
import com.eignex.klause.util.times
import com.eignex.klause.util.toLongExact
import com.eignex.klause.util.unaryMinus

internal class SmtLibAffineRealTables(cancellation: Cancellation, entryLimit: Int = 65_536) {
    private class Scalar(val variable: Int, val value: Long)
    private class Case(val cube: List<Int>, val value: Long)
    private class Table(val variable: Int, val inputs: IntArray, val values: LongArray)
    private class Channel(val variable: Int, val negative: Long, val positive: Long)
    private class Limit : RuntimeException()

    private val work = Work(cancellation, entryLimit.toLong())
    private var enabled = true
    private val declared = HashSet<Int>()
    private val conjunctions = HashMap<Int, List<Int>>()
    private val scalars = HashMap<Int, MutableList<Scalar>>()
    private val cases = LinkedHashMap<Int, MutableList<Case>>()

    init {
        require(entryLimit >= 0)
    }

    fun declarations(variables: Collection<Int>) = capture {
        work.spend(variables.size.toLong() + 1L)
        declared.addAll(variables)
    }

    fun conjunction(result: Int, operands: List<Int>, trueLiteral: Int) = capture {
        work.spend()
        if (Lit.variable(result) in declared || result == trueLiteral ||
            (trueLiteral >= 0 && result == Lit.negate(trueLiteral)) || result in conjunctions
        ) return@capture
        val cube = cube(operands, operands.size, trueLiteral) ?: return@capture
        work.spend(cube.size.toLong() + 1L)
        conjunctions[result] = cube
    }

    fun equality(literal: Int, term: RealComb) = capture {
        work.spend()
        if (term.intCoeffs.isNotEmpty() || term.realCoeffs.size != 1) return@capture
        val (variable, coefficient) = term.realCoeffs.entries.single()
        if (coefficient.den != BIG_ONE || term.constant.den != BIG_ONE || coefficient.num == BIG_ZERO ||
            coefficient.num.magnitudeBitLength() > Long.SIZE_BITS ||
            term.constant.num.magnitudeBitLength() > Long.SIZE_BITS
        ) return@capture
        val value = if (coefficient.num == BIG_ONE || coefficient.num == -BIG_ONE) {
            work.spend(2)
            if (coefficient.num == BIG_ONE) -term.constant.num else term.constant.num
        } else {
            work.spend(64)
            val numerator = -term.constant.num
            if (numerator % coefficient.num != BIG_ZERO) return@capture
            numerator / coefficient.num
        }
        if (!value.fitsLong()) return@capture
        work.spend(2)
        scalars.getOrPut(literal) { ArrayList() }.add(Scalar(variable, value.toLongExact()))
    }

    fun implication(literals: IntArray, trueLiteral: Int) = capture {
        work.spend()
        val values = scalars[literals.last()] ?: return@capture
        val cube = cube(literals.asList(), literals.size - 1, trueLiteral) ?: return@capture
        for (value in values) {
            work.spend(3)
            cases.getOrPut(value.variable) { ArrayList() }.add(Case(cube, value.value))
        }
    }

    fun infer(sourceReals: Collection<Int> = emptyList()): List<Factor> {
        work.poll()
        val completed = ArrayList<Factor>()
        if (!enabled) return completed
        try {
            val singles = HashSet<Int>()
            val channels = HashMap<Int, Channel>()
            for ((variable, entries) in cases) {
                work.spend()
                if (!singleInput(entries)) continue
                work.spend()
                singles.add(variable)
                val table = table(variable, entries) ?: continue
                if (table.inputs.size == 1 && table.values[0] != table.values[1]) {
                    work.spend(4)
                    channels.getOrPut(table.inputs[0]) { Channel(variable, table.values[0], table.values[1]) }
                }
            }
            for ((variable, entries) in cases) {
                work.spend()
                if (variable in singles) continue
                val table = table(variable, entries, channels) ?: continue
                val weights = affineWeights(table) ?: continue
                val selected = ArrayList<Channel>(table.inputs.size)
                for (input in table.inputs) {
                    work.spend()
                    val channel = channels[input] ?: break
                    selected.add(channel)
                }
                if (selected.size != table.inputs.size) continue
                val link = link(table, weights, selected)
                work.spend()
                completed.add(link)
            }
            excludeScalarValues(sourceReals, completed)
        } catch (_: Limit) {
            enabled = false
        }
        work.poll()
        return completed
    }

    private fun excludeScalarValues(sourceReals: Collection<Int>, completed: MutableList<Factor>) {
        if (sourceReals.isEmpty()) return
        work.spend(sourceReals.size.toLong() + 1L)
        val sourceVariables = sourceReals.toHashSet()
        val byVariable = LinkedHashMap<Int, MutableMap<Int, Long>>()
        for ((literal, identities) in scalars) for (identity in identities) {
            work.spend()
            if (identity.variable !in sourceVariables) continue
            val values = byVariable.getOrPut(identity.variable) {
                work.spend()
                LinkedHashMap()
            }
            work.spend()
            if (literal in values) continue
            work.spend(2)
            values[literal] = identity.value
        }
        val emitted = HashSet<Pair<Int, Int>>()
        for (values in byVariable.values) {
            for ((first, firstValue) in values) for ((second, secondValue) in values) {
                work.spend()
                if (first >= second || firstValue == secondValue) continue
                work.spend(3)
                if (!emitted.add(first to second)) continue
                work.spend(3)
                completed.add(Clause(intArrayOf(Lit.negate(first), Lit.negate(second))))
            }
        }
    }

    private inline fun capture(action: () -> Unit) {
        work.poll()
        if (!enabled) return
        try {
            action()
        } catch (_: Limit) {
            enabled = false
        }
    }

    private fun cube(literals: List<Int>, count: Int, trueLiteral: Int): List<Int>? {
        val kept = LinkedHashSet<Int>()
        for (index in 0 until count) {
            work.spend()
            val literal = literals[index]
            if (trueLiteral >= 0 && literal == trueLiteral) continue
            if (trueLiteral >= 0 && literal == Lit.negate(trueLiteral)) return null
            val parts = if (Lit.variable(literal) in declared) listOf(literal)
                else conjunctions[literal] ?: return null
            for (part in parts) {
                work.spend()
                if (Lit.negate(part) in kept) return null
                kept.add(part)
                if (kept.size > MAX_INPUTS) return null
            }
        }
        work.spend(kept.size.toLong() + 1L)
        return kept.toList()
    }

    private fun singleInput(entries: List<Case>): Boolean {
        var input = -1
        for (entry in entries) for (literal in entry.cube) {
            work.spend()
            val variable = Lit.variable(literal)
            if (input >= 0 && input != variable) return false
            input = variable
        }
        return true
    }

    private fun table(variable: Int, entries: List<Case>, channels: Map<Int, Channel>? = null): Table? {
        val support = HashSet<Int>()
        for (entry in entries) for (literal in entry.cube) {
            work.spend()
            support.add(Lit.variable(literal))
            if (support.size > MAX_INPUTS) return null
        }
        if (channels != null) for (input in support) {
            work.spend()
            if (input !in channels) return null
        }
        work.spend(support.size.toLong() * (MAX_INPUTS + 2L) + 1L)
        val inputs = support.sorted().toIntArray()
        val bitOfInput = HashMap<Int, Int>()
        for (index in inputs.indices) bitOfInput[inputs[index]] = 1 shl index
        val size = 1 shl inputs.size
        work.spend(2L * size)
        val values = LongArray(size)
        val seen = BooleanArray(size)
        for (entry in entries) {
            var mask = 0
            var bits = 0
            for (literal in entry.cube) {
                work.spend()
                val bit = bitOfInput.getValue(Lit.variable(literal))
                mask = mask or bit
                if (Lit.isPositive(literal)) bits = bits or bit
            }
            val free = (size - 1) xor mask
            var subset = free
            while (true) {
                work.spend()
                val assignment = bits or subset
                if (seen[assignment] && values[assignment] != entry.value) return null
                seen[assignment] = true
                values[assignment] = entry.value
                if (subset == 0) break
                subset = (subset - 1) and free
            }
        }
        work.spend(size.toLong())
        return if (seen.all { it }) Table(variable, inputs, values) else null
    }

    private fun affineWeights(table: Table): List<BigInt>? {
        work.spend(table.inputs.size.toLong() + 1L)
        val base = bigIntOf(table.values[0])
        val weights = table.inputs.indices.map { work.subtract(bigIntOf(table.values[1 shl it]), base) }
        for (assignment in table.values.indices) {
            work.spend()
            var expected = base
            for (index in weights.indices) {
                work.spend()
                if ((assignment and (1 shl index)) != 0) expected = work.add(expected, weights[index])
            }
            if (expected != bigIntOf(table.values[assignment])) return null
        }
        return weights
    }

    private fun link(table: Table, weights: List<BigInt>, channels: List<Channel>): ExactLinear {
        work.spend(channels.size.toLong() + 1L)
        val deltas = channels.map { work.subtract(bigIntOf(it.positive), bigIntOf(it.negative)) }
        var scale = BIG_ONE
        for (delta in deltas) scale = work.multiply(scale, delta)
        val coefficients = ArrayList<BigInt>(channels.size + 1)
        coefficients.add(scale)
        var rhs = work.multiply(scale, bigIntOf(table.values[0]))
        for (index in channels.indices) {
            var multiplier = weights[index]
            for (other in deltas.indices) if (other != index) multiplier = work.multiply(multiplier, deltas[other])
            coefficients.add(work.negate(multiplier))
            rhs = work.subtract(rhs, work.multiply(multiplier, bigIntOf(channels[index].negative)))
        }
        work.spend(8L * coefficients.size + 1L)
        return ExactLinear.of(
            intArrayOf(Term.ofRealVar(table.variable)) + channels.map { Term.ofRealVar(it.variable) }.toIntArray(),
            coefficients.map { BigFraction.of(it, BIG_ONE) }, LinearOp.EQ, BigFraction.of(rhs, BIG_ONE),
        )
    }

    private class Work(val cancellation: Cancellation, private var remaining: Long) {
        fun poll() {
            if (cancellation()) throw PolynomialSourceInterrupted()
        }

        fun spend(units: Long = 1) {
            poll()
            if (units > remaining) throw Limit()
            remaining -= units
            cancellation.charge(units)
            poll()
        }

        fun add(first: BigInt, second: BigInt): BigInt {
            admit(first.magnitudeBitLength().coerceAtLeast(second.magnitudeBitLength()) + 1)
            spend(words(first) + words(second))
            return first + second
        }

        fun negate(value: BigInt): BigInt {
            spend(words(value))
            return -value
        }

        fun subtract(first: BigInt, second: BigInt): BigInt = add(first, negate(second))

        fun multiply(first: BigInt, second: BigInt): BigInt {
            admit(first.magnitudeBitLength() + second.magnitudeBitLength())
            spend(words(first) * words(second))
            return first * second
        }

        private fun admit(bits: Int) {
            if (bits > MAX_BITS) throw Limit()
        }

        private fun words(value: BigInt): Long = (value.magnitudeBitLength().toLong() / Long.SIZE_BITS + 1L)
    }

    private companion object {
        const val MAX_INPUTS = 8
        const val MAX_BITS = 1024
    }
}
