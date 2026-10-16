package com.eignex.klause.formats.smtlib

import com.eignex.klause.ir.Lit
import com.eignex.klause.lowering.LinComb
import com.eignex.klause.lowering.trueLit

internal class SmtLibFiniteImageEncoding(
    private val definitions: Map<Int, SmtLibConditionalEquality.Definition>,
    private val visit: () -> Boolean,
) {
    private data class Key(val variable: Int, val bit: Int)
    private sealed interface Frame {
        class Eval(val term: LinComb) : Frame
        class Join(val variable: Int, val definition: SmtLibConditionalEquality.Definition) : Frame
    }

    private val eligible = HashMap<Int, Boolean>()
    private val bits = HashMap<Key, Int>()
    private var codes: Map<Long, Int>? = null
    private var width = 0
    private var stopped = false

    fun accepts(variable: Int): Boolean {
        val image = definitions[variable]?.image ?: return false
        if (image.size <= 2 && (codes == null || (0 until width).any { Key(variable, it) !in bits })) return false
        if (codes != null && image.size <= width && (0 until width).any { Key(variable, it) !in bits }) return false
        if (stopped || !qualify(variable)) return false
        if (codes == null && !prepareCodes()) return false
        return image.size > width || (0 until width).all { Key(variable, it) in bits }
    }

    private fun qualify(variable: Int): Boolean {
        eligible[variable]?.let { return it }
        val pending = ArrayDeque<Pair<Int, Boolean>>()
        val active = HashSet<Int>()
        pending.addLast(variable to false)
        while (pending.isNotEmpty()) {
            val (current, join) = pending.removeLast()
            if (current in eligible) continue
            val definition = definitions[current]
            if (!join) {
                if (!charge()) return false
                if (definition == null || definition.image.isNullOrEmpty() || !active.add(current)) {
                    eligible[current] = false
                    continue
                }
                pending.addLast(current to true)
                for (term in definition.arms + definition.default) {
                    if (!charge()) return false
                    if (term.coeffs.isEmpty()) continue
                    val child = term.asSimpleVar()
                    if (child == null) eligible[current] = false
                    else pending.addLast(child to false)
                }
            } else {
                active.remove(current)
                eligible[current] = (definition!!.arms + definition.default).all { term ->
                    term.coeffs.isEmpty() || term.asSimpleVar()?.let { eligible[it] } == true
                }
            }
        }
        return eligible[variable] == true
    }

    private fun prepareCodes(): Boolean {
        val values = HashSet<Long>()
        for (definition in definitions.values) {
            for (term in definition.arms + definition.default) {
                if (!charge()) return false
                if (term.coeffs.isEmpty()) values += term.constant
            }
        }
        codes = values.sorted().withIndex().associate { it.value to it.index }
        var largest = values.size - 1
        while (largest > 0) {
            width++
            largest = largest ushr 1
        }
        return true
    }

    fun equality(variable: Int, value: Long, builder: Compiler.Builder): Int? {
        val code = codes!!.getValue(value)
        val literals = ArrayList<Int>()
        for (bit in 0 until width) {
            val literal = bit(variable, bit, builder) ?: return null
            literals += if (code and (1 shl bit) == 0) Lit.negate(literal) else literal
        }
        return builder.foldConditionalAnd(literals)
    }

    private fun bit(variable: Int, bit: Int, builder: Compiler.Builder): Int? {
        bits[Key(variable, bit)]?.let { return it }
        val pending = ArrayDeque<Frame>()
        val literals = ArrayDeque<Int>()
        pending.addLast(Frame.Eval(LinComb(mapOf(variable to 1L), 0)))
        val truth = builder.trueLit()
        while (pending.isNotEmpty()) {
            when (val frame = pending.removeLast()) {
                is Frame.Eval -> {
                    if (!charge()) return null
                    val term = frame.term
                    if (term.coeffs.isEmpty()) {
                        literals.addLast(if (hasBit(term.constant, bit)) truth else Lit.negate(truth))
                        continue
                    }
                    val source = term.asSimpleVar()!!
                    val key = Key(source, bit)
                    val definition = definitions.getValue(source)
                    val cached = bits[key]
                    val values = definition.image!!
                    val first = hasBit(values.first(), bit)
                    when {
                        cached != null -> literals.addLast(cached)
                        values.all { hasBit(it, bit) == first } -> {
                            val literal = if (first) truth else Lit.negate(truth)
                            bits[key] = literal
                            literals.addLast(literal)
                        }
                        else -> {
                            pending.addLast(Frame.Join(source, definition))
                            pending.addLast(Frame.Eval(definition.default))
                            for (arm in definition.arms.asReversed()) pending.addLast(Frame.Eval(arm))
                        }
                    }
                }
                is Frame.Join -> {
                    val definition = frame.definition
                    val default = literals.removeLast()
                    val arms = IntArray(definition.arms.size)
                    for (index in arms.indices.reversed()) arms[index] = literals.removeLast()
                    val alternatives = ArrayList<Int>()
                    for (index in arms.indices) {
                        alternatives += builder.foldConditionalAnd(listOf(definition.guards[index], arms[index]))
                    }
                    val noArm = definition.noArm ?: builder.foldConditionalAnd(
                        definition.guards.map { Lit.negate(it) },
                    ).also { definition.noArm = it }
                    alternatives += builder.foldConditionalAnd(listOf(noArm, default))
                    val literal = builder.foldConditionalOr(alternatives)
                    bits[Key(frame.variable, bit)] = literal
                    literals.addLast(literal)
                }
            }
        }
        return literals.single()
    }

    private fun hasBit(value: Long, bit: Int): Boolean = codes!!.getValue(value) and (1 shl bit) != 0

    private fun charge(): Boolean {
        if (stopped) return false
        if (!visit()) stopped = true
        return !stopped
    }
}
