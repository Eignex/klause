package com.eignex.klause.formats.smtlib

import com.eignex.klause.lowering.polynomialSourceCheckpoint
import com.eignex.klause.util.ExecutionControl

internal fun reduceSmtGroundArrays(input: SmtReduction, cancellation: ExecutionControl): SmtReduction {
    if (input.datatypes != null) return SmtGroundArrayReducer(input, cancellation).reduce()
    val work = ArrayDeque<SExpr>()
    for (expression in input.commands) {
        cancellation.polynomialSourceCheckpoint(1)
        val command = expression as? SExpr.SList ?: continue
        if (command.polynomialHead() in setOf("declare-const", "declare-fun")) work.addLast(command.items.last())
    }
    while (work.isNotEmpty()) {
        cancellation.polynomialSourceCheckpoint(1)
        when (val sort = work.removeLast()) {
            is SExpr.Atom -> Unit
            is SExpr.SList -> {
                if (sort.polynomialHead() == "Array") return SmtGroundArrayReducer(input, cancellation).reduce()
                sort.items.drop(1).forEach(work::addLast)
            }
        }
    }
    return input
}

private class SmtGroundArrayReducer(private val input: SmtReduction, private val cancellation: ExecutionControl) {
    private data class Term(val id: Int, val sort: SmtSort, val expression: SExpr)
    private data class Node(val head: SExpr, val arguments: List<Int>)
    private data class ArrayTerm(val term: Term, val operator: String, val arguments: List<Term>)
    private data class PairKey(val first: Int, val second: Int)
    private data class Equality(val left: Term, val right: Term, val name: String, val witness: Term?)
    private data class Read(val array: Term, val index: Term, val value: Term)
    private data class Frame(val expression: SExpr, val combine: Boolean)

    private val output = ArrayList<SExpr>()
    private val sortScope = SmtLibScope(cancellation)
    private val axioms = ArrayList<SExpr>()
    private val symbols = LinkedHashMap<String, Term>()
    private val nodes = HashMap<Node, Term>()
    private val arrays = LinkedHashMap<Int, ArrayTerm>()

    // Inner extensional witnesses must not create outer reads merely because their index sorts agree.
    private val indexes = LinkedHashMap<SmtSort.Array, LinkedHashMap<Int, Term>>()
    private val atoms = HashMap<Pair<String, SmtSort>, Term>()
    private val equalities = LinkedHashMap<PairKey, Equality>()
    private val reads = LinkedHashMap<PairKey, Read>()
    private val captures = HashMap<Int, Term>()
    private val defaults = LinkedHashMap<SmtSort, Term>()
    private val processedReads = HashSet<PairKey>()
    private val processedWitnesses = HashSet<String>()
    private val processedAgreements = HashSet<Pair<String, Int>>()
    private val processedCongruences = HashSet<PairKey>()
    private val sourceBindings = input.bindings.associateByTo(LinkedHashMap()) { polynomialSymbol(it.key) }
    private val privateBindings = input.privateBindings.toMutableMap()
    private val usedNames = HashSet<String>()
    private var nextTerm = 0
    private var nextName = 0
    private var queryPosition: Int? = null

    fun reduce(): SmtReduction {
        collectNames()
        for (expression in input.commands) {
            step()
            val command = expression as SExpr.SList
            when (command.polynomialHead()) {
                "declare-sort", "define-sort" -> {
                    sortScope.command(command)
                    append(expression)
                }
                "declare-const", "declare-fun" -> declare(command)
                "assert", "minimize", "maximize" ->
                    output += form(command.items[0], rewrite(command.items[1]).expression)
                "check-sat-assuming", "get-value" -> {
                    val values = (command.items[1] as SExpr.SList).items.map { rewrite(it).expression }
                    append(form(command.items[0], SExpr.SList(values)))
                }
                "define-fun" -> smtUnsupported("array reduction requires expanded ground definitions")
                else -> append(expression)
            }
        }
        close()
        val allReads = reads.values.toList()
        for ((_, array) in arrays) {
            step()
            val sort = array.term.sort as SmtSort.Array
            val entries = allReads.filter {
                step()
                it.array.id == array.term.id
            }
                .map {
                    step()
                    it.index.expressionName() to it.value.expressionName()
                }
            val name = array.term.expressionName()
            val recipe = SmtArrayReconstruction(sort, defaults.getValue(sort.element).expressionName(), entries)
            privateBindings[name] = recipe
            sourceBindings[polynomialSymbol(name)]?.let {
                sourceBindings[polynomialSymbol(name)] = it.copy(reconstruction = recipe)
            }
        }
        output.addAll(queryPosition ?: output.size, axioms)
        val checks = equalities.values.filter {
            step()
            it.witness != null
        }.map { equality ->
            step()
            SmtArrayEqualityCheck(equality.name, equality.left.expressionName(), equality.right.expressionName())
        }
        val operationChecks = arrays.values.filter {
            step()
            it.operator != "base"
        }.map { array ->
            step()
            SmtArrayOperationCheck(array.term.expressionName(), array.operator,
                array.arguments.map { it.expressionName() })
        }
        val readChecks = allReads.map { read ->
            step()
            SmtArrayReadCheck(read.array.expressionName(), read.index.expressionName(), read.value.expressionName())
        }
        return input.copy(commands = output.toList(), bindings = sourceBindings.values.toList(),
            privateBindings = privateBindings.toMap(), checks = input.checks + checks + operationChecks + readChecks)
    }

    private fun declare(command: SExpr.SList) {
        val name = command.polynomialName(1)
        val args = if (command.polynomialHead() == "declare-fun") (command.items[2] as SExpr.SList).items
        else emptyList()
        if (args.isNotEmpty()) smtUnsupported("array reduction requires eliminated ground function applications")
        val sort = sortScope.sourceSort(command.items.last())
        val term = atom((command.items[1] as SExpr.Atom).text, sort)
        symbols[name] = term
        if (sort is SmtSort.Array) {
            arrays[term.id] = ArrayTerm(term, "base", emptyList())
        } else output += command
    }

    private fun rewrite(root: SExpr): Term {
        val work = ArrayDeque<Frame>()
        val values = ArrayDeque<Term>()
        work.addLast(Frame(root, false))
        while (work.isNotEmpty()) {
            step()
            val frame = work.removeLast()
            when (val expression = frame.expression) {
                is SExpr.Atom -> values.addLast(if (arrayLexicalLiteral(expression.text)) literal(expression)
                else symbols[polynomialSymbol(expression.text)] ?: literal(expression))
                is SExpr.SList -> if ((expression.items.firstOrNull() as? SExpr.Atom)?.text == "_") {
                    val normalized = arrayBuiltinIdentifier(expression) as SExpr.SList
                    val sort = smtIndexedBitVectorSort(normalized, emptyList())
                        ?: smtUnsupported("invalid indexed bitvector literal")
                    values.addLast(raw(normalized, sort))
                } else if (frame.combine) {
                    val args = List(expression.items.size - 1) { values.removeLast() }.reversed()
                    values.addLast(combine(expression.items[0], args))
                } else {
                    work.addLast(Frame(expression, true))
                    for (argument in expression.items.drop(1).asReversed()) work.addLast(Frame(argument, false))
                }
            }
        }
        return values.single()
    }

    private fun combine(head: SExpr, args: List<Term>): Term {
        val resolved = arrayBuiltinIdentifier(head)
        val name = (resolved as? SExpr.Atom)?.text
        name?.let(symbols::get)?.let { binding ->
            if (args.isEmpty()) return binding
            smtUnsupported("a constant cannot be applied to arguments")
        }
        return when {
            name == "select" -> read(args[0], capture(args[1]))
            name == "store" -> {
                val index = capture(args[1])
                registerIndex(index, args[0].sort as SmtSort.Array)
                array(resolved, listOf(args[0], index, capture(args[2])), args[0].sort as SmtSort.Array, "store")
            }
            name == "ite" && args[1].sort is SmtSort.Array -> array(resolved,
                listOf(capture(args[0]), args[1], args[2]), args[1].sort as SmtSort.Array, "ite")
            name in setOf("=", "distinct") && args[0].sort is SmtSort.Array -> {
                val comparisons = if (name == "=") args.zipWithNext().map { equal(it.first, it.second) }
                else args.indices.flatMap { i ->
                    (i + 1 until args.size).map { j -> form("not", equal(args[i], args[j])) }
                }
                intern(SExpr.Atom("and"), comparisons.map { raw(it, SmtSort.Bool) }, SmtSort.Bool)
            }
            else -> intern(resolved, args, resultSort(resolved, args.map { it.sort }))
        }
    }

    private fun array(head: SExpr, args: List<Term>, sort: SmtSort.Array, operator: String): Term {
        val key = Node(head, args.map { it.id })
        nodes[key]?.let { return it }
        val term = fresh(sort)
        arrays[term.id] = ArrayTerm(term, operator, args)
        nodes[key] = term
        return term
    }

    private fun registerIndex(index: Term, sort: SmtSort.Array) {
        indexes.getOrPut(sort) { LinkedHashMap() }[index.id] = index
    }

    private fun read(array: Term, index: Term): Term {
        registerIndex(index, array.sort as SmtSort.Array)
        val key = PairKey(array.id, index.id)
        return reads.getOrPut(key) {
            step()
            val element = (array.sort as SmtSort.Array).element
            val value = fresh(element)
            if (element is SmtSort.Array) arrays[value.id] = ArrayTerm(value, "base", emptyList())
            Read(array, index, value)
        }.value
    }

    private fun equal(left: Term, right: Term): SExpr {
        if (left.id == right.id) return SExpr.Atom("true")
        val key = if (left.id < right.id) PairKey(left.id, right.id) else PairKey(right.id, left.id)
        return SExpr.Atom(equalities.getOrPut(key) {
            step()
            val flag = fresh(SmtSort.Bool)
            val witness = if (left.sort is SmtSort.Array) fresh(left.sort.index).also {
                if (it.sort is SmtSort.Array) arrays[it.id] = ArrayTerm(it, "base", emptyList())
                registerIndex(it, left.sort)
            } else null
            if (witness == null) {
                axioms += assertion(form("=", flag.expression, form("=", left.expression, right.expression)))
            }
            Equality(left, right, flag.expressionName(), witness)
        }.name)
    }

    private fun close() {
        var previous = -1
        while (previous != arrays.size + reads.size + equalities.size) {
            previous = arrays.size + reads.size + equalities.size
            for (array in arrays.values.toList()) {
                val sort = array.term.sort as SmtSort.Array
                default(sort.element)
                for (index in indexes[sort]?.values?.toList().orEmpty()) read(array.term, index)
            }
            for (read in reads.values.toList()) {
                step()
                if (!processedReads.add(PairKey(read.array.id, read.index.id))) continue
                val array = arrays.getValue(read.array.id)
                when (array.operator) {
                    "store" -> {
                        val same = equal(read.index, array.arguments[1])
                        val base = read(array.arguments[0], read.index)
                        axioms += assertion(form("ite", same,
                            equal(read.value, array.arguments[2]), equal(read.value, base)))
                    }
                    "ite" -> axioms += assertion(form("ite", array.arguments[0].expression,
                        equal(read.value, read(array.arguments[1], read.index)),
                        equal(read.value, read(array.arguments[2], read.index))))
                }
            }
            for (equality in equalities.values.toList()) {
                val witness = equality.witness ?: continue
                val flag = SExpr.Atom(equality.name)
                if (processedWitnesses.add(equality.name)) axioms += assertion(form("=>", form("not", flag), form("not",
                    equal(read(equality.left, witness), read(equality.right, witness)))))
                for (index in indexes[equality.left.sort]?.values?.toList().orEmpty()) {
                    step()
                    if (processedAgreements.add(equality.name to index.id)) {
                        axioms += assertion(form("=>", flag,
                            equal(read(equality.left, index), read(equality.right, index))))
                    }
                }
            }
            val readList = reads.values.toList()
            for (i in readList.indices) {
                for (j in i + 1 until readList.size) {
                    step()
                    val left = readList[i]
                    val right = readList[j]
                    if (left.array.id == right.array.id &&
                        processedCongruences.add(PairKey(left.value.id, right.value.id))) {
                        axioms += assertion(form("=>", equal(left.index, right.index), equal(left.value, right.value)))
                    }
                }
            }
        }
    }

    private fun default(sort: SmtSort): Term = defaults.getOrPut(sort) {
        fresh(sort).also { if (sort is SmtSort.Array) arrays[it.id] = ArrayTerm(it, "base", emptyList()) }
    }

    private fun capture(term: Term): Term {
        if (term.sort is SmtSort.Array) return term
        val atom = term.expression as? SExpr.Atom
        if (atom != null && !arrayLexicalLiteral(atom.text) && polynomialSymbol(atom.text) in symbols) return term
        return captures.getOrPut(term.id) {
            fresh(term.sort).also { axioms += assertion(form("=", it.expression, term.expression)) }
        }
    }

    private fun fresh(sort: SmtSort): Term {
        var name: String
        do {
            name = "@klause.array.${nextName++}"
        } while (!usedNames.add(name))
        val term = atom(name, sort)
        if (sort !is SmtSort.Array) {
            output.add(queryPosition ?: output.size, form("declare-const", term.expression, sortSyntax(sort)))
            queryPosition = queryPosition?.plus(1)
            privateBindings[name] = SmtReconstruction.Scalar(name, sort)
        }
        symbols[polynomialSymbol(name)] = term
        return term
    }

    private fun literal(expression: SExpr.Atom): Term {
        val text = expression.text
        val sort = smtBitVectorLiteralSort(text) ?: when {
            text in setOf("true", "false") -> SmtSort.Bool
            text == "pi" || '.' in text -> SmtSort.Real
            text.all { it in '0'..'9' } -> SmtSort.Int
            else -> smtUnsupported("unbound array-lowering symbol '$text'")
        }
        return atom(text, sort)
    }

    private fun atom(name: String, sort: SmtSort): Term = atoms.getOrPut(
        (if (arrayLexicalLiteral(name)) "literal:$name" else "symbol:${polynomialSymbol(name)}") to sort,
    ) {
        raw(SExpr.Atom(name), sort)
    }
    private fun raw(expression: SExpr, sort: SmtSort): Term = Term(nextTerm++, sort, expression)
    private fun intern(head: SExpr, arguments: List<Term>, sort: SmtSort): Term =
        nodes.getOrPut(Node(head, arguments.map { it.id })) {
            raw(SExpr.SList(listOf(head) + arguments.map { it.expression }), sort)
        }
    private fun Term.expressionName(): String = (expression as SExpr.Atom).text
    private fun step() = cancellation.polynomialSourceCheckpoint(1)

    private fun append(expression: SExpr) {
        if (queryPosition == null && (expression as? SExpr.SList)?.polynomialHead() in
            setOf("check-sat", "check-sat-assuming", "get-model", "get-value", "exit")) queryPosition = output.size
        output += expression
    }

    private fun collectNames() {
        val work = ArrayDeque<SExpr>()
        input.commands.forEach(work::addLast)
        while (work.isNotEmpty()) {
            step()
            when (val node = work.removeLast()) {
                is SExpr.Atom -> usedNames += polynomialSymbol(node.text)
                is SExpr.SList -> node.items.forEach(work::addLast)
            }
        }
    }
}

private data class SmtArrayReconstruction(val sort: SmtSort.Array, val default: String,
    val entries: List<Pair<String, String>>) : SmtReconstruction {
    override fun reconstruct(values: SmtModelValues): SmtValue {
        values.checkpoint()
        return SmtValue.Array(sort, values.value(default), entries.map { (index, value) ->
            values.checkpoint()
            values.value(index) to values.value(value)
        })
    }
}

private data class SmtArrayEqualityCheck(val flag: String, val left: String, val right: String) : SmtSourceCheck {
    override fun check(values: SmtModelValues): Boolean = values.boolean(flag) ==
        (values.equal(left, right) ?: throw SmtSourceCheckingUnavailable())
}

private data class SmtArrayReadCheck(val array: String, val index: String, val value: String) : SmtSourceCheck {
    override fun check(values: SmtModelValues): Boolean {
        val selected = smtArraySelect(values.value(array) as SmtValue.Array, values.value(index), values)
            ?: throw SmtSourceCheckingUnavailable()
        return values.equal(selected, values.value(value)) ?: throw SmtSourceCheckingUnavailable()
    }
}

private data class SmtArrayOperationCheck(val result: String, val operator: String,
    val arguments: List<String>) : SmtSourceCheck {
    override fun check(values: SmtModelValues): Boolean {
        val expected = if (operator == "store") {
            val base = values.value(arguments[0]) as SmtValue.Array
            base.copy(entries = base.entries + (values.value(arguments[1]) to values.value(arguments[2])))
        } else values.value(arguments[if (values.boolean(arguments[0])) 1 else 2])
        return values.equal(values.value(result), expected) ?: throw SmtSourceCheckingUnavailable()
    }
}

private fun resultSort(head: SExpr, args: List<SmtSort>): SmtSort {
    smtBitVectorBuiltinSort(head, args)?.let { return it }
    val name = (head as? SExpr.Atom)?.text
    if (head is SExpr.SList) {
        if (head.polynomialAtom(1) == "divisible") return SmtSort.Bool
        smtUnsupported("unsupported indexed scalar expression")
    }
    return when (name) {
        "=", "distinct", "and", "or", "xor", "not", "=>", "<", "<=", ">", ">=", "is_int" -> SmtSort.Bool
        "ite" -> if (SmtSort.Real in args.drop(1)) SmtSort.Real else args[1]
        "to_int", "div", "mod" -> SmtSort.Int
        "to_real", "/", "exp", "log", "sin", "cos", "pi" -> SmtSort.Real
        else -> if (SmtSort.Real in args) SmtSort.Real else args.firstOrNull() ?: SmtSort.Int
    }
}

private fun arrayLexicalLiteral(text: String): Boolean {
    if (text in setOf("true", "false")) return true
    if (text.startsWith('#')) return true
    return text.isNotEmpty() && text[0] in '0'..'9'
}

private fun arrayBuiltinIdentifier(head: SExpr): SExpr = when (head) {
    is SExpr.Atom -> SExpr.Atom(polynomialSymbol(head.text))
    is SExpr.SList -> if ((head.items.firstOrNull() as? SExpr.Atom)?.text == "as")
        arrayBuiltinIdentifier(head.items[1])
    else SExpr.SList(head.items.mapIndexed { index, part ->
        if (index <= 1 && part is SExpr.Atom) SExpr.Atom(polynomialSymbol(part.text)) else part
    })
}

private fun form(head: String, vararg args: SExpr): SExpr.SList = form(SExpr.Atom(head), *args)
private fun form(head: SExpr, vararg args: SExpr): SExpr.SList = SExpr.SList(listOf(head) + args)
private fun assertion(expression: SExpr): SExpr.SList = form("assert", expression)
private fun sortSyntax(sort: SmtSort): SExpr = SExprReader(sort.toSmtLib()).readAll().single()
