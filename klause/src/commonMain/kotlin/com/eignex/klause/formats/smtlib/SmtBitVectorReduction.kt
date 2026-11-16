package com.eignex.klause.formats.smtlib

import com.eignex.klause.lowering.BitVectorCircuit
import com.eignex.klause.lowering.BitVectorGates
import com.eignex.klause.lowering.polynomialSourceCheckpoint
import com.eignex.klause.util.Cancellation
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BIG_ZERO
import com.eignex.util.numbers.BigInt
import com.eignex.util.numbers.bigIntOf
import com.eignex.util.numbers.div
import com.eignex.util.numbers.magnitudeBitLength
import com.eignex.util.numbers.parseBigInt
import com.eignex.util.numbers.rem
import com.eignex.util.numbers.shl
import com.eignex.util.numbers.toLongExact

internal data class SmtBitVectorReduction(
    val commands: List<SExpr>,
    val bitNames: Map<String, List<String>>,
    val checks: List<SmtBitVectorCheck> = emptyList(),
)

internal fun reduceSmtBitVectors(commands: List<SExpr>, cancellation: Cancellation): SmtBitVectorReduction =
    SmtBitVectorReducer(commands, cancellation).reduce()

internal fun lowerSmtBitVectors(source: SmtReduction, cancellation: Cancellation): SmtReduction {
    val lowered = reduceSmtBitVectors(source.commands, cancellation)
    val recipes = lowered.bitNames.mapValues { SmtReconstruction.Bits(it.value) }
        .mapKeys { polynomialSymbol(it.key) }
    val bindings = source.bindings.map { binding ->
        val recipe = recipes[polynomialSymbol(binding.declaration.name)]
        if (recipe == null) binding else binding.copy(reconstruction = recipe)
    }
    return source.copy(commands = lowered.commands, bindings = bindings,
        privateBindings = source.privateBindings + recipes,
        checks = source.checks + lowered.checks.map { check ->
            SmtSourceCheck { values -> check.check(values::boolean, values::integer, values::checkpoint) }
        })
}

private class SmtBitVectorReducer(private val source: List<SExpr>, private val cancellation: Cancellation) {
    private sealed interface Type {
        data class Bits(val width: Int) : Type
        data class Scalar(val name: String) : Type
    }

    private sealed interface Value {
        val type: Type
        data class Bits(val bits: List<SExpr>) : Value {
            override val type = Type.Bits(bits.size)
        }
        data class Scalar(val expression: SExpr, override val type: Type.Scalar) : Value
    }

    private data class Macro(
        val parameters: List<Pair<String, Type>>,
        val result: Type,
        val body: SExpr,
        val values: Map<String, Value>,
        val macros: Map<String, Macro>,
    )

    private data class Environment(val values: Map<String, Value>, val macros: Map<String, Macro>)

    private sealed interface Frame {
        data class Eval(val expression: SExpr, val environment: Environment) : Frame
        data class Combine(val expression: SExpr.SList, val count: Int) : Frame
        data class BindLet(val names: List<String>, val body: SExpr, val environment: Environment) : Frame
        data class Call(val macro: Macro, val count: Int) : Frame
        data class Check(val expected: Type) : Frame
    }

    private val boolType = Type.Scalar("Bool")
    private val intType = Type.Scalar("Int")
    private val realType = Type.Scalar("Real")
    private val values = LinkedHashMap<String, Value>()
    private val macros = LinkedHashMap<String, Macro>()
    private val output = ArrayList<SExpr>()
    private val bitNames = LinkedHashMap<String, List<String>>()
    private val reserved = HashSet<String>()
    private var nextName = 0
    private var gateCount = 0
    private var workCount = 0L
    private val checks = ArrayList<SmtBitVectorCheck>()
    private val gateCache = HashMap<SExpr, SExpr>()
    private val zero = atom("false")
    private val one = atom("true")
    private val gates = object : BitVectorGates<SExpr> {
        override val zero get() = this@SmtBitVectorReducer.zero
        override val one get() = this@SmtBitVectorReducer.one
        override fun not(value: SExpr): SExpr = checked { when (value) {
            zero -> one
            one -> zero
            else -> if (head(value) == "not") (value as SExpr.SList).items[1] else gate("not", value)
        } }
        override fun and(left: SExpr, right: SExpr): SExpr = checked { when {
            left == zero || right == zero -> zero
            left == one -> right
            right == one || left == right -> left
            else -> gate("and", left, right)
        } }
        override fun or(left: SExpr, right: SExpr): SExpr = checked { when {
            left == one || right == one -> one
            left == zero -> right
            right == zero || left == right -> left
            else -> gate("or", left, right)
        } }
        override fun xor(left: SExpr, right: SExpr): SExpr = checked { when {
            left == right -> zero
            left == zero -> right
            right == zero -> left
            left == one -> not(right)
            right == one -> not(left)
            else -> gate("xor", left, right)
        } }
        override fun ite(condition: SExpr, yes: SExpr, no: SExpr): SExpr = checked { when {
            condition == one -> yes
            condition == zero -> no
            yes == no -> yes
            yes == one && no == zero -> condition
            yes == zero && no == one -> not(condition)
            else -> gate("ite", condition, yes, no)
        } }
    }
    private val circuit = BitVectorCircuit(gates)

    fun reduce(): SmtBitVectorReduction {
        val pending = ArrayDeque<SExpr>()
        pending.addAll(source)
        var active = false
        while (pending.isNotEmpty()) {
            cancellation.polynomialSourceCheckpoint(1)
            when (val expression = pending.removeLast()) {
                is SExpr.Atom -> {
                    reserved.add(polynomialSymbol(expression.text))
                    if (expression.text == "BitVec" || expression.text.startsWith("#b") || expression.text.startsWith("#x")) active = true
                }
                is SExpr.SList -> {
                    pending.addAll(expression.items)
                    if (head(expression) == "_" && (expression.items.getOrNull(1) as? SExpr.Atom)?.text?.startsWith("bv") == true) active = true
                }
            }
        }
        if (!active) return SmtBitVectorReduction(source, emptyMap())
        for (expression in source) {
            cancellation.polynomialSourceCheckpoint(1)
            val command = expression as? SExpr.SList ?: smtUnsupported("expected SMT command")
            when (head(command)) {
                "declare-const" -> declaration(command, false)
                "declare-fun" -> declaration(command, true)
                "define-fun" -> definition(command, false)
                "define-const" -> definition(command, true)
                "assert", "minimize", "maximize" -> {
                    if (command.items.size != 2) smtUnsupported("malformed SMT expression command")
                    val value = evaluate(command.items[1], Environment(values.toMap(), macros.toMap()))
                    output.add(form(head(command)!!, scalar(value)))
                }
                "check-sat-assuming" -> {
                    val assumptions = command.items.getOrNull(1) as? SExpr.SList
                        ?: smtUnsupported("malformed check-sat-assuming")
                    val environment = Environment(values.toMap(), macros.toMap())
                    output.add(form("check-sat-assuming", SExpr.SList(assumptions.items.map { scalar(evaluate(it, environment)) })))
                }
                else -> output.add(command)
            }
        }
        return SmtBitVectorReduction(output.toList(), bitNames.toMap(), checks.toList())
    }

    private fun declaration(command: SExpr.SList, function: Boolean) {
        val name = symbol(command.items.getOrNull(1))
        val key = polynomialSymbol(name)
        if (values.containsKey(key) || macros.containsKey(key)) smtUnsupported("duplicate declaration '$name'")
        val sortNode = command.items.getOrNull(if (function) 3 else 2) ?: smtUnsupported("missing declaration sort")
        if (function) {
            val parameters = command.items.getOrNull(2) as? SExpr.SList ?: smtUnsupported("missing parameter sorts")
            if (parameters.items.isNotEmpty()) smtUnsupported("uninterpreted functions must be reduced before bitvectors")
        }
        val type = type(sortNode)
        values[key] = when (type) {
            is Type.Bits -> {
                val names = List(type.width) { fresh("Bool").text }
                bitNames[name] = names
                Value.Bits(names.map(::atom))
            }
            is Type.Scalar -> {
                output.add(command)
                Value.Scalar(atom(name), type)
            }
        }
    }

    private fun definition(command: SExpr.SList, constant: Boolean) {
        val name = polynomialSymbol(symbol(command.items.getOrNull(1)))
        if (values.containsKey(name) || macros.containsKey(name)) smtUnsupported("duplicate definition '$name'")
        val parameters = if (constant) emptyList() else {
            val parameters = command.items.getOrNull(2) as? SExpr.SList ?: smtUnsupported("missing parameters")
            parameters.items.map { parameter ->
                val pair = parameter as? SExpr.SList ?: smtUnsupported("malformed parameter")
                if (pair.items.size != 2) smtUnsupported("malformed parameter")
                polynomialSymbol(symbol(pair.items[0])) to type(pair.items[1])
            }
        }
        if (parameters.map { it.first }.toSet().size != parameters.size) smtUnsupported("duplicate parameter")
        val result = type(command.items.getOrNull(if (constant) 2 else 3) ?: smtUnsupported("missing result sort"))
        val body = command.items.getOrNull(if (constant) 3 else 4) ?: smtUnsupported("missing definition body")
        macros[name] = Macro(parameters, result, body, values.toMap(), macros.toMap())
    }

    private fun evaluate(root: SExpr, environment: Environment): Value {
        val work = ArrayDeque<Frame>()
        val results = ArrayList<Value>()
        work.addLast(Frame.Eval(root, environment))
        while (work.isNotEmpty()) {
            cancellation.polynomialSourceCheckpoint(1)
            when (val frame = work.removeLast()) {
                is Frame.Check -> requireType(results.last(), frame.expected)
                is Frame.BindLet -> {
                    val args = take(results, frame.names.size)
                    val bindings = frame.environment.values + frame.names.zip(args)
                    work.addLast(Frame.Eval(frame.body, frame.environment.copy(values = bindings)))
                }
                is Frame.Call -> {
                    val arguments = take(results, frame.count)
                    val macro = frame.macro
                    for (i in arguments.indices) requireType(arguments[i], macro.parameters[i].second)
                    val bindings = macro.values + macro.parameters.map { it.first }.zip(arguments)
                    work.addLast(Frame.Check(macro.result))
                    work.addLast(Frame.Eval(macro.body, Environment(bindings, macro.macros)))
                }
                is Frame.Combine -> results.add(combine(frame.expression, take(results, frame.count)))
                is Frame.Eval -> {
                    when (val expression = frame.expression) {
                        is SExpr.Atom -> {
                            val key = polynomialSymbol(expression.text)
                            val bound = frame.environment.values[key]
                            val macro = frame.environment.macros[key]
                            when {
                                bound != null -> results.add(bound)
                                macro != null && macro.parameters.isEmpty() -> {
                                    work.addLast(Frame.Check(macro.result))
                                    work.addLast(Frame.Eval(macro.body, Environment(macro.values, macro.macros)))
                                }
                                else -> results.add(literal(expression))
                            }
                        }
                        is SExpr.SList -> {
                            val operator = head(expression)
                            when {
                                operator == "let" -> {
                                    val bindings = expression.items.getOrNull(1) as? SExpr.SList ?: smtUnsupported("malformed let")
                                    val pairs = bindings.items.map { it as? SExpr.SList ?: smtUnsupported("malformed let binding") }
                                    val names = pairs.map { polynomialSymbol(symbol(it.items.getOrNull(0))) }
                                    if (names.toSet().size != names.size || expression.items.size != 3) smtUnsupported("malformed let")
                                    work.addLast(Frame.BindLet(names, expression.items[2], frame.environment))
                                    for (pair in pairs.asReversed()) {
                                        if (pair.items.size != 2) smtUnsupported("malformed let binding")
                                        work.addLast(Frame.Eval(pair.items[1], frame.environment))
                                    }
                                }
                                operator == "!" -> work.addLast(Frame.Eval(expression.items.getOrNull(1)
                                    ?: smtUnsupported("malformed annotation"), frame.environment))
                                frame.environment.macros.containsKey(operator) -> {
                                    val macro = frame.environment.macros.getValue(operator!!)
                                    if (expression.items.size - 1 != macro.parameters.size) smtUnsupported("wrong helper arity")
                                    work.addLast(Frame.Call(macro, macro.parameters.size))
                                    for (arg in expression.items.drop(1).asReversed()) work.addLast(Frame.Eval(arg, frame.environment))
                                }
                                operator == "_" -> results.add(indexedLiteral(expression))
                                else -> {
                                    val args = expression.items.drop(1)
                                    work.addLast(Frame.Combine(expression, args.size))
                                    for (arg in args.asReversed()) work.addLast(Frame.Eval(arg, frame.environment))
                                }
                            }
                        }
                    }
                }
            }
        }
        return results.single()
    }

    private fun combine(expression: SExpr.SList, arguments: List<Value>): Value {
        val indexed = expression.items.firstOrNull() as? SExpr.SList
        if (indexed != null) return indexedOperation(indexed, arguments)
        val operator = head(expression) ?: smtUnsupported("missing operator")
        if (operator.startsWith("bv") || operator in setOf("concat", "ubv_to_int", "sbv_to_int", "bv2nat", "bv2int")) {
            return bitVectorOperation(operator, arguments)
        }
        if (operator in setOf("=", "distinct") && arguments.any { it is Value.Bits }) {
            if (arguments.size < 2) smtUnsupported("bitvector equality requires at least two arguments")
            val bits = arguments.map(::bits)
            if (bits.any { it.size != bits[0].size }) smtUnsupported("bitvector width mismatch")
            val comparisons = ArrayList<SExpr>()
            if (operator == "=") {
                for (i in 1 until bits.size) comparisons.add(circuit.equal(bits[i - 1], bits[i]))
            } else {
                for (i in bits.indices) for (j in i + 1 until bits.size) comparisons.add(gates.not(circuit.equal(bits[i], bits[j])))
            }
            val result = bool(comparisons.fold(one, gates::and))
            record(operator, emptyList(), arguments, result)
            return result
        }
        if (operator == "ite" && arguments.any { it is Value.Bits }) {
            arity(arguments, 3)
            requireType(arguments[0], boolType)
            val condition = observeScalar(arguments[0])
            if (bits(arguments[1]).size != bits(arguments[2]).size) smtUnsupported("bitvector width mismatch")
            val observedArguments = listOf(condition) + arguments.drop(1)
            val result = Value.Bits(circuit.ite(scalar(condition), bits(arguments[1]), bits(arguments[2])))
            record(operator, emptyList(), observedArguments, result)
            return result
        }
        val args = arguments.map(::scalar)
        val resultType = when (operator) {
            "and", "or", "not", "xor", "=>", "=", "distinct", "<", "<=", ">", ">=", "is_int" -> boolType
            "to_int", "div", "mod", "abs", "ubv_to_int", "sbv_to_int" -> intType
            "to_real", "/", "exp", "log", "sin", "cos" -> realType
            "ite" -> if (arguments.drop(1).any { it.type == realType }) realType
                else arguments.getOrNull(1)?.type as? Type.Scalar ?: smtUnsupported("malformed ite")
            else -> if (arguments.any { it.type == realType }) realType else intType
        }
        return Value.Scalar(form(operator, *args.toTypedArray()), resultType)
    }

    private fun bitVectorOperation(operator: String, arguments: List<Value>): Value {
        if (operator in setOf("ubv_to_int", "sbv_to_int", "bv2nat", "bv2int")) {
            arity(arguments, 1)
            val result = observeScalar(Value.Scalar(integerValue(bits(arguments[0]), operator == "sbv_to_int"), intType))
            record(operator, emptyList(), arguments, result)
            return result
        }
        val operands = arguments.map(::bits)
        val unary = operator in setOf("bvnot", "bvneg", "bvnego", "bvredor", "bvredand")
        val associative = operator in setOf("bvand", "bvor", "bvxor", "bvadd", "bvmul")
        if (unary) arity(arguments, 1) else if (associative) {
            if (arguments.size < 2) smtUnsupported("'$operator' requires at least two operands")
        } else arity(arguments, 2)
        val left = operands[0]
        val right = operands.getOrNull(1) ?: left
        if (operator != "concat" && operands.any { it.size != left.size }) smtUnsupported("bitvector width mismatch")
        val result = when (operator) {
            "concat" -> {
                checkWidth(left.size.toLong() + right.size)
                circuit.concat(left, right)
            }
            "bvnot" -> circuit.not(left)
            "bvneg" -> circuit.negate(left)
            "bvand" -> operands.reduce(circuit::and)
            "bvor" -> operands.reduce(circuit::or)
            "bvxor" -> operands.reduce(circuit::xor)
            "bvnand" -> circuit.not(circuit.and(left, right))
            "bvnor" -> circuit.not(circuit.or(left, right))
            "bvxnor" -> circuit.not(circuit.xor(left, right))
            "bvadd" -> operands.reduce(circuit::add)
            "bvsub" -> circuit.subtract(left, right)
            "bvmul" -> operands.reduce(circuit::multiply)
            "bvudiv" -> circuit.unsignedDivRem(left, right).first
            "bvurem" -> circuit.unsignedDivRem(left, right).second
            "bvsdiv" -> circuit.signedDivide(left, right)
            "bvsrem" -> circuit.signedRemainder(left, right)
            "bvsmod" -> circuit.signedModulo(left, right)
            "bvshl" -> circuit.shiftLeft(left, right)
            "bvlshr" -> circuit.logicalShiftRight(left, right)
            "bvashr" -> circuit.arithmeticShiftRight(left, right)
            "bvcomp" -> listOf(circuit.equal(left, right))
            "bvredor" -> listOf(left.fold(zero, gates::or))
            "bvredand" -> listOf(left.fold(one, gates::and))
            else -> {
                val result = bool(when (operator) {
                "bvult" -> circuit.unsignedLess(left, right)
                "bvule" -> gates.not(circuit.unsignedLess(right, left))
                "bvugt" -> circuit.unsignedLess(right, left)
                "bvuge" -> gates.not(circuit.unsignedLess(left, right))
                "bvslt" -> circuit.signedLess(left, right)
                "bvsle" -> gates.not(circuit.signedLess(right, left))
                "bvsgt" -> circuit.signedLess(right, left)
                "bvsge" -> gates.not(circuit.signedLess(left, right))
                "bvnego" -> circuit.negateOverflow(left)
                "bvuaddo" -> circuit.unsignedAddOverflow(left, right)
                "bvsaddo" -> circuit.signedAddOverflow(left, right)
                "bvusubo" -> circuit.unsignedSubtractOverflow(left, right)
                "bvssubo" -> circuit.signedSubtractOverflow(left, right)
                "bvsdivo" -> circuit.signedDivideOverflow(left, right)
                "bvumulo" -> circuit.unsignedMultiplyOverflow(left, right)
                "bvsmulo" -> circuit.signedMultiplyOverflow(left, right)
                else -> smtUnsupported("unknown bitvector operator '$operator'")
                })
                record(operator, emptyList(), arguments, result)
                return result
            }
        }
        return Value.Bits(result).also { record(operator, emptyList(), arguments, it) }
    }

    private fun indexedOperation(indexed: SExpr.SList, arguments: List<Value>): Value {
        if (head(indexed) != "_") smtUnsupported("malformed indexed operator")
        val operator = symbol(indexed.items.getOrNull(1))
        val rawIndices = indexed.items.drop(2)
        arity(arguments, 1)
        if (operator == "divisible") {
            requireType(arguments[0], intType)
            return Value.Scalar(SExpr.SList(listOf(indexed, scalar(arguments[0]))), boolType)
        }
        val rotation = operator == "rotate_left" || operator == "rotate_right"
        val indices = if (rotation) {
            if (rawIndices.size != 1) smtUnsupported("invalid rotation index")
            val text = symbol(rawIndices[0])
            if (text.isEmpty() || text.any { it !in '0'..'9' }) smtUnsupported("invalid rotation index")
            listOf((parseBigInt(text) % bigIntOf(bits(arguments[0]).size)).toLongExact().toInt())
        } else rawIndices.map(::index)
        if (operator == "int_to_bv" || operator == "int2bv") {
            if (indices.size != 1 || indices[0] == 0) smtUnsupported("invalid int_to_bv width")
            requireType(arguments[0], intType)
            checkWidth(indices[0].toLong())
            val argument = observeScalar(arguments[0])
            val bits = List(indices[0]) { fresh("Bool") }
            val modulus = atom((BIG_ONE shl indices[0]).toString())
            val quotient = fresh("Int")
            output.add(form("assert", form("=", scalar(argument),
                form("+", integerValue(bits, false), form("*", modulus, quotient)))))
            return Value.Bits(bits).also { record(operator, indices, listOf(argument), it) }
        }
        val value = bits(arguments[0])
        return Value.Bits(when (operator) {
            "extract" -> {
                if (indices.size != 2 || indices[0] >= value.size || indices[1] > indices[0]) smtUnsupported("invalid extract indices")
                circuit.extract(value, indices[0], indices[1])
            }
            "zero_extend", "sign_extend" -> {
                if (indices.size != 1) smtUnsupported("invalid extension indices")
                checkWidth(value.size.toLong() + indices[0])
                circuit.extend(value, indices[0], operator == "sign_extend")
            }
            "repeat" -> {
                if (indices.size != 1 || indices[0] == 0) smtUnsupported("invalid repeat count")
                checkWidth(value.size.toLong() * indices[0])
                circuit.repeat(value, indices[0])
            }
            "rotate_left", "rotate_right" -> {
                if (indices.size != 1) smtUnsupported("invalid rotation index")
                circuit.rotate(value, indices[0], operator == "rotate_right")
            }
            else -> smtUnsupported("unknown indexed bitvector operator '$operator'")
        }).also { record(operator, indices, arguments, it) }
    }

    private fun integerValue(bits: List<SExpr>, signed: Boolean): SExpr {
        chargeEncodingWork(bits.size.toLong() * (bits.size + 1L) / 2)
        val summands = bits.mapIndexed { i, bit ->
            cancellation.polynomialSourceCheckpoint(1)
            val weight = atom((BIG_ONE shl i).toString())
            val term = form("*", weight, form("ite", bit, atom("1"), atom("0")))
            if (signed && i == bits.lastIndex) form("-", term) else term
        }
        return if (summands.size == 1) summands[0] else form("+", *summands.toTypedArray())
    }

    private fun literal(expression: SExpr.Atom): Value {
        val text = expression.text
        if (text.startsWith("#b")) {
            val digits = text.drop(2)
            if (digits.isEmpty() || digits.any { it != '0' && it != '1' }) smtUnsupported("invalid binary bitvector literal")
            checkWidth(digits.length.toLong())
            return Value.Bits(digits.reversed().map { if (it == '1') one else zero })
        }
        if (text.startsWith("#x")) {
            val digits = text.drop(2)
            if (digits.isEmpty() || digits.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) {
                smtUnsupported("invalid hexadecimal bitvector literal")
            }
            checkWidth(digits.length.toLong() * 4)
            return Value.Bits(digits.reversed().flatMap { digit -> List(4) { if (digit.digitToInt(16) and (1 shl it) != 0) one else zero } })
        }
        val type = when {
            text == "true" || text == "false" -> boolType
            text.toBigIntOrNull() != null -> intType
            text.contains('.') -> realType
            text == "pi" -> realType
            else -> smtUnsupported("undeclared symbol '$text'")
        }
        return Value.Scalar(expression, type)
    }

    private fun indexedLiteral(expression: SExpr.SList): Value {
        if (expression.items.size != 3) smtUnsupported("invalid indexed bitvector literal")
        val numeral = symbol(expression.items[1])
        if (!numeral.startsWith("bv")) smtUnsupported("unknown indexed constant")
        val digits = numeral.drop(2)
        if (digits.isEmpty() || digits.any { it !in '0'..'9' }) smtUnsupported("invalid bitvector numeral")
        val width = index(expression.items[2])
        checkWidth(width.toLong())
        var value = parseBigInt(digits)
        if (value.magnitudeBitLength() > width) smtUnsupported("bitvector numeral exceeds its declared width")
        val two = bigIntOf(2)
        return Value.Bits(List(width) {
            val bit = if (value % two == BIG_ZERO) zero else one
            value /= two
            bit
        })
    }

    private fun type(expression: SExpr): Type = when (expression) {
        is SExpr.Atom -> Type.Scalar(expression.text)
        is SExpr.SList -> {
            if (expression.items.size != 3 || head(expression) != "_" || symbol(expression.items[1]) != "BitVec") {
                smtUnsupported("non-scalar sort must be reduced before bitvectors")
            }
            val width = index(expression.items[2])
            checkWidth(width.toLong())
            Type.Bits(width)
        }
    }

    private fun gate(operator: String, vararg arguments: SExpr): SExpr {
        cancellation.polynomialSourceCheckpoint(1)
        val expression = form(operator, *arguments)
        return gateCache.getOrPut(expression) {
            if (++gateCount > MAX_GATES) smtUnsupported("bitvector circuit exceeds the encoding budget")
            val result = fresh("Bool")
            output.add(form("assert", form("=", result, expression)))
            result
        }
    }

    private fun checked(operation: () -> SExpr): SExpr {
        chargeEncodingWork(1)
        return operation()
    }

    private fun chargeEncodingWork(units: Long) {
        cancellation.polynomialSourceCheckpoint(units)
        if (units > MAX_WORK - workCount) smtUnsupported("bitvector circuit exceeds the work budget")
        workCount += units
    }

    private fun observeScalar(value: Value): Value.Scalar {
        val scalar = value as? Value.Scalar ?: smtUnsupported("expected scalar observation")
        if (scalar.expression is SExpr.Atom &&
            (scalar.type == boolType || scalar.expression.text.toBigIntOrNull() == null)) return scalar
        val name = fresh(scalar.type.name)
        output.add(form("assert", form("=", name, scalar.expression)))
        return Value.Scalar(name, scalar.type)
    }

    private fun record(operator: String, indices: List<Int>, arguments: List<Value>, result: Value) {
        fun coordinate(value: Value): SmtBitVectorCoordinate = when (value) {
            is Value.Bits -> SmtBitVectorCoordinate.Bits(value.bits.map { symbol(it) })
            is Value.Scalar -> if (value.type == boolType) SmtBitVectorCoordinate.Boolean(symbol(value.expression))
                else SmtBitVectorCoordinate.Integer(symbol(value.expression))
        }
        checks.add(SmtBitVectorCheck(operator, indices, arguments.map(::coordinate), coordinate(result)))
    }

    private fun fresh(sort: String): SExpr.Atom {
        cancellation.polynomialSourceCheckpoint(1)
        if (nextName >= MAX_GATES) smtUnsupported("bitvector coordinates exceed the encoding budget")
        var name: String
        do { name = "@klause.bv.${nextName++}" } while (!reserved.add(name))
        return atom(name).also { output.add(form("declare-const", it, atom(sort))) }
    }

    private fun requireType(value: Value, expected: Type) {
        if (value.type != expected && !(value.type == intType && expected == realType)) smtUnsupported("incompatible expression sort")
    }

    private fun bits(value: Value): List<SExpr> = (value as? Value.Bits)?.bits ?: smtUnsupported("expected bitvector term")

    private fun scalar(value: Value): SExpr = (value as? Value.Scalar)?.expression ?: smtUnsupported("bitvector used as scalar")

    private fun bool(expression: SExpr): Value = Value.Scalar(expression, boolType)

    private fun arity(arguments: List<Value>, count: Int) {
        if (arguments.size != count) smtUnsupported("wrong bitvector operator arity")
    }

    private fun index(expression: SExpr): Int {
        val text = symbol(expression)
        if (text.isEmpty() || text.any { it !in '0'..'9' }) smtUnsupported("invalid bitvector index")
        return text.toIntOrNull() ?: smtUnsupported("bitvector index exceeds the encoding budget")
    }

    private fun checkWidth(width: Long) {
        if (width !in 1..MAX_WIDTH.toLong()) smtUnsupported("bitvector width exceeds the encoding budget")
    }

    private fun take(values: MutableList<Value>, count: Int): List<Value> {
        val result = values.takeLast(count)
        repeat(count) { values.removeAt(values.lastIndex) }
        return result
    }

    private fun String.toBigIntOrNull(): BigInt? = if (isNotEmpty() && all { it in '0'..'9' }) parseBigInt(this) else null

    private fun symbol(expression: SExpr?): String = (expression as? SExpr.Atom)?.text ?: smtUnsupported("expected symbol")

    private fun head(expression: SExpr): String? = ((expression as? SExpr.SList)?.items?.firstOrNull() as? SExpr.Atom)?.text?.let(::polynomialSymbol)

    private fun atom(text: String): SExpr.Atom = SExpr.Atom(text)

    private fun form(operator: String, vararg arguments: SExpr): SExpr.SList = SExpr.SList(listOf(atom(operator)) + arguments)

    private companion object {
        const val MAX_WIDTH = 1_000_000
        const val MAX_GATES = 1_000_000
        const val MAX_WORK = 10_000_000
    }
}
