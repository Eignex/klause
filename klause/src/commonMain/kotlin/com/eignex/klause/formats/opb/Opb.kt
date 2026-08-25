package com.eignex.klause.formats.opb

import com.eignex.klause.formats.FormatException
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.lowering.OpbLowering
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.CharSource
import com.eignex.klause.util.StringCharSource

/** Raised when an OPB/WBO document is malformed, so a caller can catch it via [FormatException] like
 *  the other input formats. */
class OpbFormatException(msg: String) : FormatException("OPB", msg)

/** Parsed OPB instance and optional objective. */
data class OpbProblem(
    /** Compiled solver problem. */
    val problem: Problem,
    /** Objective, or null for satisfaction instances. */
    val objective: LinearObjective?,
    /** AND-indicator definitions for product terms — the local-search bool functional cone. */
    val boolFolds: List<DefinitionalSweep.BoolFoldSpec> = emptyList(),
    /** Count of declared `x1..xN` variables. [Problem.numBoolVars] also counts the Tseitin/soft
     *  indicators appended above them, so a model listing must use this to omit the aux variables. */
    val numDeclaredVars: Int = 0,
)

/** OPB/WBO format facade. Syntax parsing is format-owned; factor construction is [OpbLowering]-owned. */
object Opb {

    /** Parse OPB [text] into an [OpbProblem]. */
    fun parse(text: String): OpbProblem = parse(StringCharSource(text))

    /** Parse an OPB [source] into an [OpbProblem], consuming it line by line. */
    fun parse(source: CharSource): OpbProblem = OpbLowering.lower(OpbSyntax.parse(source))
}
