package com.eignex.klause.formats.smtlib

import com.eignex.klause.lowering.polynomialSourceCheckpoint
import com.eignex.klause.util.ExecutionControl

internal class SmtLibScope(private val cancellation: ExecutionControl,
    private val allowSolverSymbols: Boolean = false) {
    private class Signature(val parameters: List<SmtSort>, val result: SmtSort, val identity: Int)
    private class Locals(val values: Map<String, Int>, val parent: Locals?) {
        fun get(name: String): Int? {
            var scope: Locals? = this
            while (scope != null) {
                scope.values[name]?.let { return it }
                scope = scope.parent
            }
            return null
        }
    }
    private sealed interface Frame {
        class Evaluate(val expression: SExpr, val locals: Locals?) : Frame
        class Combine(val head: String, val count: Int, val signature: Signature?,
            val qualification: SmtSort? = null, val indexed: SExpr.SList? = null) : Frame
        class Let(val names: List<String>, val body: SExpr, val locals: Locals?) : Frame
    }
    private val globals = HashMap<String, Signature>()
    private val globalSortParameters = LinkedHashSet<String>()
    val sortParameters: Set<String> get() = globalSortParameters.toSet()
    private val typedSymbols = ArrayList<SmtTypedSymbol>()
    val symbols: List<SmtTypedSymbol> get() = typedSymbols.toList()
    private val sourceObligations = ArrayList<Pair<String, Int>>()
    val obligations: List<Pair<String, Int>> get() = sourceObligations.toList()
    private val freeSorts = HashMap<String, Int>()
    private val freeSortNames = HashMap<String, String>()
    private data class SortAlias(val parameters: List<String>, val body: SmtSort)
    private val sortAliases = HashMap<String, SortAlias>()
    private var sortParameterIdentity = 0
    private val originalDeclarations = ArrayList<SmtSourceDeclaration>()
    val declarations: List<SmtSourceDeclaration> get() = originalDeclarations.toList()
    private val nodes = ArrayList<SmtTypedTerm>()
    private val interned = HashMap<SmtTypedTerm, Int>()
    private val assertionNodes = ArrayList<Int>()
    private var symbolIdentity = 0
    val terms: List<SmtTypedTerm> get() = nodes.toList()
    val assertions: List<Int> get() = assertionNodes.toList()

    private fun term(operator: String, arguments: List<Int>, sort: SmtSort): Int {
        val node = SmtTypedTerm(operator, arguments, sort)
        return interned.getOrPut(node) {
            cancellation.polynomialSourceCheckpoint(1)
            nodes.add(node)
            nodes.lastIndex
        }
    }
    private var querySeen = false
    private var exited = false

    fun command(expression: SExpr): Boolean {
        val command = expression as? SExpr.SList ?: return false
        val head = command.polynomialHead()
        if (exited || (querySeen && head !in setOf("get-model", "get-objectives", "exit", "set-info", "set-option"))) {
            return false
        }
        return when (head) {
            "declare-sort-parameter" -> {
                command.polynomialSize(2)
                val name = command.polynomialName(1)
                if (name in freeSorts || name in sortAliases || name in globalSortParameters ||
                    theorySort(name) || reservedName(name)) return false
                globalSortParameters += name
                true
            }
            "declare-sort" -> {
                command.polynomialSize(3)
                val name = command.polynomialName(1)
                val arity = command.polynomialAtom(2).toIntOrNull() ?: return false
                if (arity < 0 || name in freeSorts || name in sortAliases ||
                    name in globalSortParameters || theorySort(name) || reservedName(name)) {
                    false
                } else {
                    freeSorts[name] = arity
                    freeSortNames[name] = (command.items[1] as SExpr.Atom).text
                    true
                }
            }
            "define-sort" -> {
                command.polynomialSize(4)
                val name = command.polynomialName(1)
                if (name in freeSorts || name in sortAliases || name in globalSortParameters ||
                    theorySort(name) || reservedName(name)) return false
                val parameters = (command.items[2] as? SExpr.SList)?.items?.map {
                    (it as? SExpr.Atom)?.text?.let(::polynomialSymbol) ?: return false
                } ?: return false
                if (parameters.toSet().size != parameters.size || parameters.any {
                        theorySort(it) || reservedName(it)
                    }) return false
                val bindings = parameters.associateWith { aliasParameter() }
                val body = sourceSort(command.items[3], parameters.toSet(),
                    allowGlobalParameters = false, localBindings = bindings)
                sortAliases[name] = SortAlias(bindings.values.map { it.name }, body)
                true
            }
            "declare-const", "declare-fun" -> {
                command.polynomialSize(if (head == "declare-const") 3 else 4)
                val parameters = if (head == "declare-fun") {
                    val arguments = command.items[2] as? SExpr.SList ?: return false
                    arguments.items.map(::sourceSort)
                } else emptyList()
                val name = command.polynomialName(1)
                val result = sourceSort(command.items[if (head == "declare-const") 2 else 3])
                if (name in globals || theoryFunction(name) || reservedName(name)) {
                    smtUnsupported("duplicate declaration of '$name'")
                } else {
                    globals[name] = Signature(parameters, result, symbolIdentity++)
                    val spelling = (command.items[1] as SExpr.Atom).text
                    val declaration = SmtSourceDeclaration(spelling, parameters, result)
                    originalDeclarations += declaration
                    typedSymbols += SmtTypedSymbol(globals.getValue(name).identity, declaration)
                    true
                }
            }
            "define-fun" -> {
                command.polynomialSize(5)
                val name = command.polynomialName(1)
                if (name in globals || theoryFunction(name) || reservedName(name)) return false
                val parameters = command.items.getOrNull(2) as? SExpr.SList ?: return false
                val names = ArrayList<String>()
                val uniqueNames = HashSet<String>()
                val sorts = ArrayList<SmtSort>()
                for (parameter in parameters.items) {
                    cancellation.polynomialSourceCheckpoint(1)
                    val pair = parameter as? SExpr.SList ?: return false
                    if (pair.items.size != 2) return false
                    val parameterName = pair.polynomialName(0)
                    if (!uniqueNames.add(parameterName) || theoryFunction(parameterName) ||
                        reservedName(parameterName)) return false
                    names += parameterName
                    sorts += sourceSort(pair.items[1])
                }
                val result = sourceSort(command.items[3])
                val locals = names.indices.associate { index ->
                    names[index] to term("parameter:${symbolIdentity++}", emptyList(), sorts[index])
                }
                val body = sort(command.items.getOrNull(4) ?: return false, Locals(locals, null)) ?: return false
                if (!accepts(result, nodes[body].sort)) return false
                val identity = symbolIdentity++
                globals[name] = Signature(sorts, result, identity)
                typedSymbols += SmtTypedSymbol(identity,
                    SmtSourceDeclaration((command.items[1] as SExpr.Atom).text, sorts, result),
                    SmtTypedDefinition(names.map { locals.getValue(it) }, body))
                true
            }
            "assert", "minimize", "maximize" -> {
                command.polynomialSize(2)
                val result = sort(command.items[1], null) ?: return false
                sourceObligations += head to result
                if (head == "assert") {
                    assertionNodes += result
                    nodes[result].sort == SmtSort.Bool
                } else numeric(nodes[result].sort)
            }
            "set-logic" -> {
                command.polynomialSize(2)
                command.polynomialName(1)
                true
            }
            "check-sat" -> {
                command.polynomialSize(1)
                querySeen = true
                true
            }
            "check-sat-assuming" -> {
                command.polynomialSize(2)
                val assumptions = command.items[1] as? SExpr.SList ?: return false
                for (assumption in assumptions.items) {
                    val atom = if (assumption is SExpr.SList) {
                        if (assumption.items.size != 2 || assumption.polynomialHead() != "not") return false
                        assumption.items[1] as? SExpr.Atom ?: return false
                    } else assumption as? SExpr.Atom ?: return false
                    if (globals[polynomialSymbol(atom.text)]?.let {
                            it.parameters.isEmpty() && it.result == SmtSort.Bool
                        } != true) return false
                    val node = sort(assumption, null) ?: return false
                    assertionNodes += node
                    sourceObligations += "assert" to node
                }
                querySeen = true
                true
            }
            "exit" -> {
                command.polynomialSize(1)
                exited = true
                true
            }
            "get-model", "get-objectives" -> {
                command.polynomialSize(1)
                true
            }
            "set-info", "set-option" -> {
                command.polynomialSize(3)
                true
            }
            else -> smtUnsupported("unsupported command '$head'")
        }
    }

    internal fun sourceSort(expression: SExpr, localParameters: Set<String> = emptySet(),
        allowGlobalParameters: Boolean = true, localBindings: Map<String, SmtSort> = emptyMap()): SmtSort {
        val work = ArrayDeque<Pair<SExpr, Boolean>>()
        val values = ArrayDeque<SmtSort>()
        work.addLast(expression to false)
        while (work.isNotEmpty()) {
            cancellation.polynomialSourceCheckpoint(1)
            val (node, combine) = work.removeLast()
            when (node) {
                is SExpr.Atom -> values.addLast(when (polynomialSymbol(node.text)) {
                    "Bool" -> SmtSort.Bool
                    "Int" -> SmtSort.Int
                    "Real" -> SmtSort.Real
                    else -> {
                        val name = polynomialSymbol(node.text)
                        val alias = sortAliases[name]
                        when {
                            name in localBindings -> localBindings.getValue(name)
                            name in localParameters || (allowGlobalParameters && name in globalSortParameters) ->
                                SmtSort.Uninterpreted(name)
                            alias != null && alias.parameters.isEmpty() -> alias.body
                            freeSorts[name] == 0 -> SmtSort.Uninterpreted(freeSortNames.getValue(name))
                            else -> smtUnsupported("unsupported sort '$name'")
                        }
                    }
                })
                is SExpr.SList -> {
                    val head = node.polynomialHead()
                    if ((node.items.firstOrNull() as? SExpr.Atom)?.text == "_") {
                        node.polynomialSize(3)
                        if (node.polynomialAtom(1) != "BitVec") smtUnsupported("unsupported indexed source sort")
                        val widthText = (node.items[2] as? SExpr.Atom)?.text
                            ?: smtUnsupported("invalid bitvector width")
                        if (widthText.isEmpty() || widthText.any { it !in '0'..'9' }) {
                            smtUnsupported("invalid bitvector width")
                        }
                        val width = widthText.toIntOrNull()
                        if (width == null || width <= 0) smtUnsupported("invalid bitvector width")
                        values.addLast(SmtSort.BitVector(width))
                    } else if (combine) {
                        val args = List(node.items.size - 1) { values.removeLast() }.reversed()
                        val alias = sortAliases[head]
                        values.addLast(when {
                            head == "Array" -> SmtSort.Array(args[0], args[1])
                            alias != null -> substituteSort(alias.body, alias.parameters.zip(args).toMap())
                            else -> SmtSort.Uninterpreted(freeSortNames.getValue(polynomialSymbol(head)), args)
                        })
                    } else {
                        if (head in localParameters || head in localBindings) {
                            smtUnsupported("sort parameter '$head' takes no arguments")
                        }
                        val arity = if (head == "Array") 2 else sortAliases[head]?.parameters?.size ?: freeSorts[head]
                        if (arity == null || node.items.size != arity + 1) smtUnsupported("invalid source sort '$head'")
                        work.addLast(node to true)
                        for (argument in node.items.drop(1).asReversed()) work.addLast(argument to false)
                    }
                }
            }
        }
        return values.single()
    }

    private fun aliasParameter(): SmtSort.Uninterpreted {
        var name: String
        do {
            cancellation.polynomialSourceCheckpoint(1)
            name = ".klause.alias.parameter.${sortParameterIdentity++}"
        } while (name in freeSorts || name in sortAliases || name in globalSortParameters)
        return SmtSort.Uninterpreted(name)
    }

    private fun substituteSort(root: SmtSort, substitutions: Map<String, SmtSort>): SmtSort {
        val work = ArrayDeque<Pair<SmtSort, Boolean>>()
        val values = ArrayDeque<SmtSort>()
        work.addLast(root to false)
        while (work.isNotEmpty()) {
            cancellation.polynomialSourceCheckpoint(1)
            val (sort, combine) = work.removeLast()
            when (sort) {
                is SmtSort.Array -> if (combine) {
                    val element = values.removeLast()
                    values.addLast(SmtSort.Array(values.removeLast(), element))
                } else {
                    work.addLast(sort to true)
                    work.addLast(sort.element to false)
                    work.addLast(sort.index to false)
                }
                is SmtSort.Uninterpreted -> if (sort.arguments.isEmpty()) {
                    values.addLast(substitutions[polynomialSymbol(sort.name)] ?: sort)
                } else if (combine) {
                    val args = List(sort.arguments.size) { values.removeLast() }.reversed()
                    values.addLast(SmtSort.Uninterpreted(sort.name, args))
                } else {
                    work.addLast(sort to true)
                    for (argument in sort.arguments.asReversed()) work.addLast(argument to false)
                }
                else -> values.addLast(sort)
            }
        }
        return values.single()
    }

    private fun theorySort(name: String): Boolean = name in setOf("Bool", "Int", "Real", "Array", "BitVec")

    private fun sort(root: SExpr, locals: Locals?): Int? {
        val work = ArrayDeque<Frame>()
        val values = ArrayDeque<Int>()
        work.addLast(Frame.Evaluate(root, locals))
        fun pop(count: Int): List<Int> = List(count) { values.removeLast() }.reversed()
        while (work.isNotEmpty()) {
            cancellation.polynomialSourceCheckpoint(1)
            when (val frame = work.removeLast()) {
                is Frame.Evaluate -> when (val node = frame.expression) {
                    is SExpr.Atom -> {
                        val name = polynomialSymbol(node.text)
                        val result = literal(node.text)?.let {
                            term("literal:${if (it == SmtSort.Bool) name else node.text}", emptyList(), it)
                        }
                            ?: frame.locals?.get(name) ?: globals[name]?.takeIf { it.parameters.isEmpty() }
                                ?.let { term("symbol:${it.identity}:$name", emptyList(), it.result) }
                            ?: if (node.text == "pi") term("pi", emptyList(), SmtSort.Real) else return null
                        values.addLast(result)
                    }
                    is SExpr.SList -> {
                        val identifier = node.items.firstOrNull() ?: return null
                        val qualified = (identifier as? SExpr.SList)?.takeIf {
                            (it.items.firstOrNull() as? SExpr.Atom)?.text == "as"
                        }
                        if (qualified != null && qualified.items.size != 3) return null
                        val qualification = qualified?.let { sourceSort(it.items[2]) }
                        val selected = qualified?.items?.get(1) ?: identifier
                        val indexed = selected as? SExpr.SList
                        if (indexed != null) {
                            val key = indexed.items.map { (it as? SExpr.Atom)?.text ?: return null }.joinToString(" ")
                            work.addLast(Frame.Combine("($key)", node.items.size - 1, null, qualification, indexed))
                            for (argument in node.items.drop(1).asReversed()) {
                                work.addLast(Frame.Evaluate(argument, frame.locals))
                            }
                            continue
                        }
                        val syntax = (selected as? SExpr.Atom)?.text ?: return null
                        val head = polynomialSymbol(syntax)
                        if (syntax == "as" && qualified == null) {
                            if (node.items.size != 3) return null
                            val selectedIndex = node.items[1] as? SExpr.SList
                            if (selectedIndex != null) {
                                val type = smtIndexedBitVectorSort(selectedIndex, emptyList()) ?: return null
                                if (type != sourceSort(node.items[2])) return null
                                val key = selectedIndex.items.map { (it as? SExpr.Atom)?.text ?: return null }
                                    .joinToString(" ")
                                values.addLast(term("($key)", emptyList(), type))
                                continue
                            }
                            val name = node.polynomialName(1)
                            val signature = globals[name]
                            val result = signature?.takeIf { it.parameters.isEmpty() }?.let {
                                instantiateSmtRank(it.parameters, it.result, globalSortParameters, emptyList(),
                                    sourceSort(node.items[2]), cancellation)?.second
                            }
                                ?: literal(name)?.takeIf { name in setOf("true", "false") }
                                ?: if (name == "pi") SmtSort.Real else return null
                            if (result != sourceSort(node.items[2])) return null
                            values.addLast(term(if (signature == null) name else "symbol:${signature.identity}:$name",
                                emptyList(), result))
                        } else if (syntax == "_") {
                            val type = smtIndexedBitVectorSort(node, emptyList()) ?: return null
                            val key = node.items.map { (it as? SExpr.Atom)?.text ?: return null }.joinToString(" ")
                            values.addLast(term("($key)", emptyList(), type))
                        } else if (syntax == "!") {
                            if (node.items.size < 2) return null
                            work.addLast(Frame.Evaluate(node.items[1], frame.locals))
                        } else if (syntax == "let") {
                            if (node.items.size != 3) return null
                            val bindings = node.items[1] as? SExpr.SList ?: return null
                            val names = ArrayList<String>()
                            val uniqueNames = HashSet<String>()
                            val arguments = ArrayList<SExpr>()
                            for (binding in bindings.items) {
                                cancellation.polynomialSourceCheckpoint(1)
                                val pair = binding as? SExpr.SList ?: return null
                                if (pair.items.size != 2) return null
                                val name = pair.polynomialName(0)
                                if (!uniqueNames.add(name) || theoryFunction(name) || reservedName(name)) return null
                                names += name
                                arguments += pair.items[1]
                            }
                            work.addLast(Frame.Let(names, node.items[2], frame.locals))
                            for (argument in arguments.asReversed()) {
                                work.addLast(Frame.Evaluate(argument, frame.locals))
                            }
                        } else {
                            val name = polynomialSymbol(head)
                            if (frame.locals?.get(name) != null) return null
                            val signature = globals[name]
                            work.addLast(Frame.Combine(head, node.items.size - 1, signature, qualification))
                            for (argument in node.items.drop(1).asReversed()) {
                                work.addLast(Frame.Evaluate(argument, frame.locals))
                            }
                        }
                    }
                }
                is Frame.Let -> {
                    val bindings = frame.names.zip(pop(frame.names.size)).toMap()
                    work.addLast(Frame.Evaluate(frame.body, Locals(bindings, frame.locals)))
                }
                is Frame.Combine -> {
                    val arguments = pop(frame.count)
                    val args = arguments.map { nodes[it].sort }
                    val signature = frame.signature
                    val result = if (frame.indexed != null) smtIndexedBitVectorSort(frame.indexed, args)
                    else if (signature == null) operator(polynomialSymbol(frame.head), args) else {
                        instantiateSmtRank(signature.parameters, signature.result, globalSortParameters, args,
                            frame.qualification, cancellation)?.second ?: return null
                    }
                    val inferred = result ?: return null
                    val type = if (signature == null && frame.qualification == SmtSort.Real &&
                        inferred == SmtSort.Int && frame.head in setOf("+", "-", "*", "abs", "^", "ite")
                    ) SmtSort.Real else inferred
                    if (frame.qualification != null && frame.qualification != type) return null
                    val operator = if (signature == null) frame.head else "symbol:${signature.identity}:${frame.head}"
                    values.addLast(term(operator, arguments, type))
                }
            }
        }
        return values.single()
    }

    private fun literal(text: String): SmtSort? {
        if (polynomialSymbol(text) in setOf("true", "false")) return SmtSort.Bool
        smtBitVectorLiteralSort(text)?.let { return it }
        if (text.isNotEmpty() && text.all { it in '0'..'9' }) return SmtSort.Int
        val dot = text.indexOf('.')
        return if (dot > 0 && dot < text.lastIndex && text.indices.all { it == dot || text[it] in '0'..'9' }) {
            SmtSort.Real
        } else null
    }

    private fun operator(head: String, args: List<SmtSort>): SmtSort? = when (head) {
        "+", "*" -> numericResult(args)
        "-" -> if (args.isEmpty()) null else numericResult(args)
        "/" -> if (args.size >= 2 && args.all(::numeric)) SmtSort.Real else null
        "^" -> if (args.size == 2 && numeric(args[0]) && args[1] == SmtSort.Int) args[0] else null
        "and", "or" -> if (args.all { it == SmtSort.Bool }) SmtSort.Bool else null
        "xor", "=>" -> if (args.size >= 2 && args.all { it == SmtSort.Bool }) SmtSort.Bool else null
        "not" -> if (args == listOf(SmtSort.Bool)) SmtSort.Bool else null
        "=", "distinct" -> if (args.size >= 2 &&
            (args.all { it == args[0] } || args.all(::numeric))) SmtSort.Bool else null
        "<", "<=", ">", ">=" -> if (args.size >= 2 && args.all(::numeric)) SmtSort.Bool else null
        "ite" -> {
            if (args.size != 3) smtUnsupported("ite expects 3 arguments")
            if (args[0] != SmtSort.Bool) null
            else if (args[1] == args[2]) args[1] else numericResult(args.drop(1))
        }
        "to_real" -> if (args == listOf(SmtSort.Int)) SmtSort.Real else null
        "to_int" -> if (args.size == 1 && numeric(args[0])) SmtSort.Int else null
        "is_int" -> if (args.size == 1 && numeric(args[0])) SmtSort.Bool else null
        "abs" -> if (args.size == 1 && numeric(args[0])) args[0] else null
        "div", "mod" -> if (args == listOf(SmtSort.Int, SmtSort.Int)) SmtSort.Int else null
        "exp", "log", "sin", "cos" -> if (args.size == 1 && numeric(args[0])) SmtSort.Real else null
        "pi" -> if (args.isEmpty()) SmtSort.Real else null
        "select" -> (args.firstOrNull() as? SmtSort.Array)?.let {
            if (args.size == 2 && args[1] == it.index) it.element else null
        }
        "store" -> (args.firstOrNull() as? SmtSort.Array)?.let {
            if (args.size == 3 && args[1] == it.index && args[2] == it.element) it else null
        }
        else -> smtBitVectorOperatorSort(head, args)
    }

    private fun numeric(sort: SmtSort): Boolean = sort == SmtSort.Int || sort == SmtSort.Real
    private fun numericResult(args: List<SmtSort>): SmtSort? = if (!args.all(::numeric)) null
        else if (SmtSort.Real in args) SmtSort.Real else SmtSort.Int
    private fun accepts(expected: SmtSort, actual: SmtSort): Boolean = expected == actual ||
        (expected == SmtSort.Real && actual == SmtSort.Int)

    private fun reservedName(name: String): Boolean = !allowSolverSymbols &&
        (name.startsWith('@') || name.startsWith('.'))

    private fun theoryFunction(name: String): Boolean = name in setOf(
        "true", "false", "not", "and", "or", "xor", "=>", "=", "distinct", "ite",
        "+", "-", "*", "/", "div", "mod", "abs", "**", "<", "<=", ">", ">=", "to_real", "to_int", "is_int",
        "select", "store", "concat", "bvnot", "bvneg", "bvand", "bvor", "bvxor", "bvxnor", "bvnand", "bvnor",
        "bvcomp", "bvadd", "bvsub", "bvmul", "bvudiv", "bvurem", "bvsdiv", "bvsrem", "bvsmod", "bvshl",
        "bvlshr", "bvashr", "bvult", "bvule", "bvugt", "bvuge", "bvslt", "bvsle", "bvsgt", "bvsge",
        "ubv_to_int", "sbv_to_int", "bvnego", "bvuaddo", "bvsaddo", "bvumulo", "bvsmulo", "bvusubo", "bvssubo",
        "bvsdivo",
    )
}

internal fun SExpr.factorSourceSymbols(cancellation: ExecutionControl): SExpr {
    val work = ArrayDeque<Pair<SExpr, Boolean>>()
    val values = ArrayDeque<SExpr>()
    work.addLast(this to false)
    val punctuation = "~!@$%^&*_+=<>.?/-"
    fun initial(character: Char): Boolean = character in 'a'..'z' || character in 'A'..'Z' || character in punctuation
    while (work.isNotEmpty()) {
        cancellation.polynomialSourceCheckpoint(1)
        val (node, combine) = work.removeLast()
        when (node) {
            is SExpr.Atom -> {
                val name = polynomialSymbol(node.text)
                val simple = name.isNotEmpty() && initial(name[0]) &&
                    name.all { initial(it) || it in '0'..'9' }
                values.addLast(if (simple && name != node.text) SExpr.Atom(name) else node)
            }
            is SExpr.SList -> if (combine) {
                val children = List(node.items.size) { values.removeLast() }.reversed()
                val unchanged = children.indices.all { children[it] === node.items[it] }
                values.addLast(if (unchanged) node else SExpr.SList(children))
            } else {
                work.addLast(node to true)
                for (child in node.items.asReversed()) work.addLast(child to false)
            }
        }
    }
    return values.single()
}
