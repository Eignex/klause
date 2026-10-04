package com.eignex.klause.bench.source

import kotlin.math.abs

/**
 * A mixed-integer linear model built in code and written as MPS, for corpora that ship their instances in a format
 * of their own (see [OrLibrary]). Rows are kept sparse, one term per (variable, coefficient), so a model with many
 * variables stays as small in memory as its MPS file.
 */
internal class MipModel(private val name: String, private val maximize: Boolean = false) {
    private class Variable(
        val name: String,
        val cost: Double,
        val lower: Double,
        val upper: Double?,
        val integer: Boolean,
    )

    private class Row(val name: String, val sense: Char, val rhs: Double, val terms: List<Pair<Int, Double>>)

    private val variables = ArrayList<Variable>()
    private val rows = ArrayList<Row>()

    /** A 0-1 variable; returns its index for [row]. */
    fun binary(name: String, cost: Double = 0.0): Int = add(Variable(name, cost, 0.0, 1.0, integer = true))

    /** A continuous variable in [lower, upper], unbounded above when [upper] is null. */
    fun continuous(name: String, cost: Double = 0.0, lower: Double = 0.0, upper: Double? = null): Int =
        add(Variable(name, cost, lower, upper, integer = false))

    /** `Σ coefficient·variable` [sense] [rhs], with [sense] one of `L` (≤), `G` (≥) or `E` (=). */
    fun row(name: String, sense: Char, rhs: Double, terms: List<Pair<Int, Double>>) {
        require(sense in "LGE") { "row sense must be L, G or E, got $sense" }
        rows += Row(name, sense, rhs, terms)
    }

    val variableCount: Int get() = variables.size

    private fun add(variable: Variable): Int {
        variables += variable
        return variables.size - 1
    }

    /** The model in free MPS: an `OBJSENSE` section for a maximisation, integer columns between `MARKER`s, and
     *  every bound written out, so no reader's default for an unbounded integer applies. */
    fun mps(): String = buildString {
        appendLine("NAME $name")
        if (maximize) appendLine("OBJSENSE\n    MAX")
        appendLine("ROWS")
        appendLine(" N obj")
        for (row in rows) appendLine(" ${row.sense} ${row.name}")
        appendLine("COLUMNS")
        val byColumn = Array(variables.size) { ArrayList<Pair<String, Double>>() }
        for (row in rows) for ((column, coefficient) in row.terms) byColumn[column] += row.name to coefficient
        var marker = 0
        var inInteger = false
        variables.forEachIndexed { index, variable ->
            if (variable.integer != inInteger) {
                appendLine("    M${marker++} 'MARKER' '${if (variable.integer) "INTORG" else "INTEND"}'")
                inInteger = variable.integer
            }
            // A column needs at least one entry to exist, so a variable in no row still lists its cost.
            if (variable.cost != 0.0 || byColumn[index].isEmpty()) {
                appendLine(
                    "    ${variable.name} obj ${number(variable.cost)}",
                )
            }
            for ((row, coefficient) in byColumn[index]) appendLine("    ${variable.name} $row ${number(coefficient)}")
        }
        if (inInteger) appendLine("    M$marker 'MARKER' 'INTEND'")
        appendLine("RHS")
        for (row in rows) if (row.rhs != 0.0) appendLine("    rhs ${row.name} ${number(row.rhs)}")
        appendLine("BOUNDS")
        for (variable in variables) {
            if (variable.lower != 0.0) appendLine(" LO bnd ${variable.name} ${number(variable.lower)}")
            when {
                variable.upper != null -> appendLine(" UP bnd ${variable.name} ${number(variable.upper)}")
                variable.integer -> appendLine(" PL bnd ${variable.name}")
            }
        }
        appendLine("ENDATA")
    }

    private fun number(value: Double): String = if (value == Math.rint(
            value,
        ) && abs(value) < LONG_SAFE
    ) {
        value.toLong().toString()
    } else {
        value.toString()
    }

    private companion object {
        const val LONG_SAFE = 1e15
    }
}
