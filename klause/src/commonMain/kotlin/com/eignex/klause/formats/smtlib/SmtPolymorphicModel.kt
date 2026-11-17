package com.eignex.klause.formats.smtlib

internal class SmtPolymorphicModel(
    private val source: SmtTypedSource,
    private val archetypes: Map<Int, String>,
    private val transports: Map<String, String>,
    private val instanceKeys: Map<SmtSourceDeclaration, String>,
    private val constructive: Boolean,
) : SmtSourceCheck {
    private val symbols = source.symbols.associateBy { it.identity }
    private sealed interface Frame {
        data class Evaluate(val node: Int, val locals: Map<Int, Boolean>) : Frame
        data class Combine(val node: Int, val locals: Map<Int, Boolean>) : Frame
    }

    fun bindings(bindings: List<SmtSourceBinding>): List<SmtSourceBinding> = if (!constructive) bindings
    else bindings.map { binding ->
        val archetype = transports[polynomialSymbol(binding.key)]
        if (archetype == null || polynomialSymbol(archetype) == polynomialSymbol(binding.key)) binding
        else binding.copy(reconstruction = SmtPolymorphicTransport(archetype, binding.declaration))
    }

    fun instantiate(name: String, parameters: List<SmtSort>, result: SmtSort, values: SmtModelValues,
        externalPoint: ((SmtSort, Boolean) -> SmtValue?)? = null): SmtValue? {
        values.checkpoint()
        for (sort in parameters + result) sort.checkpointStructure { values.checkpoint() }
        val symbol = source.symbols.firstOrNull {
            values.checkpoint()
            polynomialSymbol(it.declaration.name) == polynomialSymbol(name) && it.definition == null
        } ?: return null
        val rank = instantiateSmtRank(symbol.declaration.parameters, symbol.declaration.result,
            source.sortParameters, parameters, result, values.cancellation) ?: return null
        val declaration = symbol.declaration.copy(parameters = rank.first, result = rank.second)
        instanceKeys[declaration]?.let { return values.value(it) }
        val archetype = archetypes[symbol.identity]
        if (constructive && archetype != null) {
            return try {
                SmtPolymorphicTransport(archetype, declaration) { sort, second ->
                    modelPoint(sort, second, values, externalPoint)
                }.reconstruct(values)
            } catch (_: SmtSourceCheckingUnavailable) {
                null
            }
        }
        val point = modelPoint(result, false, values, externalPoint) ?: return null
        return if (parameters.isEmpty()) point else SmtValue.Function(parameters, emptyList(), point)
    }

    private fun modelPoint(sort: SmtSort, second: Boolean, values: SmtModelValues,
        externalPoint: ((SmtSort, Boolean) -> SmtValue?)?): SmtValue? = smtArrayModelPoint(sort, values) { leaf ->
        if (leaf is SmtSort.Uninterpreted && source.commands.none {
                values.checkpoint()
                val command = it as? SExpr.SList
                command?.polynomialHead() == "declare-sort" &&
                    command.polynomialName(1) == polynomialSymbol(leaf.name)
            }) externalPoint?.invoke(leaf, second)
        else smtPolymorphicPoint(leaf, second, values)
    }

    override fun check(values: SmtModelValues): Boolean {
        for ((head, root) in source.obligations) {
            values.checkpoint()
            if (head != "assert" || !evaluate(root, values)) return false
        }
        return true
    }

    private fun evaluate(root: Int, values: SmtModelValues): Boolean {
        val work = ArrayDeque<Frame>()
        val stack = ArrayDeque<Boolean>()
        work.addLast(Frame.Evaluate(root, emptyMap()))
        while (work.isNotEmpty()) {
            values.checkpoint()
            when (val frame = work.removeLast()) {
                is Frame.Evaluate -> {
                    val local = frame.locals[frame.node]
                    if (local != null) {
                        stack.addLast(local)
                        continue
                    }
                    work.addLast(Frame.Combine(frame.node, frame.locals))
                    for (argument in source.terms[frame.node].arguments.asReversed()) {
                        work.addLast(Frame.Evaluate(argument, frame.locals))
                    }
                }
                is Frame.Combine -> {
                    val node = source.terms[frame.node]
                    val args = List(node.arguments.size) { stack.removeLast() }.reversed()
                    val symbol = if (node.operator.startsWith("symbol:")) {
                        symbols[node.operator.substringAfter(':').substringBefore(':').toInt()]
                    } else null
                    if (symbol?.definition != null) {
                        work.addLast(Frame.Evaluate(symbol.definition.body,
                            symbol.definition.parameters.zip(args).toMap()))
                        continue
                    }
                    stack.addLast(if (symbol != null) {
                        val model = values.value(archetypes.getValue(symbol.identity))
                        if (args.isEmpty()) (model as SmtValue.Boolean).value
                        else applySmtBooleanFunction(model as SmtValue.Function, args, values)
                    } else when (node.operator) {
                        "literal:true" -> true
                        "literal:false" -> false
                        "and" -> args.all { it }
                        "or" -> args.any { it }
                        "not" -> !args.single()
                        "xor" -> args.count { it } % 2 == 1
                        "=>" -> args.dropLast(1).asReversed().fold(args.last()) { right, left -> !left || right }
                        "=" -> args.all { it == args[0] }
                        "distinct" -> args.toSet().size == args.size
                        "ite" -> if (args[0]) args[1] else args[2]
                        else -> throw SmtSourceCheckingUnavailable()
                    })
                }
            }
        }
        return stack.single()
    }
}

internal fun applySmtBooleanFunction(function: SmtValue.Function, arguments: List<Boolean>,
    values: SmtModelValues): Boolean {
    for ((tuple, result) in function.entries) {
        values.checkpoint()
        if (tuple.map { (it as SmtValue.Boolean).value } == arguments) return (result as SmtValue.Boolean).value
    }
    return (function.default as SmtValue.Boolean).value
}

private class SmtPolymorphicTransport(private val archetype: String,
    private val declaration: SmtSourceDeclaration,
    private val externalPoint: ((SmtSort, Boolean) -> SmtValue?)? = null) : SmtReconstruction {
    override fun reconstruct(values: SmtModelValues): SmtValue {
        values.checkpoint()
        val original = values.value(archetype)
        if (declaration.parameters.isEmpty()) {
            return smtPolymorphicPoint(declaration.result, (original as SmtValue.Boolean).value, values, externalPoint)
        }
        val function = original as SmtValue.Function
        val assignments = ArrayDeque<List<Boolean>>()
        val entries = ArrayList<Pair<List<SmtValue>, SmtValue>>()
        assignments.addLast(emptyList())
        while (assignments.isNotEmpty()) {
            values.checkpoint()
            val arguments = assignments.removeLast()
            if (arguments.size != declaration.parameters.size) {
                assignments.addLast(arguments + false)
                assignments.addLast(arguments + true)
                continue
            }
            val tuple = arguments.indices.map {
                smtPolymorphicPoint(declaration.parameters[it], arguments[it], values, externalPoint)
            }
            val result = smtPolymorphicPoint(declaration.result, applySmtBooleanFunction(function, arguments, values),
                values, externalPoint)
            entries += tuple to result
        }
        return SmtValue.Function(declaration.parameters, entries,
            smtPolymorphicPoint(declaration.result, (function.default as SmtValue.Boolean).value,
                values, externalPoint))
    }
}

internal fun smtPolymorphicPoint(sort: SmtSort, second: Boolean, values: SmtModelValues,
    externalPoint: ((SmtSort, Boolean) -> SmtValue?)? = null): SmtValue {
    values.checkpoint()
    if (externalPoint != null) return externalPoint(sort, second) ?: throw SmtSourceCheckingUnavailable()
    return smtArrayModelPoint(sort, values) { leaf ->
        when (leaf) {
            SmtSort.Bool -> SmtValue.Boolean(second)
            SmtSort.Int -> SmtValue.Integer(if (second) "1" else "0")
            SmtSort.Real -> SmtValue.Real(if (second) "1.0" else "0.0")
            is SmtSort.BitVector -> SmtValue.BitVector("0".repeat(leaf.width - 1) + if (second) "1" else "0")
            is SmtSort.Uninterpreted -> {
                val identity = buildString {
                    append("@klause.poly.point.")
                    for (character in leaf.toSmtLib()) {
                        values.checkpoint()
                        append(character.code.toString(16).padStart(4, '0'))
                    }
                    append(if (second) ".1" else ".0")
                }
                SmtValue.Uninterpreted(leaf, identity)
            }
            is SmtSort.Array -> error("array prefixes are handled before scalar model points")
        }
    } ?: throw SmtSourceCheckingUnavailable()
}

private fun smtArrayModelPoint(sort: SmtSort, values: SmtModelValues,
    point: (SmtSort) -> SmtValue?): SmtValue? {
    val arrays = ArrayDeque<SmtSort.Array>()
    var leaf = sort
    while (leaf is SmtSort.Array) {
        values.checkpoint()
        arrays.addLast(leaf)
        leaf = leaf.element
    }
    var value = point(leaf) ?: return null
    while (arrays.isNotEmpty()) {
        values.checkpoint()
        value = SmtValue.Array(arrays.removeLast(), value, emptyList())
    }
    return value
}
