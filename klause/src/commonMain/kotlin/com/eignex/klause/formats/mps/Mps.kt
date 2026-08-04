package com.eignex.klause.formats.mps

import com.eignex.klause.formats.FormatException
import com.eignex.klause.formats.ObjectiveSense
import com.eignex.klause.formats.splitWhitespace
import com.eignex.klause.util.CharSource
import com.eignex.klause.util.StringCharSource
import com.eignex.klause.util.lineSequence

/** Raised when an MPS file is malformed or uses a construct outside the supported subset. */
class MpsFormatException(msg: String) : FormatException("MPS", msg)

/**
 * A decision variable. [integer] marks a column declared inside an `INTORG`/`INTEND` marker pair (or
 * given an integer bound type `UI`/`LI`/`BV`). Bounds are resolved to their effective values, with a
 * `null` standing for infinity: `lower == null` is `-∞`, `upper == null` is `+∞`.
 */
data class MpsVar(
    /** Column (variable) name. */
    val name: String,
    /** True for a column declared integer (an `INTORG`/`INTEND` marker, or a `UI`/`LI`/`BV` bound). */
    val integer: Boolean,
    /** Lower bound; `null` is `-∞`. */
    val lower: Double?,
    /** Upper bound; `null` is `+∞`. */
    val upper: Double?,
)

/**
 * A constraint row as a two-sided linear bound `lower ≤ Σ coeffs(i)·var(indices(i)) ≤ upper`. The MPS
 * row type and any `RANGES` entry are resolved into [lower]/[upper] (`null` = the side is open): an
 * `L` row is `(null, rhs)`, `G` is `(rhs, null)`, `E` is `(rhs, rhs)`, and a ranged row is the
 * corresponding finite interval. [indices] point into [MpsModel.variables]; [coeffs] is parallel.
 */
data class MpsConstraint(
    /** Row name. */
    val name: String,
    /** Variable indices into [MpsModel.variables], parallel to [coeffs]. */
    val indices: IntArray,
    /** Coefficients, parallel to [indices]. */
    val coeffs: DoubleArray,
    /** Lower bound of the row's linear form; `null` = open below. */
    val lower: Double?,
    /** Upper bound of the row's linear form; `null` = open above. */
    val upper: Double?,
)

/** The objective (the first free `N` row): a sparse linear form plus a [constant] (from an `RHS`
 *  entry against the objective row, negated per the MPS convention). Empty when the file has no `N` row. */
data class MpsObjective(
    /** Objective row name (empty when the file has no `N` row). */
    val name: String,
    /** Variable indices into [MpsModel.variables], parallel to [coeffs]. */
    val indices: IntArray,
    /** Coefficients, parallel to [indices]. */
    val coeffs: DoubleArray,
    /** Constant term (an `RHS` against the objective row, negated). */
    val constant: Double,
)

/** A parsed MPS instance in its native double-valued form, before lowering to a klause `Problem`. */
data class MpsModel(
    /** Problem name from the `NAME` section (empty if absent). */
    val name: String,
    /** Objective optimisation sense. */
    val sense: ObjectiveSense,
    /** The objective's linear form and constant. */
    val objective: MpsObjective,
    /** Declared variables, in declaration order (constraint/objective indices point here). */
    val variables: List<MpsVar>,
    /** Constraint rows (the `N` objective row is excluded). */
    val constraints: List<MpsConstraint>,
)

/**
 * Parser for the MPS (Mathematical Programming System) MIP/LP format. Reads the standard sections —
 * `NAME`, `OBJSENSE`, `ROWS`, `COLUMNS` (with `MARKER`/`INTORG`/`INTEND` integer sections), `RHS`,
 * `RANGES`, `BOUNDS` — into an [MpsModel]. Whitespace ("free") tokenisation is used, which handles the
 * common case; fixed-column files whose names embed spaces are not supported.
 *
 * Bound conventions: a variable defaults to `[0, +∞)`. `RANGES` and `RHS` follow the standard sign
 * rules (see [applyRange]). An integer column with no explicit bound keeps `[0, +∞)` — the MPS
 * standard is ambiguous here (some readers use `[0, 1]`), so the choice is made explicit and left to
 * the lowering step to reconcile with whichever oracle a benchmark uses.
 */
object Mps {

    private enum class Section { NONE, NAME, OBJSENSE, ROWS, COLUMNS, RHS, RANGES, BOUNDS, ENDATA }

    private enum class RowType { OBJECTIVE, LE, GE, EQ, FREE }

    private class Row(val name: String, val type: RowType) {
        /** Sparse column coefficients, keyed by variable index. */
        val coeffs = LinkedHashMap<Int, Double>()
        var rhs = 0.0
        var range: Double? = null
    }

    private class Var(val name: String, val index: Int) {
        var integer = false
        var lower: Double? = 0.0
        var upper: Double? = null // +infinity by default
        var explicitLower = false
        var explicitUpper = false
    }

    /** Parse MPS [text] into an [MpsModel]. */
    fun parse(text: String): MpsModel = parse(StringCharSource(text))

    /** Parse an MPS [source] into an [MpsModel], consuming it line by line. */
    fun parse(source: CharSource): MpsModel {
        val rows = ArrayList<Row>()
        val rowByName = HashMap<String, Row>()
        val vars = ArrayList<Var>()
        val varByName = HashMap<String, Var>()
        var name = ""
        var sense = ObjectiveSense.MINIMIZE
        var objectiveRow: Row? = null
        var section = Section.NONE
        var inIntegerMarker = false

        for (rawLine in source.lineSequence()) {
            // Comments start with `*`; blank lines are skipped. A line with no leading whitespace and a
            // recognised keyword opens a new section, otherwise it is a data line for the current one.
            if (rawLine.isBlank() || rawLine.startsWith("*")) continue
            val fields = rawLine.trim().splitWhitespace()
            if (!rawLine[0].isWhitespace()) {
                section = openSection(fields).also {
                    if (it == Section.NAME) name = fields.getOrElse(1) { "" }
                }
                // OBJSENSE may state MAX/MAXIMIZE on the header line itself.
                if (section == Section.OBJSENSE && isMaximize(fields.getOrNull(1))) sense = ObjectiveSense.MAXIMIZE
                if (objectiveRow == null) objectiveRow = rows.firstOrNull { it.type == RowType.OBJECTIVE }
                continue
            }

            when (section) {
                Section.OBJSENSE -> if (isMaximize(fields.getOrNull(0))) sense = ObjectiveSense.MAXIMIZE

                Section.ROWS -> readRow(fields, rows, rowByName)

                Section.COLUMNS ->
                    inIntegerMarker =
                        readColumn(fields, rowByName, vars, varByName, inIntegerMarker)

                Section.RHS -> readRhs(fields, rowByName)

                Section.RANGES -> readRanges(fields, rowByName)

                Section.BOUNDS -> readBounds(fields, varByName)

                else -> Unit // NAME data / ENDATA / NONE
            }
        }

        return build(name, sense, rows, vars, objectiveRow)
    }

    /** Recognise which section a header line opens, from its first token. */
    private fun openSection(fields: List<String>): Section = when (fields[0].uppercase()) {
        "NAME" -> Section.NAME
        "OBJSENSE" -> Section.OBJSENSE
        "ROWS" -> Section.ROWS
        "COLUMNS" -> Section.COLUMNS
        "RHS" -> Section.RHS
        "RANGES" -> Section.RANGES
        "BOUNDS" -> Section.BOUNDS
        "ENDATA" -> Section.ENDATA
        else -> throw MpsFormatException("unknown section header '${fields[0]}'")
    }

    private fun isMaximize(token: String?): Boolean =
        token != null && (token.equals("MAX", true) || token.equals("MAXIMIZE", true))

    /** A ROWS data line: `<type> <name>`, type in `N`(free/objective) `L`(≤) `G`(≥) `E`(=). */
    private fun readRow(fields: List<String>, rows: MutableList<Row>, rowByName: MutableMap<String, Row>) {
        if (fields.size < 2) {
            throw MpsFormatException(
                "ROWS line needs a type and a name: '${fields.joinToString(" ")}'",
            )
        }
        // The first free (`N`) row is the objective; any further `N` rows are ignored non-constraint rows.
        val type = when (fields[0].uppercase()) {
            "N" -> if (rows.none { it.type == RowType.OBJECTIVE }) RowType.OBJECTIVE else RowType.FREE
            "L" -> RowType.LE
            "G" -> RowType.GE
            "E" -> RowType.EQ
            else -> throw MpsFormatException("unknown ROWS type '${fields[0]}'")
        }
        val row = Row(fields[1], type)
        rows.add(row)
        rowByName[fields[1]] = row
    }

    /** A COLUMNS data line: either a `MARKER`/`INTORG`/`INTEND` integer-section toggle (returns the new
     *  in-integer-section flag) or `<col> <row> <val> [<row> <val>]` accumulating column coefficients. */
    private fun readColumn(
        fields: List<String>,
        rowByName: Map<String, Row>,
        vars: MutableList<Var>,
        varByName: MutableMap<String, Var>,
        inMarker: Boolean,
    ): Boolean {
        if (fields.any { it.equals("'MARKER'", true) || it == "MARKER" }) {
            return when {
                fields.any { it.contains("INTORG", true) } -> true
                fields.any { it.contains("INTEND", true) } -> false
                else -> inMarker
            }
        }
        val colName = fields[0]
        val v = varByName.getOrPut(colName) { Var(colName, vars.size).also { vars.add(it) } }
        if (inMarker) v.integer = true
        // Row/value pairs follow the column name; one or two per line.
        var i = 1
        while (i < fields.size) {
            val rowName = fields[i]
            val value = fields.getOrNull(i + 1)
                ?: throw MpsFormatException("COLUMNS entry '$colName' missing value for row '$rowName'")
            val row = rowByName[rowName] ?: throw MpsFormatException("COLUMNS entry references unknown row '$rowName'")
            row.coeffs[v.index] = (row.coeffs[v.index] ?: 0.0) + parseNum(value, "coefficient")
            i += 2
        }
        return inMarker
    }

    /** An RHS data line: `[<setname>] <row> <val> [<row> <val>]`. A value against the objective row is
     *  kept on its [Row.rhs] and folded into the objective constant (negated) by [build]. */
    private fun readRhs(fields: List<String>, rowByName: Map<String, Row>) =
        forEachRowValue(fields, rowByName, "RHS") { row, value -> row.rhs = value }

    /** A RANGES data line: `[<setname>] <row> <val> [<row> <val>]`. */
    private fun readRanges(fields: List<String>, rowByName: Map<String, Row>) =
        forEachRowValue(fields, rowByName, "RANGES") { row, value -> row.range = value }

    /** Apply a callback over the `(row, value)` pairs of an RHS/RANGES line, tolerating an optional
     *  leading set-name column (present in most files, absent in some). */
    private inline fun forEachRowValue(
        fields: List<String>,
        rowByName: Map<String, Row>,
        section: String,
        apply: (Row, Double) -> Unit,
    ) {
        // Heuristic for the optional set name: if the first token is a known row, there is no set name.
        val start = if (fields.isNotEmpty() && rowByName.containsKey(fields[0])) 0 else 1
        var i = start
        while (i < fields.size) {
            val rowName = fields[i]
            val value = fields.getOrNull(i + 1)
                ?: throw MpsFormatException("$section entry missing value for row '$rowName'")
            val row = rowByName[rowName] ?: throw MpsFormatException("$section references unknown row '$rowName'")
            apply(row, parseNum(value, "$section value"))
            i += 2
        }
    }

    /** A BOUNDS data line: `<type> [<setname>] <col> [<val>]`. */
    private fun readBounds(fields: List<String>, varByName: Map<String, Var>) {
        if (fields.size < 2) throw MpsFormatException("BOUNDS line too short: '${fields.joinToString(" ")}'")
        val type = fields[0].uppercase()
        // A value-less bound (FR/MI/PL/BV) ends at the column; the rest carry a trailing value. This
        // tolerates the optional bound-set name between the type and the column.
        val valueless = type == "FR" || type == "MI" || type == "PL" || type == "BV"
        val col = if (valueless) fields.last() else fields[fields.size - 2]
        val v = varByName[col] ?: throw MpsFormatException("BOUNDS references unknown column '$col'")
        applyBound(v, type, if (valueless) 0.0 else parseNum(fields.last(), "bound value"))
    }

    /** Apply one BOUNDS entry of [type] with [value] to variable [v] (the standard MPS bound types;
     *  a `null` bound is left standing for infinity). */
    private fun applyBound(v: Var, type: String, value: Double) {
        when (type) {
            "UP" -> {
                v.upper = value
                v.explicitUpper = true
                if (value < 0 && !v.explicitLower) v.lower = null
            }

            "LO" -> {
                v.lower = value
                v.explicitLower = true
            }

            "FX" -> {
                v.lower = value
                v.upper = value
                v.explicitLower = true
                v.explicitUpper = true
            }

            "FR" -> {
                v.lower = null
                v.upper = null
                v.explicitLower = true
                v.explicitUpper = true
            }

            "MI" -> {
                v.lower = null
                v.explicitLower = true
            }

            "PL" -> {
                v.upper = null
                v.explicitUpper = true
            }

            "BV" -> {
                v.integer = true
                v.lower = 0.0
                v.upper = 1.0
                v.explicitLower = true
                v.explicitUpper = true
            }

            "UI" -> {
                v.integer = true
                v.upper = value
                v.explicitUpper = true
            }

            "LI" -> {
                v.integer = true
                v.lower = value
                v.explicitLower = true
            }

            else -> throw MpsFormatException("unknown BOUNDS type '$type'")
        }
    }

    private fun build(
        name: String,
        sense: ObjectiveSense,
        rows: List<Row>,
        vars: List<Var>,
        objectiveRow: Row?,
    ): MpsModel {
        val variables = vars.map { MpsVar(it.name, it.integer, it.lower, it.upper) }
        val constraints = rows.filter { it.type == RowType.LE || it.type == RowType.GE || it.type == RowType.EQ }
            .map { row ->
                val indices = row.coeffs.keys.toIntArray()
                val coeffs = DoubleArray(indices.size) { row.coeffs.getValue(indices[it]) }
                val (lo, hi) = bounds(row)
                MpsConstraint(row.name, indices, coeffs, lo, hi)
            }
        val objective = if (objectiveRow == null) {
            MpsObjective("", IntArray(0), DoubleArray(0), 0.0)
        } else {
            val indices = objectiveRow.coeffs.keys.toIntArray()
            val coeffs = DoubleArray(indices.size) { objectiveRow.coeffs.getValue(indices[it]) }
            // An objective RHS `b` names a constant `-b` on the objective's value (it sits on the rhs side).
            MpsObjective(objectiveRow.name, indices, coeffs, -objectiveRow.rhs)
        }
        return MpsModel(name, sense, objective, variables, constraints)
    }

    /** Resolve a row's `[lower, upper]` from its type, rhs and optional range (`null` = open side). */
    private fun bounds(row: Row): Pair<Double?, Double?> {
        val r = row.range
        if (r == null) {
            return when (row.type) {
                RowType.LE -> null to row.rhs
                RowType.GE -> row.rhs to null
                RowType.EQ -> row.rhs to row.rhs
                else -> null to null
            }
        }
        return applyRange(row.type, row.rhs, r)
    }

    /**
     * The standard `RANGES` sign rules turning a one-sided row plus a range `R` into a finite interval:
     *  - `L` (`≤ rhs`): `[rhs − |R|, rhs]`
     *  - `G` (`≥ rhs`): `[rhs, rhs + |R|]`
     *  - `E` (`= rhs`): `[rhs, rhs + R]` when `R ≥ 0`, else `[rhs + R, rhs]`.
     */
    private fun applyRange(type: RowType, rhs: Double, range: Double): Pair<Double?, Double?> {
        val abs = if (range < 0) -range else range
        return when (type) {
            RowType.LE -> (rhs - abs) to rhs
            RowType.GE -> rhs to (rhs + abs)
            RowType.EQ -> if (range >= 0) rhs to (rhs + range) else (rhs + range) to rhs
            else -> null to null
        }
    }

    private fun parseNum(token: String, role: String): Double =
        token.toDoubleOrNull() ?: throw MpsFormatException("$role not a number: '$token'")
}
