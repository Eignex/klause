package com.eignex.klause.formats.smtlib

import com.eignex.klause.factor.ReifiedFactor
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.LinearObjectiveSpec
import com.eignex.klause.ir.Lit
import com.eignex.klause.lowering.IntComb
import com.eignex.klause.lowering.LinComb
import com.eignex.klause.lowering.trueLit
import com.eignex.klause.lowering.tseitinAnd
import com.eignex.klause.lowering.tseitinOr

internal class SmtLibConditionalEquality {
    private class Definition(
        val guards: List<Int>,
        val arms: List<LinComb>,
        val default: LinComb,
        val factors: List<Factor>,
    ) {
        var noArm: Int? = null
    }

    private data class Key(val variable: Int, val value: Long)
    private sealed interface Frame {
        class Eval(val term: LinComb) : Frame
        class Join(val variable: Int, val definition: Definition) : Frame
    }

    private val definitions = HashMap<Int, Definition>()
    private val equalities = HashMap<Key, Int>()
    private var work = 0

    fun define(variable: Int, guards: List<Int>, arms: List<LinComb>, default: LinComb, factors: List<Factor>) {
        definitions[variable] = Definition(guards.toList(), arms.toList(), default, factors.toList())
    }

    fun retainNeededDefinitions(
        factors: MutableList<Factor>,
        sourceIntegers: Collection<Int>,
        sourceBooleans: Collection<Int>,
        objective: LinearObjectiveSpec?,
    ) {
        if (definitions.isEmpty()) return
        val owners = HashMap<Factor, Int>()
        val predicateOwners = HashMap<Int, Int>()
        for ((variable, definition) in definitions) {
            for (factor in definition.factors) {
                owners[factor] = variable
                (factor as? ReifiedFactor)?.let { predicateOwners[it.auxBoolVar] = variable }
            }
        }
        val retained = HashSet<Int>()
        val pending = ArrayDeque<Int>()
        fun need(variable: Int) {
            if (variable in definitions && retained.add(variable)) pending.addLast(variable)
        }
        fun needPredicate(variable: Int) {
            predicateOwners[variable]?.let(::need)
        }
        fun read(factor: Factor) {
            factor.variables.ints.forEach(::need)
            factor.variables.boolVars.forEach(::needPredicate)
        }
        sourceIntegers.forEach(::need)
        sourceBooleans.forEach(::needPredicate)
        objective?.intCoefficients?.forEachIndexed { variable, coefficient -> if (coefficient != 0L) need(variable) }
        objective?.boolWeights?.forEachIndexed { variable, weight -> if (weight != 0L) needPredicate(variable) }
        for (factor in factors) if (factor !in owners) read(factor)
        while (pending.isNotEmpty()) {
            definitions.getValue(pending.removeFirst()).factors.forEach(::read)
        }
        factors.removeAll { factor -> owners[factor]?.let { it !in retained } == true }
        for ((predicate, owner) in predicateOwners) {
            if (owner !in retained) factors.add(Clause(intArrayOf(Lit.make(predicate, false))))
        }
    }

    fun reify(variable: Int, value: Long, builder: Compiler.Builder): Int? {
        val key = Key(variable, value)
        equalities[key]?.let { return it }
        if (variable !in definitions || work >= WORK_LIMIT) return null
        val pending = ArrayDeque<Frame>()
        val literals = ArrayDeque<Int>()
        pending.addLast(Frame.Eval(LinComb(mapOf(variable to 1L), 0)))
        while (pending.isNotEmpty()) {
            when (val frame = pending.removeLast()) {
                is Frame.Eval -> {
                    if (++work > WORK_LIMIT) return null
                    val term = frame.term
                    val source = term.asSimpleVar()
                    val cached = source?.let { equalities[Key(it, value)] }
                    val definition = source?.let { definitions[it] }
                    when {
                        cached != null -> literals.addLast(cached)
                        term.coeffs.isEmpty() -> literals.addLast(
                            if (term.constant == value) builder.trueLit() else Lit.negate(builder.trueLit()),
                        )
                        source != null && definition != null -> {
                            pending.addLast(Frame.Join(source, definition))
                            pending.addLast(Frame.Eval(definition.default))
                            for (arm in definition.arms.asReversed()) pending.addLast(Frame.Eval(arm))
                        }
                        else -> {
                            val literal = builder.reifyRelation(
                                "=", IntComb.Narrow(term), IntComb.Narrow(LinComb(emptyMap(), value)),
                            )
                            literals.addLast(literal)
                            if (source != null) equalities[Key(source, value)] = literal
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
                    if (default != Lit.negate(builder.trueLit())) {
                        val noArm = definition.noArm ?: builder.foldConditionalAnd(
                            definition.guards.map { Lit.negate(it) },
                        ).also { definition.noArm = it }
                        alternatives += builder.foldConditionalAnd(listOf(noArm, default))
                    }
                    val literal = builder.foldConditionalOr(alternatives)
                    equalities[Key(frame.variable, value)] = literal
                    literals.addLast(literal)
                }
            }
        }
        return literals.single()
    }

    private companion object {
        const val WORK_LIMIT = 65_536
    }
}

private fun Compiler.Builder.foldConditionalAnd(literals: List<Int>): Int {
    val truth = trueLit()
    val retained = LinkedHashSet<Int>()
    for (literal in literals) {
        if (literal == Lit.negate(truth) || Lit.negate(literal) in retained) return Lit.negate(truth)
        if (literal != truth) retained += literal
    }
    return when (retained.size) {
        0 -> truth
        1 -> retained.first()
        else -> tseitinAnd(retained.toList())
    }
}

private fun Compiler.Builder.foldConditionalOr(literals: List<Int>): Int {
    val truth = trueLit()
    val retained = LinkedHashSet<Int>()
    for (literal in literals) {
        if (literal == truth || Lit.negate(literal) in retained) return truth
        if (literal != Lit.negate(truth)) retained += literal
    }
    return when (retained.size) {
        0 -> Lit.negate(truth)
        1 -> retained.first()
        else -> tseitinOr(retained.toList())
    }
}
