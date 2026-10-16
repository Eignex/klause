package com.eignex.klause.formats.smtlib

import com.eignex.klause.ir.Lit
import com.eignex.klause.lowering.IntComb
import com.eignex.klause.lowering.LinComb
import com.eignex.klause.lowering.trueLit

internal class SmtLibConditionalTags(
    private val definitions: Map<Int, SmtLibConditionalEquality.Definition>,
    private val charge: () -> Boolean,
) {
    private class Support(val codes: Set<Int>, val constantsOnly: Boolean)
    private data class TagTest(val variable: Int, val code: Int)
    private data class LeafQuery(val code: Int, val op: String, val value: Long)
    private sealed interface Frame {
        class Eval(val term: LinComb) : Frame
        class Complete(val variable: Int, val definition: SmtLibConditionalEquality.Definition) : Frame
    }

    private var attemptedCodebook = false
    private var codebook: Map<LinComb, Int>? = null
    private var leaves: List<LinComb> = emptyList()
    private var width = 0
    private val supports = HashMap<Int, Support?>()
    private val bits = HashMap<Int, Array<Int?>>()
    private val tests = HashMap<TagTest, Int>()
    private val predicates = HashMap<LeafQuery, Int>()

    fun compare(variable: Int, op: String, value: Long, builder: Compiler.Builder): Int? {
        if (!pay() || !prepareCodebook(builder)) return null
        val support = support(variable) ?: return null
        if (op == "=" && support.constantsOnly) {
            if (!pay()) return null
            val code = codebook!!.get(LinComb(emptyMap(), value))
            val literal = if (code == null || code !in support.codes) Lit.negate(builder.trueLit())
            else test(variable, code, builder) ?: return null
            return if (pay()) literal else null
        }
        val alternatives = ArrayList<Int>()
        for (code in support.codes) {
            if (!pay()) return null
            val predicate = predicate(code, op, value, builder) ?: return null
            if (predicate == Lit.negate(builder.trueLit())) continue
            val selected = test(variable, code, builder) ?: return null
            alternatives += and(listOf(selected, predicate), builder) ?: return null
        }
        return or(alternatives, builder)
    }

    private fun prepareCodebook(builder: Compiler.Builder): Boolean {
        if (attemptedCodebook) return codebook != null
        attemptedCodebook = true
        val codes = LinkedHashMap<LinComb, Int>()
        val terms = ArrayList<LinComb>()
        fun include(term: LinComb): Boolean {
            if (!pay()) return false
            if (term.asSimpleVar()?.let { it in definitions } == true) return true
            val coefficients = LinkedHashMap<Int, Long>()
            for ((variable, coefficient) in term.coeffs) {
                if (!pay()) return false
                coefficients[variable] = coefficient
            }
            val key = LinComb(coefficients, term.constant)
            if (!pay()) return false
            if (key !in codes) {
                codes[key] = terms.size
                terms += key
            }
            return true
        }
        // Integer identities and arm order keep the codebook independent of coefficient map iteration.
        for (variable in 0 until builder.nextInt) {
            if (!pay()) return false
            val definition = definitions[variable] ?: continue
            for (arm in definition.arms) if (!include(arm)) return false
            if (!include(definition.default)) return false
        }
        var capacity = 1L
        while (capacity < terms.size) {
            if (!pay()) return false
            capacity *= 2
            width++
        }
        if (!pay()) return false
        codebook = codes
        leaves = terms
        return true
    }

    private fun support(variable: Int): Support? {
        if (!pay()) return null
        if (variable in supports) return supports[variable]
        val pending = ArrayDeque<Frame>()
        val active = HashSet<Int>()
        val completed = HashSet<Int>()
        val codes = LinkedHashSet<Int>()
        var constantsOnly = true
        pending.addLast(Frame.Eval(LinComb(mapOf(variable to 1L), 0)))
        while (pending.isNotEmpty()) {
            if (!pay()) return null
            when (val frame = pending.removeLast()) {
                is Frame.Eval -> {
                    val source = frame.term.asSimpleVar()
                    val definition = source?.let { definitions[it] }
                    when {
                        definition == null -> {
                            codes += codebook!!.getValue(frame.term)
                            if (codes.size > LEAF_LIMIT) return null.also { supports[variable] = null }
                            if (frame.term.coeffs.isNotEmpty()) constantsOnly = false
                        }
                        source != null && source in active -> return null.also { supports[variable] = null }
                        source != null && source in completed -> Unit
                        else -> {
                            active.add(source!!)
                            pending.addLast(Frame.Complete(source, definition))
                            if (!pay()) return null
                            pending.addLast(Frame.Eval(definition.default))
                            for (arm in definition.arms.asReversed()) {
                                if (!pay()) return null
                                pending.addLast(Frame.Eval(arm))
                            }
                        }
                    }
                }
                is Frame.Complete -> {
                    active.remove(frame.variable)
                    completed.add(frame.variable)
                }
            }
        }
        if (!pay()) return null
        return Support(codes, constantsOnly).also { supports[variable] = it }
    }

    private fun test(variable: Int, code: Int, builder: Compiler.Builder): Int? {
        if (!pay()) return null
        val key = TagTest(variable, code)
        tests[key]?.let { return it }
        val literals = ArrayList<Int>()
        for (index in 0 until width) {
            if (!pay()) return null
            val literal = bit(variable, index, builder) ?: return null
            literals += if ((code and (1 shl index)) == 0) Lit.negate(literal) else literal
        }
        return and(literals, builder)?.also { tests[key] = it }
    }

    private fun bit(variable: Int, index: Int, builder: Compiler.Builder): Int? {
        val pending = ArrayDeque<Frame>()
        val values = ArrayList<Int>()
        pending.addLast(Frame.Eval(LinComb(mapOf(variable to 1L), 0)))
        while (pending.isNotEmpty()) {
            if (!pay()) return null
            when (val frame = pending.removeLast()) {
                is Frame.Eval -> {
                    val source = frame.term.asSimpleVar()
                    val definition = source?.let { definitions[it] }
                    val cached = source?.let { bits[it]?.get(index) }
                    when {
                        cached != null -> values += cached
                        definition == null -> {
                            val code = codebook!!.getValue(frame.term)
                            val truth = builder.trueLit()
                            values += if ((code and (1 shl index)) == 0) Lit.negate(truth) else truth
                        }
                        else -> {
                            pending.addLast(Frame.Complete(source!!, definition))
                            if (!pay()) return null
                            pending.addLast(Frame.Eval(definition.default))
                            for (arm in definition.arms.asReversed()) {
                                if (!pay()) return null
                                pending.addLast(Frame.Eval(arm))
                            }
                        }
                    }
                }
                is Frame.Complete -> {
                    if (!pay(frame.definition.guards.size + 1)) return null
                    val default = values.removeAt(values.lastIndex)
                    val arms = IntArray(frame.definition.arms.size)
                    for (arm in arms.indices.reversed()) arms[arm] = values.removeAt(values.lastIndex)
                    val literal = joinBit(frame.definition, arms, default, builder) ?: return null
                    if (!pay()) return null
                    bits.getOrPut(frame.variable) { arrayOfNulls(width) }[index] = literal
                    values += literal
                }
            }
        }
        return values.single()
    }

    private fun joinBit(
        definition: SmtLibConditionalEquality.Definition,
        arms: IntArray,
        default: Int,
        builder: Compiler.Builder,
    ): Int? {
        if (!pay(arms.size)) return null
        if (arms.all { it == default }) return default
        val alternatives = ArrayList<Int>()
        val truth = builder.trueLit()
        for (index in arms.indices) {
            if (!pay()) return null
            if (arms[index] != Lit.negate(truth)) {
                alternatives += and(listOf(definition.guards[index], arms[index]), builder) ?: return null
            }
        }
        if (default != Lit.negate(truth)) {
            var noArm = definition.noArm
            if (noArm == null) {
                if (!pay(definition.guards.size)) return null
                noArm = and(definition.guards.map { Lit.negate(it) }, builder) ?: return null
                definition.noArm = noArm
            }
            alternatives += and(listOf(noArm, default), builder) ?: return null
        }
        return or(alternatives, builder)
    }

    private fun predicate(code: Int, op: String, value: Long, builder: Compiler.Builder): Int? {
        if (!pay()) return null
        val key = LeafQuery(code, op, value)
        predicates[key]?.let { return it }
        val term = leaves[code]
        if (!pay(term.coeffs.size + 1)) return null
        val literal = builder.reifyRawRelation(op, IntComb.Narrow(term), IntComb.Narrow(LinComb(emptyMap(), value)))
        if (!pay()) return null
        predicates[key] = literal
        return literal
    }

    private fun and(literals: List<Int>, builder: Compiler.Builder): Int? {
        if (!pay(literals.size + 1)) return null
        val literal = builder.foldConditionalAnd(literals)
        return if (pay()) literal else null
    }

    private fun or(literals: List<Int>, builder: Compiler.Builder): Int? {
        if (!pay(literals.size + 1)) return null
        val literal = builder.foldConditionalOr(literals)
        return if (pay()) literal else null
    }

    private fun pay(count: Int = 1): Boolean {
        repeat(count) { if (!charge()) return false }
        return true
    }

    private companion object {
        const val LEAF_LIMIT = 1_024
    }
}
