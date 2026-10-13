package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.ExactFlatZincValidation
import com.eignex.klause.bench.metric.ExactObjective
import com.eignex.klause.bench.metric.MiniZincSourceValidation
import com.eignex.klause.bench.metric.SourceValidation

internal object FznStreamChecker {
    fun check(source: String, output: String, interrupted: Boolean, minimumWitnesses: Int): List<SourceValidation> {
        require(minimumWitnesses > 0)
        require(!interrupted || output.lineSequence().none { it == "==========" }) {
            "Interrupted output carries an optimality marker"
        }
        val publications = output.split("----------").dropLast(1)
        require(publications.size >= minimumWitnesses) { "Missing complete FlatZinc publications" }
        val costs = ArrayList<ExactObjective?>()
        val checked = publications.map { publication ->
            val costLines = publication.lineSequence().filter { it.startsWith("% objective=") }.toList()
            require(costLines.size == 1) { "Each FlatZinc publication requires its own exact cost" }
            val cost = costLines.single().substringAfter('=')
            costs.add(ExactObjective.parse(cost))
            val coordinates = MiniZincSourceValidation.exactCoordinates(publication + "----------")
                ?: MiniZincSourceValidation.candidate(publication + "----------")
                ?: error("FlatZinc publication has no complete coordinates")
            ExactFlatZincValidation.inspect(source, coordinates, cost)
        }
        if (checked.all { it.status == "valid" }) {
            val solve = source.lineSequence().map { it.substringBefore('%') }.joinToString("\n")
                .split(';').single { it.trim().startsWith("solve ") }
            val sense = requireNotNull(Regex("\\b(minimize|maximize)\\b").find(solve)).value
            for ((prior, next) in costs.zipWithNext()) {
                val comparison = requireNotNull(next).compareTo(requireNotNull(prior))
                require(if (sense == "minimize") comparison < 0 else comparison > 0) {
                    "FlatZinc incumbent does not strictly improve in original direction"
                }
            }
        }
        return checked
    }
}
