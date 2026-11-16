package com.eignex.klause.formats.smtlib

import com.eignex.klause.lowering.polynomialSourceCheckpoint
import com.eignex.klause.util.Cancellation

internal fun reduceSmtGroundArrays(input: SmtReduction, cancellation: Cancellation): SmtReduction {
    val sorts = input.declarations.flatMap { it.parameters + it.result }
    return if (sorts.any { it is SmtSort.Array }) SmtGroundArrayReducer(input, cancellation).reduce() else input
}

private class SmtGroundArrayReducer(private val input: SmtReduction, private val cancellation: Cancellation) {
    private data class Term(val id: Int, val sort: SmtSort, val expression: SExpr)
    private data class Node(val head: SExpr, val arguments: List<Int>)
    private data class ArrayTerm(val term: Term, val operator: String, val arguments: List<Term>)
    private data class PairKey(val first: Int, val second: Int)
    private data class Equality(val left: Term, val right: Term, val name: String, val witness: Term?)
    private data class Read(val array: Term, val index: Term, val value: Term)
    private data class Frame(val expression: SExpr, val combine: Boolean)

    private val output = ArrayList<SExpr>()
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
    private val sourceBindings = input.bindings.associateByTo(LinkedHashMap()) { polynomialSymbol(it.declaration.name) }
    private val privateBindings = input.privateBindings.toMutableMap()
    private val usedNames = HashSet<String>()
    private var nextTerm = 0
    private var nextName = 0

    fun reduce(): SmtReduction {
        collectNames()
        for (expression in input.commands) {
            step()
            val command = expression as SExpr.SList
            when (command.polynomialHead()) {
                "declare-const", "declare-fun" -> declare(command)
                "assert", "minimize", "maximize" -> output += form(command.items[0], rewrite(command.items[1]).expression)
                "check-sat-assuming", "get-value" -> {
                    val values = (command.items[1] as SExpr.SList).items.map { rewrite(it).expression }
                    output += form(command.items[0], SExpr.SList(values))
                }
                "define-fun" -> smtUnsupported("array reduction requires expanded ground definitions")
                else -> output += expression
            }
        }
        close()
        val allReads = reads.values.toList()
        for ((_, array) in arrays) {
            step()
            val sort = array.term.sort as SmtSort.Array
            val entries = allReads.filter { it.array.id == array.term.id }.map { it.index.expressionName() to it.value.expressionName() }
            val name = array.term.expressionName()
            val recipe = SmtArrayReconstruction(sort, entries)
            privateBindings[name] = recipe
            sourceBindings[polynomialSymbol(name)]?.let {
                sourceBindings[polynomialSymbol(name)] = it.copy(reconstruction = recipe)
            }
        }
        val query = output.indexOfFirst { (it as? SExpr.SList)?.polynomialHead() in
            setOf("check-sat", "check-sat-assuming", "get-model", "get-value", "exit") }
        output.addAll(if (query < 0) output.size else query, axioms)
        val checks = equalities.values.mapNotNull { equality ->
            if (equality.witness == null) return@mapNotNull null
            val left = equality.left.expressionName()
            val right = equality.right.expressionName()
            SmtSourceCheck { values ->
                val selected = values.boolean(equality.name)
                val a = values.value(left) as SmtValue.Array
                val b = values.value(right) as SmtValue.Array
                val positions = allReads.filter { it.array.id == equality.left.id }
                val agreements = positions.all { read ->
                    val other = reads.getValue(PairKey(equality.right.id, read.index.id))
                    encodedEqual(read.value, other.value, values)
                }
                selected == agreements && a.sort == b.sort
            }
        }
        val operationChecks = allReads.mapNotNull { read ->
            val array = arrays.getValue(read.array.id)
            when (array.operator) {
                "store" -> SmtSourceCheck { values ->
                    val expected = if (encodedEqual(read.index, array.arguments[1], values)) array.arguments[2]
                        else reads.getValue(PairKey(array.arguments[0].id, read.index.id)).value
                    encodedEqual(read.value, expected, values)
                }
                "ite" -> SmtSourceCheck { values ->
                    val branch = array.arguments[if (values.boolean(array.arguments[0].expressionName())) 1 else 2]
                    encodedEqual(read.value, reads.getValue(PairKey(branch.id, read.index.id)).value, values)
                }
                else -> null
            }
        }
        return input.copy(commands = output.toList(), bindings = sourceBindings.values.toList(),
            privateBindings = privateBindings.toMap(), checks = input.checks + checks + operationChecks)
    }

    private fun declare(command: SExpr.SList) {
        val name = command.polynomialName(1)
        val args = if (command.polynomialHead() == "declare-fun") (command.items[2] as SExpr.SList).items else emptyList()
        if (args.isNotEmpty()) smtUnsupported("array reduction requires eliminated ground function applications")
        val sort = parseSort(command.items.last())
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
                is SExpr.Atom -> values.addLast(symbols[polynomialSymbol(expression.text)] ?: literal(expression))
                is SExpr.SList -> if (expression.polynomialHead() == "_") {
                    val width = expression.polynomialAtom(2).toInt()
                    values.addLast(raw(expression, SmtSort.BitVector(width)))
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
        val name = (head as? SExpr.Atom)?.text
        return when {
            name == "select" -> read(args[0], capture(args[1]))
            name == "store" -> {
                val index = capture(args[1])
                registerIndex(index, args[0].sort as SmtSort.Array)
                array(head, listOf(args[0], index, capture(args[2])), args[0].sort as SmtSort.Array, "store")
            }
            name == "ite" && args[1].sort is SmtSort.Array -> array(head,
                listOf(capture(args[0]), args[1], args[2]), args[1].sort as SmtSort.Array, "ite")
            name in setOf("=", "distinct") && args[0].sort is SmtSort.Array -> {
                val comparisons = if (name == "=") args.zipWithNext().map { equal(it.first, it.second) }
                    else args.indices.flatMap { i -> (i + 1 until args.size).map { j -> form("not", equal(args[i], args[j])) } }
                intern(SExpr.Atom("and"), comparisons.map { raw(it, SmtSort.Bool) }, SmtSort.Bool)
            }
            else -> intern(head, args, resultSort(head, args.map { it.sort }))
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
            if (witness == null) axioms += assertion(form("=", flag.expression, form("=", left.expression, right.expression)))
            Equality(left, right, flag.expressionName(), witness)
        }.name)
    }

    private fun close() {
        var previous = -1
        while (previous != arrays.size + reads.size + equalities.size) {
            previous = arrays.size + reads.size + equalities.size
            for (array in arrays.values.toList()) {
                val sort = array.term.sort as SmtSort.Array
                for (index in indexes[sort]?.values?.toList().orEmpty()) read(array.term, index)
            }
            for (read in reads.values.toList()) {
                step()
                val array = arrays.getValue(read.array.id)
                when (array.operator) {
                        "store" -> {
                            val same = equal(read.index, array.arguments[1])
                            val base = read(array.arguments[0], read.index)
                            axioms += assertion(form("ite", same, equal(read.value, array.arguments[2]), equal(read.value, base)))
                        }
                        "ite" -> axioms += assertion(form("ite", array.arguments[0].expression,
                            equal(read.value, read(array.arguments[1], read.index)),
                            equal(read.value, read(array.arguments[2], read.index))))
                }
            }
            for (equality in equalities.values.toList()) {
                val witness = equality.witness ?: continue
                val flag = SExpr.Atom(equality.name)
                axioms += assertion(form("=>", form("not", flag), form("not",
                    equal(read(equality.left, witness), read(equality.right, witness)))))
                for (index in indexes[equality.left.sort]?.values?.toList().orEmpty()) {
                    axioms += assertion(form("=>", flag, equal(read(equality.left, index), read(equality.right, index))))
                }
            }
            val readList = reads.values.toList()
            for (i in readList.indices) for (j in i + 1 until readList.size) {
                step()
                val left = readList[i]
                val right = readList[j]
                if (left.array.id == right.array.id) axioms += assertion(form("=>", equal(left.index, right.index),
                    equal(left.value, right.value)))
            }
        }
    }

    private fun encodedEqual(left: Term, right: Term, values: SmtModelValues): Boolean {
        if (left.id == right.id) return true
        val key = if (left.id < right.id) PairKey(left.id, right.id) else PairKey(right.id, left.id)
        return values.boolean(equalities.getValue(key).name)
    }

    private fun capture(term: Term): Term {
        if (term.sort is SmtSort.Array || term.expression is SExpr.Atom &&
            polynomialSymbol(term.expression.text) in symbols) return term
        return captures.getOrPut(term.id) {
            fresh(term.sort).also { axioms += assertion(form("=", it.expression, term.expression)) }
        }
    }

    private fun fresh(sort: SmtSort): Term {
        var name: String
        do { name = "@klause.array.${nextName++}" } while (!usedNames.add(name))
        val term = atom(name, sort)
        if (sort !is SmtSort.Array) {
            val query = output.indexOfFirst { (it as? SExpr.SList)?.polynomialHead() in
                setOf("check-sat", "check-sat-assuming", "get-model", "get-value", "exit") }
            output.add(if (query < 0) output.size else query, form("declare-const", term.expression, sortSyntax(sort)))
            privateBindings[name] = SmtReconstruction.Scalar(name, sort)
        }
        symbols[polynomialSymbol(name)] = term
        return term
    }

    private fun literal(expression: SExpr.Atom): Term {
        val text = expression.text
        val sort = when {
            text in setOf("true", "false") -> SmtSort.Bool
            text.startsWith("#b") -> SmtSort.BitVector(text.length - 2)
            text.startsWith("#x") -> SmtSort.BitVector((text.length - 2) * 4)
            text == "pi" || '.' in text -> SmtSort.Real
            text.all { it in '0'..'9' } -> SmtSort.Int
            else -> smtUnsupported("unbound array-lowering symbol '$text'")
        }
        return atom(text, sort)
    }

    private fun atom(name: String, sort: SmtSort): Term = atoms.getOrPut(polynomialSymbol(name) to sort) {
        raw(SExpr.Atom(name), sort)
    }
    private fun raw(expression: SExpr, sort: SmtSort): Term = Term(nextTerm++, sort, expression)
    private fun intern(head: SExpr, arguments: List<Term>, sort: SmtSort): Term =
        nodes.getOrPut(Node(head, arguments.map { it.id })) { raw(SExpr.SList(listOf(head) + arguments.map { it.expression }), sort) }
    private fun Term.expressionName(): String = (expression as SExpr.Atom).text
    private fun step() = cancellation.polynomialSourceCheckpoint(1)

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

private data class SmtArrayReconstruction(val sort: SmtSort.Array, val entries: List<Pair<String, String>>) : SmtReconstruction {
    override fun reconstruct(values: SmtModelValues): SmtValue = SmtValue.Array(sort, smtArrayDefault(sort.element),
        entries.map { (index, value) -> values.value(index) to values.value(value) })
}

private fun smtArrayDefault(sort: SmtSort): SmtValue = when (sort) {
    SmtSort.Bool -> SmtValue.Boolean(false)
    SmtSort.Int -> SmtValue.Integer("0")
    SmtSort.Real -> SmtValue.Real("0.0")
    is SmtSort.BitVector -> SmtValue.BitVector("0".repeat(sort.width))
    is SmtSort.Uninterpreted -> SmtValue.Uninterpreted(sort, "@klause.array.default")
    is SmtSort.Array -> SmtValue.Array(sort, smtArrayDefault(sort.element), emptyList())
}

private fun parseSort(expression: SExpr): SmtSort = when (expression) {
    is SExpr.Atom -> when (expression.text) {
        "Bool" -> SmtSort.Bool
        "Int" -> SmtSort.Int
        "Real" -> SmtSort.Real
        else -> SmtSort.Uninterpreted(expression.text)
    }
    is SExpr.SList -> when (expression.polynomialHead()) {
        "Array" -> SmtSort.Array(parseSort(expression.items[1]), parseSort(expression.items[2]))
        "_" -> SmtSort.BitVector(expression.polynomialAtom(2).toInt())
        else -> SmtSort.Uninterpreted(expression.polynomialHead(), expression.items.drop(1).map(::parseSort))
    }
}

private fun resultSort(head: SExpr, args: List<SmtSort>): SmtSort {
    val name = (head as? SExpr.Atom)?.text
    if (head is SExpr.SList) {
        val operator = head.polynomialAtom(1)
        val base = args.firstOrNull() as? SmtSort.BitVector
        return when (operator) {
            "extract" -> SmtSort.BitVector(head.polynomialAtom(2).toInt() - head.polynomialAtom(3).toInt() + 1)
            "zero_extend", "sign_extend" -> SmtSort.BitVector(checkNotNull(base).width + head.polynomialAtom(2).toInt())
            "repeat" -> SmtSort.BitVector(checkNotNull(base).width * head.polynomialAtom(2).toInt())
            "int_to_bv", "int2bv" -> SmtSort.BitVector(head.polynomialAtom(2).toInt())
            else -> checkNotNull(base)
        }
    }
    return when (name) {
        "=", "distinct", "and", "or", "xor", "not", "=>", "<", "<=", ">", ">=", "is_int",
        "bvult", "bvule", "bvugt", "bvuge", "bvslt", "bvsle", "bvsgt", "bvsge",
        "bvuaddo", "bvsaddo", "bvumulo", "bvsmulo", "bvnego", "bvusubo", "bvssubo", "bvsdivo" -> SmtSort.Bool
        "ite" -> args[1]
        "to_int", "div", "mod", "ubv_to_int", "sbv_to_int", "bv2nat", "bv2int" -> SmtSort.Int
        "to_real", "/", "exp", "log", "sin", "cos", "pi" -> SmtSort.Real
        "concat" -> SmtSort.BitVector(args.sumOf { (it as SmtSort.BitVector).width })
        "bvcomp" -> SmtSort.BitVector(1)
        else -> if (SmtSort.Real in args) SmtSort.Real else args.firstOrNull() ?: SmtSort.Int
    }
}
private fun form(head: String, vararg args: SExpr): SExpr.SList = form(SExpr.Atom(head), *args)
private fun form(head: SExpr, vararg args: SExpr): SExpr.SList = SExpr.SList(listOf(head) + args)
private fun assertion(expression: SExpr): SExpr.SList = form("assert", expression)
private fun sortSyntax(sort: SmtSort): SExpr = SExprReader(sort.toSmtLib()).readAll().single()
