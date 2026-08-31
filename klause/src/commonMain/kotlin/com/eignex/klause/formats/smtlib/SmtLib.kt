package com.eignex.klause.formats.smtlib

import com.eignex.klause.config.DEFAULT_UNBOUNDED_INT_HI
import com.eignex.klause.config.DEFAULT_UNBOUNDED_INT_LO
import com.eignex.klause.ir.LinearObjectiveSpec
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.util.CharSource
import com.eignex.klause.util.StringCharSource

/** An SMT-LIB source decoded and lowered into klause's representation. */
data class SmtLibProblem(
    /** Compiled model. Open integer sides remain open until a finite-search backend materializes them. */
    val model: Problem,
    /** Objective, or null for satisfaction instances. */
    val objective: LinearObjectiveSpec?,
    /** Declared `Int` variable name to int id. */
    val intVarNames: Map<String, Int> = emptyMap(),
    /** Declared `Bool` variable name to bool id. */
    val boolVarNames: Map<String, Int> = emptyMap(),
    /** Declared `Real` variable name to LP-only real id. */
    val realVarNames: Map<String, Int> = emptyMap(),
    /** The objective's optimisation sense (minimise for satisfaction instances, which have none). */
    val sense: ObjectiveSense = ObjectiveSense.MINIMIZE,
)

/** SMT-LIB linear-arithmetic format facade. */
object SmtLib {
    /** Decode and lower SMT-LIB linear-arithmetic [text]. */
    fun parse(
        text: String,
        unboundedIntLo: Long = DEFAULT_UNBOUNDED_INT_LO,
        unboundedIntHi: Long = DEFAULT_UNBOUNDED_INT_HI,
        strictBounds: Boolean = false,
    ): SmtLibProblem = parse(StringCharSource(text), unboundedIntLo, unboundedIntHi, strictBounds)

    /** Decode and lower a streamed SMT-LIB [source], retaining only one top-level command at a time. */
    fun parse(
        source: CharSource,
        unboundedIntLo: Long = DEFAULT_UNBOUNDED_INT_LO,
        unboundedIntHi: Long = DEFAULT_UNBOUNDED_INT_HI,
        strictBounds: Boolean = false,
    ): SmtLibProblem {
        val builder = Compiler.Builder(unboundedIntLo, unboundedIntHi, strictBounds)
        val reader = SExprReader(source)
        while (true) builder.command(reader.readCommandOrNull() ?: break)
        return builder.build()
    }
}
