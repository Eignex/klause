package com.eignex.klause.bench.metric

import java.math.BigInteger

internal object ExactOpbValidation {
    private data class Term(val coefficient: BigInteger, val literals: List<String>)
    private data class Row(val terms: List<Term>, val op: String, val bound: BigInteger, val cost: BigInteger?)
    private data class Source(
        val variables: Int, val rows: List<Row>, val objective: List<Term>, val optimizing: Boolean,
        val top: BigInteger?,
    )

    fun inspect(source: String, coordinates: String, objective: String?): SourceValidation = runCatching {
        val parsed = parse(source)
        val values = LinkedHashMap<String, Boolean>()
        for (token in coordinates.trim().split(Regex("\\s+")).filter(String::isNotEmpty)) {
            val match = Regex("(-?)x([1-9][0-9]*)").matchEntire(token) ?: error("unsupported Boolean coordinate")
            val variable = "x${match.groupValues[2]}"
            require(variable !in values) { "duplicate source coordinate $variable" }
            values[variable] = match.groupValues[1].isEmpty()
        }
        if ((1..parsed.variables).any { "x$it" !in values } ||
            values.keys.any { it.substring(1).toInt() !in 1..parsed.variables }
        ) {
            return@runCatching SourceValidation(
                "invalid", "OPB publication does not contain exactly the source Boolean coordinates")
        }
        fun value(terms: List<Term>): BigInteger = terms.fold(BigInteger.ZERO) { sum, term ->
            if (term.literals.all { literal ->
                    val positive = !literal.startsWith('~')
                    values.getValue(literal.removePrefix("~")) == positive
                }) sum + term.coefficient else sum
        }
        var penalties = BigInteger.ZERO
        for (row in parsed.rows) {
            val sum = value(row.terms)
            val holds = when (row.op) {
                "<=" -> sum <= row.bound
                ">=" -> sum >= row.bound
                "=" -> sum == row.bound
                else -> error("unsupported relation")
            }
            if (!holds) {
                if (row.cost == null) {
                    return@runCatching SourceValidation("invalid", "original OPB hard relation rejects candidate")
                }
                penalties += row.cost
            }
        }
        if (parsed.top != null && penalties >= parsed.top) {
            return@runCatching SourceValidation("invalid", "original WBO soft top rejects candidate")
        }
        val expected = value(parsed.objective) + penalties
        if (parsed.optimizing) {
            val reported = objective?.toBigIntegerOrNull()
                ?: return@runCatching SourceValidation("unknown", "OPB objective requires an exact integral publication")
            if (reported != expected) {
                return@runCatching SourceValidation("invalid", "reported cost differs from original OPB cost")
            }
        } else if (objective != null) {
            return@runCatching SourceValidation("invalid", "OPB satisfaction publication carries an objective")
        }
        SourceValidation("valid", "exact original OPB checks ${parsed.variables} Boolean coordinates, " +
            "${parsed.rows.size} relations and source objective units", scope = "opb-integral")
    }.getOrElse {
        SourceValidation("unknown", "exact OPB checking incomplete: ${it.message.orEmpty().take(300)}",
            scope = "opb-integral")
    }

    private fun parse(source: String): Source {
        val header = Regex("(?m)^\\s*\\*\\s*#variable=\\s*([0-9]+)").find(source)
            ?.groupValues?.get(1)?.toInt() ?: 0
        val body = source.lineSequence().filterNot { it.trimStart().startsWith('*') }.joinToString("\n")
        val statements = body.split(';').map(String::trim).filter(String::isNotEmpty)
        require(body.trimEnd().endsWith(';')) { "missing source statement terminator" }
        val rows = ArrayList<Row>()
        val objective = ArrayList<Term>()
        var optimizing = false
        var top: BigInteger? = null
        var variables = header
        for (statement in statements) {
            val tokens = tokens(statement)
            for (token in tokens) if (token.matches(Regex("~?x[1-9][0-9]*"))) {
                variables = maxOf(variables, token.removePrefix("~x").removePrefix("x").toInt())
            }
            when (tokens.first()) {
                "min:" -> {
                    objective.addAll(terms(tokens.drop(1)))
                    optimizing = true
                }
                "soft:" -> {
                    require(tokens.size == 2 && top == null) { "invalid soft header" }
                    top = tokens[1].toBigInteger().also { require(it.signum() >= 0) }
                }
                else -> {
                    var start = 0
                    val cost = if (tokens.first() == "[") {
                        require(tokens.size >= 3 && tokens[2] == "]") { "invalid soft penalty" }
                        start = 3
                        optimizing = true
                        tokens[1].toBigInteger().also { require(it.signum() >= 0) }
                    } else null
                    val comparison = tokens.indexOfFirst { it in setOf("<=", ">=", "=") }
                    require(comparison >= start && comparison + 2 == tokens.size) { "invalid constraint" }
                    rows.add(Row(terms(tokens.subList(start, comparison)), tokens[comparison],
                        tokens[comparison + 1].toBigInteger(), cost))
                }
            }
        }
        return Source(variables, rows, objective, optimizing, top)
    }

    private fun tokens(statement: String): List<String> {
        val token = Regex("min\\s*:|soft\\s*:|[<>]=|=|[\\[\\]]|[+-]?[0-9]+|~?x[1-9][0-9]*")
        val result = ArrayList<String>()
        var cursor = 0
        while (cursor < statement.length) {
            if (statement[cursor].isWhitespace()) {
                cursor++
                continue
            }
            val match = token.find(statement, cursor)
            require(match != null && match.range.first == cursor) { "unsupported source token" }
            result.add(match.value.replace(Regex("\\s+"), ""))
            cursor = match.range.last + 1
        }
        require(result.isNotEmpty())
        return result
    }

    private fun terms(tokens: List<String>): List<Term> {
        val result = ArrayList<Term>()
        var cursor = 0
        while (cursor < tokens.size) {
            val coefficient = tokens[cursor++].toBigInteger()
            val literals = ArrayList<String>()
            while (cursor < tokens.size && tokens[cursor].matches(Regex("~?x[1-9][0-9]*"))) {
                literals.add(tokens[cursor++])
            }
            require(literals.isNotEmpty()) { "weighted term has no source literal" }
            result.add(Term(coefficient, literals))
        }
        return result
    }
}
