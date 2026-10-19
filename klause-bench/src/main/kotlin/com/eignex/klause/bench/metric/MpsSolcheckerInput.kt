package com.eignex.klause.bench.metric

internal object MpsSolcheckerInput {
    private val decimal = Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")
    private val sections = setOf("NAME", "OBJSENSE", "ROWS", "COLUMNS", "RHS", "RANGES", "BOUNDS", "INDICATORS", "ENDATA")

    data class Profile(val maximize: Boolean)

    // This screens upstream reader limitations; it constructs no model or mathematical obligations.
    fun qualify(source: String): Profile {
        var section = ""
        var objective: String? = null
        var maximize = false
        var ended = false
        var column: String? = null
        val columns = mutableSetOf<String>()
        val objectiveColumns = mutableSetOf<String>()
        val rows = mutableSetOf<String>()
        val indicators = mutableSetOf<String>()
        for (line in source.lineSequence()) {
            require(line.length < 1023 && line.all { it == '\t' || it.code in 32..126 }) { "unsupported MPS line" }
            if (line.isBlank() || line.startsWith('*')) continue
            require(!ended) { "records after ENDATA are unsupported" }
            val fields = line.trim().split(Regex("\\s+"))
            val first = fields.first()
            if (!line.first().isWhitespace()) {
                require(first in sections && (first == "NAME" || fields.size == 1)) { "unsupported MPS section" }
                section = first
                ended = first == "ENDATA"
                continue
            }
            require(fields.all { it.length < 256 && '$' !in it }) { "unsupported MPS field" }
            when (section) {
                "OBJSENSE" -> {
                    require(fields.size == 1 && first in setOf("MIN", "MAX"))
                    maximize = first == "MAX"
                }
                "ROWS" -> {
                    require(fields.size == 2 && first in setOf("N", "L", "E", "G") && rows.add(fields[1]))
                    if (first == "N") {
                        require(objective == null) { "multiple free rows are unsupported" }
                        objective = fields[1]
                    }
                }
                "COLUMNS" -> {
                    if ("'MARKER'" in fields) {
                        require(fields.size == 3 && fields[1] == "'MARKER'" && fields[2] in setOf("'INTORG'", "'INTEND'"))
                        column = null
                    } else {
                        require(fields.size == 3 || fields.size == 5)
                        if (column != first) {
                            require(columns.add(first)) { "nonconsecutive repeated source column" }
                            column = first
                        }
                        for (i in 1 until fields.size step 2) {
                            require(fields[i] in rows) { "unknown source row" }
                            requireNumber(fields[i + 1])
                            if (fields[i] == objective) require(objectiveColumns.add(first)) { "repeated objective coefficient" }
                        }
                    }
                }
                "RHS", "RANGES" -> {
                    require(fields.size == 3 || fields.size == 5) { "unlabelled vectors are unsupported" }
                    for (i in 1 until fields.size step 2) {
                        require(fields[i] in rows && fields[i] != objective) { "objective RHS offsets are unsupported" }
                        requireNumber(fields[i + 1])
                    }
                }
                "BOUNDS" -> {
                    require(fields.size in 3..4 && first in setOf("LO", "UP", "FX", "LI", "UI", "FR", "MI", "PL", "BV"))
                    if (fields.size == 4) requireNumber(fields[3])
                }
                "INDICATORS" -> require(fields.size == 4 && first == "IF" && fields[3] in setOf("0", "1") &&
                    fields[1] in rows && indicators.add(fields[1]))
                else -> error("unsupported MPS record")
            }
        }
        require(ended && objective != null) { "incomplete MPS source" }
        return Profile(maximize)
    }

    fun requireNumber(text: String) {
        require(text.length in 1..31 && decimal.matches(text)) { "number outside upstream decimal input" }
        val exponent = text.substringAfter('e', text.substringAfter('E', "0")).toIntOrNull()
        require(exponent != null && exponent in -128..128) { "number outside upstream exponent limit" }
    }
}
