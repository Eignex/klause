package com.eignex.klause.bench.metric

import java.math.BigInteger

internal object ExactFlatZincValidation {
    private sealed interface Value {
        data class Number(val value: ExactObjective) : Value
        data class Bool(val value: Boolean) : Value
        data class Array(val values: List<Value>) : Value
    }

    fun inspect(source: String, coordinates: String, objective: String?): SourceValidation = runCatching {
        Checker(source, coordinates).check(objective).copy(scope = "flatzinc-binary64")
    }.getOrElse {
        SourceValidation("unknown", "exact FlatZinc checking incomplete: ${it.message.orEmpty().take(300)}", scope = "flatzinc-binary64")
    }

    private class Checker(source: String, coordinates: String) {
        private val statements = source.lineSequence().map { it.substringBefore('%') }.joinToString("\n")
            .split(';').map(String::trim).filter(String::isNotEmpty)
        private val values = HashMap<String, Value>()
        private val declarations = LinkedHashMap<String, Pair<String, String?>>()
        private val constraints = ArrayList<String>()
        private var objective: String? = null
        private var hasSolve = false
        private val zero = number("0")

        init {
            for (line in coordinates.lineSequence()) {
                val assignment = line.removePrefix("% klause-exact: ").trim().removeSuffix(";")
                val name = assignment.substringBefore('=').trim()
                require(name.matches(Regex("[A-Za-z_][A-Za-z_0-9]*(\\[[0-9]+])?"))) { "malformed coordinate" }
                require(name !in values) { "duplicate coordinate $name" }
                values[name] = literal(assignment.substringAfter('=', ""))
            }
            for (statement in statements) {
                when {
                    statement.startsWith("predicate ") -> Unit
                    statement.startsWith("constraint ") -> constraints.add(statement.removePrefix("constraint "))
                    statement.startsWith("solve ") -> {
                        require(!hasSolve) { "multiple solve directives" }
                        hasSolve = true
                        objective = Regex("\\b(?:minimize|maximize)\\s+([A-Za-z_][A-Za-z_0-9]*|[-+0-9.eE]+)")
                            .find(statement)?.groupValues?.get(1)
                        require(objective != null || Regex("\\bsatisfy$").containsMatchIn(statement)) {
                            "unsupported solve directive"
                        }
                    }
                    statement.startsWith("output ") -> Unit
                    else -> declaration(statement)
                }
            }
        }

        private fun declaration(statement: String) {
            val match = Regex("^(.*?):\\s*([A-Za-z_][A-Za-z_0-9]*)(.*)$", RegexOption.DOT_MATCHES_ALL)
                .matchEntire(statement) ?: error("unsupported declaration")
            val (type, name, rest) = match.destructured
            val initializer = rest.substringAfter('=', "").trim().takeIf(String::isNotEmpty)
            require(name !in declarations) { "duplicate declaration $name" }
            declarations[name] = type.trim() to initializer
        }

        private fun resolve(text: String, visiting: Set<String> = emptySet()): Value {
            val token = text.trim()
            if (token.startsWith('[')) {
                require(token.endsWith(']')) { "unterminated array" }
                return Value.Array(split(token.substring(1, token.length - 1)).map { resolve(it, visiting) })
            }
            if (token !in declarations) return values[token] ?: sourceLiteral(token)
            require(token !in visiting) { "cyclic alias $token" }
            val initializer = declarations.getValue(token).second
            val supplied = values[token]
            val initialized = initializer?.let { resolve(it, visiting + token) }
            if (supplied != null && initialized != null) {
                require(equal(supplied, initialized)) { "coordinate disagrees with declaration $token" }
            }
            return supplied ?: initialized ?: error("missing coordinate $token")
        }

        fun check(reportedObjective: String?): SourceValidation {
            require(hasSolve) { "missing solve directive" }
            var bounds = 0
            for ((name, declaration) in declarations) {
                val (type, _) = declaration
                val value = resolve(name)
                val scalarType = type.substringAfterLast(" of ").removePrefix("var ").trim()
                val elements = if (value is Value.Array) value.values else listOf(value)
                for (element in elements) {
                    when (scalarType) {
                        "bool" -> require(element is Value.Bool) { "non-Boolean coordinate $name" }
                        "float" -> require(element is Value.Number) { "non-numeric coordinate $name" }
                        "int" -> require(element is Value.Number && element.value.integral) { "non-integer $name" }
                        else -> {
                            val range = scalarType.split("..")
                            require(range.size == 2) { "unsupported domain $scalarType" }
                            val lo = sourceNumber(range[0])
                            val hi = sourceNumber(range[1])
                            val x = numeric(element)
                            if (x < lo || x > hi) return SourceValidation("invalid", "source bound rejects $name")
                            if (range.none { '.' in it || 'e' in it.lowercase() } && !x.integral) {
                                return SourceValidation("invalid", "non-integer source coordinate $name")
                            }
                            bounds++
                        }
                    }
                }
            }
            for (constraint in constraints) {
                if (!predicate(constraint)) return SourceValidation("invalid", "source predicate rejects candidate: ${constraint.take(160)}")
            }
            objective?.let { name ->
                val expected = reportedObjective?.let(ExactObjective::parse)
                    ?: return SourceValidation("unknown", "missing exact reported objective")
                if (numeric(resolve(name)).compareTo(expected) != 0) {
                    return SourceValidation("invalid", "reported objective differs from source objective")
                }
            }
            return SourceValidation("valid", "exact original FlatZinc checks $bounds bounds and ${constraints.size} predicates with binary64 literals")
        }

        private fun predicate(text: String): Boolean {
            val name = text.substringBefore('(').trim()
            val end = text.indexOf(')')
            require(end >= 0) { "unsupported predicate syntax" }
            val args = split(text.substringAfter('(').substringBefore(')')).map { resolve(it) }
            if (name == "bool_clause") {
                require(args.size == 2)
                return array(args[0]).any { boolean(it) } || array(args[1]).any { !boolean(it) }
            }
            val base = name.removeSuffix("_reif").removeSuffix("_imp")
            val arity = if ("_lin_" in base || base.endsWith("_times") || base.endsWith("_plus") || base == "float_div") 3 else 2
            require(args.size == arity + if (name.endsWith("_reif") || name.endsWith("_imp")) 1 else 0) { "predicate arity" }
            val relation = when (base) {
                "float_eq", "int_eq", "bool_eq" -> equal(args[0], args[1])
                "float_ne", "int_ne" -> !equal(args[0], args[1])
                "float_le", "int_le" -> numeric(args[0]) <= numeric(args[1])
                "float_lt", "int_lt" -> numeric(args[0]) < numeric(args[1])
                "float_lin_eq", "int_lin_eq", "float_lin_le", "int_lin_le" -> {
                    val coefficients = array(args[0])
                    val variables = array(args[1])
                    require(coefficients.size == variables.size)
                    val activity = coefficients.indices.fold(zero) { sum, i ->
                        sum + numeric(coefficients[i]) * numeric(variables[i])
                    }
                    if (base.endsWith("_eq")) activity.compareTo(numeric(args[2])) == 0 else activity <= numeric(args[2])
                }
                "float_times", "int_times" ->
                    (numeric(args[0]) * numeric(args[1])).compareTo(numeric(args[2])) == 0
                "float_plus", "int_plus" ->
                    (numeric(args[0]) + numeric(args[1])).compareTo(numeric(args[2])) == 0
                "float_div" -> (numeric(args[0]) / numeric(args[1])).compareTo(numeric(args[2])) == 0
                else -> error("unsupported predicate $name")
            }
            return when {
                name.endsWith("_reif") -> relation == boolean(args.last())
                name.endsWith("_imp") -> !boolean(args.last()) || relation
                else -> relation
            }
        }

        private fun literal(text: String): Value = when (val token = text.trim()) {
            "true" -> Value.Bool(true)
            "false" -> Value.Bool(false)
            else -> Value.Number(number(token))
        }

        private fun sourceLiteral(text: String): Value = when (text) {
            "true" -> Value.Bool(true)
            "false" -> Value.Bool(false)
            else -> Value.Number(sourceNumber(text))
        }

        private fun sourceNumber(text: String): ExactObjective {
            if ('.' !in text && 'e' !in text.lowercase()) return number(text)
            val value = text.toDouble()
            require(value.isFinite()) { "nonfinite source literal" }
            val bits = java.lang.Double.doubleToLongBits(value)
            val exponentBits = ((bits ushr 52) and 2047).toInt()
            val significand = (bits and 0xfffffffffffffL) or if (exponentBits == 0) 0L else (1L shl 52)
            val exponent = if (exponentBits == 0) -1074 else exponentBits - 1075
            val signed = BigInteger.valueOf(significand).let { if (bits < 0) -it else it }
            val numerator = if (exponent >= 0) signed.shiftLeft(exponent) else signed
            val denominator = if (exponent >= 0) BigInteger.ONE else BigInteger.ONE.shiftLeft(-exponent)
            return number("$numerator/$denominator")
        }

        private fun number(text: String): ExactObjective = requireNotNull(ExactObjective.parse(text)) { "unsupported number $text" }
        private fun numeric(value: Value): ExactObjective = (value as Value.Number).value
        private fun boolean(value: Value): Boolean = (value as Value.Bool).value
        private fun array(value: Value): List<Value> = (value as Value.Array).values
        private fun equal(a: Value, b: Value): Boolean = when {
            a is Value.Number && b is Value.Number -> a.value.compareTo(b.value) == 0
            a is Value.Array && b is Value.Array -> a.values.size == b.values.size && a.values.indices.all { equal(a.values[it], b.values[it]) }
            else -> a == b
        }

        private fun split(text: String): List<String> {
            if (text.isBlank()) return emptyList()
            var depth = 0
            var start = 0
            val result = ArrayList<String>()
            for ((i, c) in text.withIndex()) {
                when (c) {
                    '[' -> depth++
                    ']' -> depth--
                    ',' -> if (depth == 0) {
                        result.add(text.substring(start, i).trim())
                        start = i + 1
                    }
                }
            }
            require(depth == 0) { "unbalanced array" }
            result.add(text.substring(start).trim())
            return result
        }
    }
}
