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

    private sealed interface ComparisonKey
    private data class Key(val variable: Int, val value: Long, val operator: LinearOp = LinearOp.EQ) : ComparisonKey
    private data class PairKey(val left: Int, val right: Int, val operator: LinearOp) : ComparisonKey
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
    private val pendingEqualities = LinkedHashMap<ComparisonKey, PendingEquality>()
    private val pairEqualities = HashMap<PairKey, Int>()
    private var work = 0
    private var diagnosticPending = emptyMap<String, Int>()
    private val diagnosticCompleted = HashMap<String, Int>()

    private fun diagnosticName(key: ComparisonKey): String = when (key) {
        is Key -> key.operator.name
        is PairKey -> "PAIR_${key.operator.name}"
    }

    fun printDiagnosticCounters(unused: UnusedColumns, factors: Int) {
        for (operator in listOf("EQ", "LE", "GE", "PAIR_LE", "PAIR_GE")) {
            println("; conditionalPending_$operator=${diagnosticPending[operator] ?: 0}")
            println("; conditionalCompleted_$operator=${diagnosticCompleted[operator] ?: 0}")
        }
        println("; conditionalExpansionVisits=$work")
        println("; conditionalDefinedInts=${definitions.size}")
        println("; conditionalRetainedIntDefinitions=${definitions.size - unused.ints.size}")
        println("; conditionalFactors=$factors")
    }

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

    fun rememberPrimitive(variable: Int, value: Long, literal: Int, operator: LinearOp = LinearOp.EQ) {
        if (variable !in definitions) equalities.getOrPut(Key(variable, value, operator)) { literal }
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

    fun reify(variable: Int, value: Long, builder: Compiler.Builder, operator: LinearOp = LinearOp.EQ): Int? {
        val key = Key(variable, value, operator)
        equalities[key]?.let { return it }
        imageTruth(variable, value, operator)?.let { truth ->
            return truthLiteral(truth, builder).also { equalities[key] = it }
        }
        if (variable !in definitions) return null
        return pendingEqualities.getOrPut(key) {
            val literal = builder.reifyLinear(longArrayOf(1), intArrayOf(variable), operator, value)
            PendingEquality(literal, builder.factors.last())
        }.literal
    }

    fun reifyPair(left: Int, right: Int, operator: LinearOp, builder: Compiler.Builder): Int? {
        if (operator != LinearOp.LE && operator != LinearOp.GE) return null
        val leftImage = definitions[left]?.image?.takeIf { it.size <= PAIR_IMAGE_LIMIT }
        val rightImage = definitions[right]?.image?.takeIf { it.size <= PAIR_IMAGE_LIMIT }
        if (leftImage == null && rightImage == null) return null
        val key = PairKey(left, right, operator)
        pairEqualities[key]?.let { return it }
        return pendingEqualities.getOrPut(key) {
            val literal = builder.reifyLinear(longArrayOf(1, -1), intArrayOf(left, right), operator, 0)
            PendingEquality(literal, builder.factors.last())
        }.literal
    }

    fun expandPending(builder: Compiler.Builder) {
        diagnosticPending = pendingEqualities.keys.groupingBy(::diagnosticName).eachCount()
        val replaced = HashSet<Factor>()
        for ((key, equality) in pendingEqualities.entries.toList().asReversed()) {
            val expanded = when (key) {
                is Key -> expand(key.variable, key.value, builder, key.operator)
                is PairKey -> expandPair(key, builder)
            } ?: continue
            if (expanded == equality.literal) continue
            val clauses = listOf(
                Clause(intArrayOf(Lit.negate(equality.literal), expanded)),
                Clause(intArrayOf(equality.literal, Lit.negate(expanded))),
            )
            builder.factors.addAll(clauses)
            definePredicate(Lit.variable(equality.literal), clauses)
            replaced.add(equality.factor)
            val name = diagnosticName(key)
            diagnosticCompleted[name] = (diagnosticCompleted[name] ?: 0) + 1
        }
        builder.factors.removeAll { it in replaced }
        pendingEqualities.clear()
    }

    private fun expandPair(key: PairKey, builder: Compiler.Builder): Int? {
        pairEqualities[key]?.let { return it }
        val rightImage = definitions[key.right]?.image?.takeIf { it.size <= PAIR_IMAGE_LIMIT }
        val leftImage = definitions[key.left]?.image?.takeIf { it.size <= PAIR_IMAGE_LIMIT }
        val selectRight = rightImage != null && (leftImage == null || rightImage.size <= leftImage.size)
        val image = if (selectRight) rightImage else leftImage
        if (image == null || work >= workLimit) return null
        val selected = if (selectRight) key.right else key.left
        val other = if (selectRight) key.left else key.right
        val operator = if (selectRight) key.operator else {
            if (key.operator == LinearOp.LE) LinearOp.GE else LinearOp.LE
        }
        val alternatives = ArrayList<Int>()
        for (value in image) {
            if (++work > workLimit) return null
            val guard = expand(selected, value, builder, LinearOp.EQ) ?: return null
            val comparison = if (other in definitions) expand(other, value, builder, operator) ?: return null else {
                builder.reifyRelation(
                    operatorText(operator), IntComb.Narrow(LinComb(mapOf(other to 1L), 0)),
                    IntComb.Narrow(LinComb(emptyMap(), value)),
                )
            }
            alternatives.add(builder.foldConditionalAnd(listOf(guard, comparison)))
        }
        return builder.foldConditionalOr(alternatives).also { pairEqualities[key] = it }
    }

    private fun expand(variable: Int, value: Long, builder: Compiler.Builder, operator: LinearOp): Int? {
        val key = Key(variable, value, operator)
        equalities[key]?.let { return it }
        imageTruth(variable, value, operator)?.let { truth ->
            return truthLiteral(truth, builder).also { equalities[key] = it }
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
                    val cached = source?.let { equalities[Key(it, value, operator)] }
                    val definition = source?.let { definitions[it] }
                    val imageTruth = source?.let { imageTruth(it, value, operator) }
                    when {
                        cached != null -> literals.addLast(cached)
                        term.coeffs.isEmpty() -> literals.addLast(
                            truthLiteral(compare(term.constant, value, operator), builder),
                        )
                        source != null && imageTruth != null -> {
                            val literal = truthLiteral(imageTruth, builder)
                            equalities[Key(source, value, operator)] = literal
                            literals.addLast(literal)
                        }
                        source != null && definition != null -> {
                            pending.addLast(Frame.Join(source, definition))
                            scheduleBranches(definition, value, builder, pending, operator)
                        }
                        else -> {
                            val literal = builder.reifyRelation(
                                operatorText(operator), IntComb.Narrow(term),
                                IntComb.Narrow(LinComb(emptyMap(), value)),
                            )
                            literals.addLast(literal)
                            if (source != null) equalities[Key(source, value, operator)] = literal
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
                        val guard = if (operator == LinearOp.EQ) definition.guardTests[index]?.truthWhen(
                            definition.arms[index].asSimpleVar(), value,
                        ) else null
                        alternatives += when (guard) {
                            true -> arms[index]
                            false -> Lit.negate(builder.trueLit())
                            null -> builder.foldConditionalAnd(listOf(definition.guards[index], arms[index]))
                        }
                    }
                    if (default != Lit.negate(builder.trueLit())) {
                        alternatives += defaultAlternative(definition, default, value, builder, operator)
                    }
                    val literal = builder.foldConditionalOr(alternatives)
                    equalities[Key(frame.variable, value, operator)] = literal
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
        operator: LinearOp,
    ) {
        pending.addLast(
            if (operator == LinearOp.EQ && definition.excludesDefault(value)) Frame.Known(Lit.negate(builder.trueLit()))
            else Frame.Eval(definition.default),
        )
        for (index in definition.arms.indices.reversed()) {
            val arm = definition.arms[index]
            val excluded = operator == LinearOp.EQ &&
                definition.guardTests[index]?.truthWhen(arm.asSimpleVar(), value) == false
            pending.addLast(if (excluded) Frame.Known(Lit.negate(builder.trueLit())) else Frame.Eval(arm))
        }
    }

    private fun defaultAlternative(
        definition: Definition,
        literal: Int,
        value: Long,
        builder: Compiler.Builder,
        operator: LinearOp,
    ): Int {
        val source = definition.default.asSimpleVar()
        if (operator == LinearOp.EQ && source != null && source in definition.guardColumns) {
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

    private fun imageTruth(variable: Int, value: Long, operator: LinearOp): Boolean? {
        val image = definitions[variable]?.image ?: return null
        if (operator == LinearOp.EQ) return if (value !in image) false else null
        if (image.all { compare(it, value, operator) }) return true
        if (image.none { compare(it, value, operator) }) return false
        return null
    }

    private fun truthLiteral(truth: Boolean, builder: Compiler.Builder): Int =
        if (truth) builder.trueLit() else Lit.negate(builder.trueLit())

    private fun compare(left: Long, right: Long, operator: LinearOp): Boolean = when (operator) {
        LinearOp.EQ -> left == right
        LinearOp.LE -> left <= right
        LinearOp.GE -> left >= right
        LinearOp.NE -> left != right
    }

    private fun operatorText(operator: LinearOp): String = when (operator) {
        LinearOp.EQ -> "="
        LinearOp.LE -> "<="
        LinearOp.GE -> ">="
        LinearOp.NE -> "distinct"
    }

    private companion object {
        const val IMAGE_LIMIT = 1_024
        const val PAIR_IMAGE_LIMIT = 64
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
