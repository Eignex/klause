package com.eignex.klause.formats.smtlib

import com.eignex.klause.factor.ReifiedFactor
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.LinearObjectiveSpec
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.lowering.IntComb
import com.eignex.klause.lowering.LinComb
import com.eignex.klause.lowering.reifyLinear
import com.eignex.klause.lowering.trueLit

internal class SmtLibConditionalEquality(private val workLimit: Int = 65_536) {
    class UnusedColumns(val ints: Set<Int>, val bools: Set<Int>)

    private class Definition(
        val guards: List<Int>,
        val arms: List<LinComb>,
        val default: LinComb,
        val factors: List<Factor>,
        val image: Set<Long>?,
        val guardTests: List<GuardEquality?>,
    ) {
        var noArm: Int? = null
        val guardColumns = guardTests.mapNotNull { it?.variable }.toSet()

        fun excludesDefault(value: Long): Boolean {
            val source = default.asSimpleVar()
            return source != null && source in guardColumns &&
                guardTests.any { it?.truthWhen(source, value) == true }
        }
    }

    private data class Key(val variable: Int, val value: Long)
    private class PendingEquality(val literal: Int, val factor: Factor)
    private data class Owner(val variable: Int, val integer: Boolean)
    private sealed interface Frame {
        class Eval(val term: LinComb) : Frame
        class Known(val literal: Int) : Frame
        class Join(val variable: Int, val definition: Definition) : Frame
    }

    private val definitions = HashMap<Int, Definition>()
    private val booleanDefinitions = HashMap<Int, List<Factor>>()
    private val equalities = HashMap<Key, Int>()
    private val pendingEqualities = LinkedHashMap<Key, PendingEquality>()
    private var work = 0

    fun define(
        variable: Int,
        guards: List<Int>,
        arms: List<LinComb>,
        default: LinComb,
        factors: List<Factor>,
        guardTests: List<GuardEquality?>,
    ) {
        definitions[variable] = Definition(
            guards.toList(), arms.toList(), default, factors.toList(),
            constantImage(arms + default), guardTests.toList(),
        )
    }

    fun definePredicate(variable: Int, factors: List<Factor>) {
        if (factors.isNotEmpty()) booleanDefinitions[variable] = factors.toList()
    }

    fun isDefined(variable: Int): Boolean = variable in definitions

    fun rememberPrimitive(variable: Int, value: Long, literal: Int) {
        if (variable !in definitions) equalities.putIfAbsent(Key(variable, value), literal)
    }

    private fun constantImage(terms: List<LinComb>): Set<Long>? {
        val values = HashSet<Long>()
        for (term in terms) {
            if (term.coeffs.isEmpty()) {
                values += term.constant
            } else {
                val source = term.asSimpleVar() ?: return null
                values += definitions[source]?.image ?: return null
            }
            if (values.size > IMAGE_LIMIT) return null
        }
        return values
    }

    fun retainNeededDefinitions(
        factors: MutableList<Factor>,
        sourceIntegers: Collection<Int>,
        sourceBooleans: Collection<Int>,
        objective: LinearObjectiveSpec?,
    ): UnusedColumns {
        if (definitions.isEmpty()) return UnusedColumns(emptySet(), emptySet())
        val owners = HashMap<Factor, Owner>()
        val integerOwners = definitions.keys.associateWith { Owner(it, integer = true) }
        val booleanOwners = HashMap<Int, Owner>()
        val ownedFactors = HashMap<Owner, List<Factor>>()
        fun own(owner: Owner, parts: List<Factor>) {
            ownedFactors[owner] = parts
            for (factor in parts) {
                owners[factor] = owner
                (factor as? ReifiedFactor)?.let { booleanOwners[it.auxBoolVar] = owner }
            }
        }
        for ((variable, definition) in definitions) {
            own(integerOwners.getValue(variable), definition.factors)
        }
        for ((variable, parts) in booleanDefinitions) {
            val owner = Owner(variable, integer = false)
            booleanOwners[variable] = owner
            own(owner, parts)
        }
        for (factor in factors) {
            if (factor is ReifiedFactor && factor !in owners && factor.auxBoolVar !in booleanOwners) {
                val owner = Owner(factor.auxBoolVar, integer = false)
                own(owner, listOf(factor))
            }
        }
        val retained = HashSet<Owner>()
        val pending = ArrayDeque<Owner>()
        fun need(owner: Owner?) {
            if (owner != null && retained.add(owner)) pending.addLast(owner)
        }
        fun read(factor: Factor) {
            factor.variables.ints.forEach { need(integerOwners[it]) }
            factor.variables.boolVars.forEach { need(booleanOwners[it]) }
        }
        sourceIntegers.forEach { need(integerOwners[it]) }
        sourceBooleans.forEach { need(booleanOwners[it]) }
        objective?.intCoefficients?.forEachIndexed { variable, coefficient ->
            if (coefficient != 0L) need(integerOwners[variable])
        }
        objective?.boolWeights?.forEachIndexed { variable, weight ->
            if (weight != 0L) need(booleanOwners[variable])
        }
        for (factor in factors) if (factor !in owners) read(factor)
        while (pending.isNotEmpty()) {
            ownedFactors.getValue(pending.removeFirst()).forEach(::read)
        }
        factors.removeAll { factor -> owners[factor]?.let { it !in retained } == true }
        return UnusedColumns(
            integerOwners.keys.filterTo(HashSet()) { integerOwners.getValue(it) !in retained },
            booleanOwners.keys.filterTo(HashSet()) { booleanOwners.getValue(it) !in retained },
        )
    }

    fun reify(variable: Int, value: Long, builder: Compiler.Builder): Int? {
        val key = Key(variable, value)
        equalities[key]?.let { return it }
        if (definitions[variable]?.image?.contains(value) == false) {
            return Lit.negate(builder.trueLit()).also { equalities[key] = it }
        }
        if (variable !in definitions) return null
        return pendingEqualities.getOrPut(key) {
            val literal = builder.reifyLinear(longArrayOf(1), intArrayOf(variable), LinearOp.EQ, value)
            PendingEquality(literal, builder.factors.last())
        }.literal
    }

    fun expandPending(builder: Compiler.Builder) {
        val replaced = HashSet<Factor>()
        for ((key, equality) in pendingEqualities.entries.toList().asReversed()) {
            val expanded = expand(key.variable, key.value, builder) ?: continue
            if (expanded == equality.literal) continue
            val clauses = listOf(
                Clause(intArrayOf(Lit.negate(equality.literal), expanded)),
                Clause(intArrayOf(equality.literal, Lit.negate(expanded))),
            )
            builder.factors.addAll(clauses)
            definePredicate(Lit.variable(equality.literal), clauses)
            replaced.add(equality.factor)
        }
        builder.factors.removeAll { it in replaced }
        pendingEqualities.clear()
    }

    private fun expand(variable: Int, value: Long, builder: Compiler.Builder): Int? {
        val key = Key(variable, value)
        equalities[key]?.let { return it }
        if (definitions[variable]?.image?.contains(value) == false) {
            return Lit.negate(builder.trueLit()).also { equalities[key] = it }
        }
        if (variable !in definitions || work >= workLimit) return null
        val pending = ArrayDeque<Frame>()
        val literals = ArrayDeque<Int>()
        pending.addLast(Frame.Eval(LinComb(mapOf(variable to 1L), 0)))
        while (pending.isNotEmpty()) {
            when (val frame = pending.removeLast()) {
                is Frame.Known -> {
                    if (++work > workLimit) return null
                    literals.addLast(frame.literal)
                }
                is Frame.Eval -> {
                    if (++work > workLimit) return null
                    val term = frame.term
                    val source = term.asSimpleVar()
                    val cached = source?.let { equalities[Key(it, value)] }
                    val definition = source?.let { definitions[it] }
                    when {
                        cached != null -> literals.addLast(cached)
                        term.coeffs.isEmpty() -> literals.addLast(
                            if (term.constant == value) builder.trueLit() else Lit.negate(builder.trueLit()),
                        )
                        source != null && definition?.image?.contains(value) == false -> {
                            val literal = Lit.negate(builder.trueLit())
                            equalities[Key(source, value)] = literal
                            literals.addLast(literal)
                        }
                        source != null && definition != null -> {
                            pending.addLast(Frame.Join(source, definition))
                            scheduleBranches(definition, value, builder, pending)
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
                        val guard = definition.guardTests[index]?.truthWhen(
                            definition.arms[index].asSimpleVar(), value,
                        )
                        alternatives += when (guard) {
                            true -> arms[index]
                            false -> Lit.negate(builder.trueLit())
                            null -> builder.foldConditionalAnd(listOf(definition.guards[index], arms[index]))
                        }
                    }
                    if (default != Lit.negate(builder.trueLit())) {
                        alternatives += defaultAlternative(definition, default, value, builder)
                    }
                    val literal = builder.foldConditionalOr(alternatives)
                    equalities[Key(frame.variable, value)] = literal
                    literals.addLast(literal)
                }
            }
        }
        return literals.single()
    }

    private fun scheduleBranches(
        definition: Definition,
        value: Long,
        builder: Compiler.Builder,
        pending: ArrayDeque<Frame>,
    ) {
        pending.addLast(
            if (definition.excludesDefault(value)) Frame.Known(Lit.negate(builder.trueLit()))
            else Frame.Eval(definition.default),
        )
        for (index in definition.arms.indices.reversed()) {
            val arm = definition.arms[index]
            val excluded = definition.guardTests[index]?.truthWhen(arm.asSimpleVar(), value) == false
            pending.addLast(if (excluded) Frame.Known(Lit.negate(builder.trueLit())) else Frame.Eval(arm))
        }
    }

    private fun defaultAlternative(
        definition: Definition,
        literal: Int,
        value: Long,
        builder: Compiler.Builder,
    ): Int {
        val source = definition.default.asSimpleVar()
        if (source != null && source in definition.guardColumns) {
            var simplified = false
            val guards = ArrayList<Int>()
            for (index in definition.guards.indices) {
                when (definition.guardTests[index]?.truthWhen(source, value)) {
                    true -> return Lit.negate(builder.trueLit())
                    false -> simplified = true
                    null -> guards.add(Lit.negate(definition.guards[index]))
                }
            }
            if (simplified) return builder.foldConditionalAnd(guards + literal)
        }
        val noArm = definition.noArm ?: builder.foldConditionalAnd(
            definition.guards.map { Lit.negate(it) },
        ).also { definition.noArm = it }
        return builder.foldConditionalAnd(listOf(noArm, literal))
    }

    private companion object {
        const val IMAGE_LIMIT = 1_024
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
        else -> reifyAnd(retained.toList())
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
        else -> reifyOr(retained.toList())
    }
}
