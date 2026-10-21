package com.eignex.klause.bench.metric

import java.math.BigDecimal

internal object MpsSolcheckerInput {
    private val decimal = Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")
    private val sections =
        setOf("NAME", "OBJSENSE", "ROWS", "COLUMNS", "RHS", "RANGES", "BOUNDS", "INDICATORS", "ENDATA")

    data class Profile(
        val maximize: Boolean,
        val text: String,
        val insertedHeaders: List<String>,
        val reorderedBoundPairs: Int,
    )

    // The upstream reader needs a restricted input profile to preserve source obligations.
    fun qualify(source: String, cancelled: () -> Boolean = { false }): Profile {
        var section = ""
        var objective: String? = null
        var maximize = false
        var ended = false
        var named = false
        var rhs = false
        var column: String? = null
        val columns = mutableSetOf<String>()
        val objectiveColumns = mutableSetOf<String>()
        val rows = mutableSetOf<String>()
        val indicators = mutableSetOf<String>()
        val vectors = mutableMapOf<String, String>()
        val vectorRows = mutableMapOf<String, MutableSet<String>>()
        val boundKinds = mutableMapOf<String, MutableSet<String>>()
        val reorderableUpper = mutableMapOf<String, Int>()
        val lines = Regex("(?<=\\n)|(?<=\\r)(?!\\n)").split(source).toMutableList()
        var reorderedBoundPairs = 0
        for ((lineIndex, record) in lines.withIndex()) {
            val line = record.trimEnd('\r', '\n')
            check(!cancelled()) { "MPS admission allowance exhausted" }
            require(line.length < 1023 && line.all { it == '\t' || it.code in 32..126 }) { "unsupported MPS line" }
            if (line.isBlank() || line.startsWith('*')) continue
            require(!ended) { "records after ENDATA are unsupported" }
            val fields = line.trim().split(Regex("\\s+"))
            val first = fields.first()
            if (!line.first().isWhitespace()) {
                require(first in sections && (first == "NAME" || fields.size == 1)) { "unsupported MPS section" }
                section = first
                ended = first == "ENDATA"
                named = named || first == "NAME"
                rhs = rhs || first == "RHS"
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
                        require(
                            fields.size == 3 && fields[1] == "'MARKER'" && fields[2] in setOf("'INTORG'", "'INTEND'"),
                        )
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
                            if (fields[i] == objective) {
                                require(
                                    objectiveColumns.add(first),
                                ) { "repeated objective coefficient" }
                            }
                        }
                    }
                }

                "RHS", "RANGES" -> {
                    require(fields.size == 3 || fields.size == 5) { "unlabelled vectors are unsupported" }
                    require(vectors.getOrPut(section) { first } == first) { "multiple vectors are unsupported" }
                    for (i in 1 until fields.size step 2) {
                        require(fields[i] in rows && fields[i] != objective) { "objective RHS offsets are unsupported" }
                        require(vectorRows.getOrPut(section) { mutableSetOf() }.add(fields[i])) {
                            "repeated vector rows are unsupported"
                        }
                        requireNumber(fields[i + 1])
                    }
                }

                "BOUNDS" -> {
                    require(fields.size in 3..4 && first in setOf("LO", "UP", "FX", "LI", "UI", "FR", "MI", "PL", "BV"))
                    require(vectors.getOrPut(section) { fields[1] } == fields[1]) { "multiple bound vectors" }
                    if (fields.size == 4) requireNumber(fields[3])
                    val kinds = boundKinds.getOrPut(fields[2]) { mutableSetOf() }
                    val upperIndex = reorderableUpper.remove(fields[2])
                    require((first != "BV" || kinds.isEmpty()) && "BV" !in kinds) { "mixed binary bounds" }
                    if (first in setOf("LO", "LI", "MI") && "UP" in kinds) {
                        require(
                            first == "LO" && upperIndex != null && fields.size == 4 &&
                                BigDecimal(fields[3]).compareTo(BigDecimal.ZERO) == 0,
                        ) {
                            "lower bounds following upper bounds are unsupported"
                        }
                        // MIPLIB resets a marker-binary upper side on LO; LO 0 then UP 1 preserves both sides.
                        lines[lineIndex] = lines[upperIndex]
                        lines[upperIndex] = record
                        reorderedBoundPairs++
                    }
                    if (
                        first == "UP" && kinds.isEmpty() && fields.size == 4 &&
                        BigDecimal(fields[3]).compareTo(BigDecimal.ONE) == 0
                    ) {
                        reorderableUpper[fields[2]] = lineIndex
                    }
                    kinds += first
                }

                "INDICATORS" -> require(
                    fields.size == 4 && first == "IF" && fields[3] in setOf("0", "1") &&
                        fields[1] in rows && indicators.add(fields[1]),
                )

                else -> error("unsupported MPS record")
            }
        }
        require(ended && objective != null) { "incomplete MPS source" }
        val headers = mutableListOf<String>()
        var text = lines.joinToString("")
        if (!named) {
            text = "NAME KLAUSE_SOURCE_CHECK\n$text"
            headers += "NAME"
        }
        if (!rhs) {
            val position = requireNotNull(Regex("(?m)^(RANGES|BOUNDS|INDICATORS|ENDATA)[ \\t]*\\r?$").find(text))
                .range.first
            text = text.substring(0, position) + "RHS\n" + text.substring(position)
            headers += "RHS"
        }
        check(!cancelled()) { "MPS admission allowance exhausted" }
        return Profile(maximize, text, headers, reorderedBoundPairs)
    }

    fun requireNumber(text: String) {
        require(text.length in 1..31 && decimal.matches(text)) { "number outside upstream decimal input" }
        val exponent = text.substringAfter('e', text.substringAfter('E', "0")).toIntOrNull()
        require(exponent != null && exponent in -128..128) { "number outside upstream exponent limit" }
    }
}
