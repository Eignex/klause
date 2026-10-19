package com.eignex.klause.bench.metric

import java.math.BigDecimal
import java.math.BigInteger

internal object MpsSolution {
    private val number = Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?|[+-]?[0-9]+/[0-9]+")
    private val sourceSemantics = setOf("format=MPS", "numeric=DECIMAL_LITERALS", "overrides=true",
        "unbounded-int-lo=0", "unbounded-int-hi=1", "unbounded-real-lo=0", "unbounded-real-hi=open",
        "absolute-tolerance=0", "relative-tolerance=0")

    fun convert(record: SolveRecord, raw: String, exact: Boolean): String {
        require(record.feasible == true && record.kind == "optimize") { "no feasible objective-bearing point" }
        require(("--exact" in record.command.split(Regex("\\s+"))) == exact) {
            "producer exact policy differs"
        }
        for (line in raw.lineSequence().filter { "klause-source-semantics:" in it }) {
            val tokens = line.substringAfter("klause-source-semantics:").trim().split(Regex("\\s+"))
            require(exact && tokens.size == sourceSemantics.size && tokens.toSet() == sourceSemantics) {
                "overridden semantics are outside original-file validation"
            }
        }
        val witness = requireNotNull(record.finalWitness)
        require(SolveEvidence.finalWitness(com.eignex.klause.bench.catalog.Format.MPS, raw) == witness) {
            "retained point differs from stdout"
        }
        val objective = requireNotNull(raw.lineSequence().lastOrNull { it.startsWith("o ") })
            .removePrefix("o ").trim()
        val reported = requireNotNull(record.exactObjective ?: record.objective?.toString())
        require(value(objective) == value(reported)) { "retained objective differs from stdout" }
        val seen = mutableSetOf<String>()
        return buildString {
            appendLine("=obj= ${decimal(objective)}")
            val tokens = witness.split(Regex("\\s+"))
            require(tokens.first() == "v")
            for (token in tokens.drop(1)) {
                val parts = token.split('=')
                require(parts.size == 2 && parts[0].isNotEmpty() && parts[0].length < 256 &&
                    !parts[0].startsWith('=') && seen.add(parts[0])) { "duplicate or malformed coordinate" }
                appendLine("${parts[0]} ${decimal(parts[1])}")
            }
        }
    }

    private fun decimal(text: String): String {
        val (numerator, denominator) = value(text)
        val result = BigDecimal(numerator).divide(BigDecimal(denominator)).stripTrailingZeros().toString()
        MpsSolcheckerInput.requireNumber(result)
        return result
    }

    private fun value(text: String): Pair<BigInteger, BigInteger> {
        require(text.length in 1..4096 && number.matches(text)) { "unsupported number" }
        val parts = text.split('/')
        val numerator: BigInteger
        val denominator: BigInteger
        if (parts.size == 2) {
            numerator = parts[0].toBigInteger()
            denominator = parts[1].toBigInteger()
            require(denominator.signum() > 0)
        } else {
            val decimal = BigDecimal(text)
            require(decimal.scale() in -4096..4096) { "number exponent exceeds limit" }
            numerator = decimal.unscaledValue() * BigInteger.TEN.pow((-decimal.scale()).coerceAtLeast(0))
            denominator = BigInteger.TEN.pow(decimal.scale().coerceAtLeast(0))
        }
        val divisor = numerator.gcd(denominator)
        return numerator / divisor to denominator / divisor
    }
}
