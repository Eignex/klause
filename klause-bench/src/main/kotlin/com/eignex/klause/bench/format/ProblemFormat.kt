package com.eignex.klause.bench.format

import com.eignex.klause.bench.catalog.Format
import com.eignex.klause.formats.dimacs.Dimacs
import com.eignex.klause.formats.flatzinc.parseFlatZinc
import com.eignex.klause.formats.json.JsonSchema
import com.eignex.klause.formats.opb.Opb
import com.eignex.klause.solver.Objective
import com.eignex.klause.solver.Problem
import java.io.File

/** A parsed instance lifted into klause's solver representation, plus an optional objective. */
data class Ingested(val problem: Problem, val objective: Objective? = null)

/**
 * Turns a file in some [Format] into an [Ingested] klause [Problem]. Only **in-process**
 * formats implement [ingest] here; formats that need an external compile step (MiniZinc) or
 * a net-new parser (XCSP3, SMT-LIB) are handled by their runner and report [inProcess]=false.
 *
 * Each implementation is a thin wrapper over the format parser already shipped in
 * `com.eignex.klause.formats.*` — this layer exists only to dispatch uniformly by [Format].
 */
interface ProblemFormat {
    val format: Format
    val inProcess: Boolean
    fun ingest(file: File): Ingested =
        error("format $format has no in-process ingest; resolve it through its runner")
}

object DimacsFormat : ProblemFormat {
    override val format = Format.DIMACS
    override val inProcess = true
    override fun ingest(file: File) = Ingested(Dimacs.parse(file.readText()))
}

object OpbFormat : ProblemFormat {
    override val format = Format.OPB
    override val inProcess = true
    override fun ingest(file: File): Ingested {
        val opb = Opb.parse(file.readText())
        return Ingested(opb.problem, opb.objective)
    }
}

object JsonSchemaFormat : ProblemFormat {
    override val format = Format.JSON_SCHEMA
    override val inProcess = true
    override fun ingest(file: File) = Ingested(JsonSchema.parseProblem(file.readText()))
}

object FlatZincFormat : ProblemFormat {
    override val format = Format.FLATZINC
    override val inProcess = true
    // Objective extraction from the solve directive is deferred to the MiniZinc optimization
    // path (phase 2); satisfaction FZN is handled here.
    override fun ingest(file: File) = Ingested(parseFlatZinc(file.readText()).problem)
}

/** Compiled by the `minizinc` CLI, then parsed in-process — see `runner.MiniZincRunner`. */
object MiniZincFormat : ProblemFormat {
    override val format = Format.MINIZINC
    override val inProcess = false
}

/** XCSP3 ingest (pragmatic integer CSP/COP subset → klause Problem). Parser lives in
 *  `com.eignex.klause.formats.xcsp3`; this wrapper reads bench-level config knobs. */
object Xcsp3Format : ProblemFormat {
    override val format = Format.XCSP3
    override val inProcess = true
    override fun ingest(file: File): Ingested {
        val negTableCap = System.getProperty("klause.bench.xcsp3.negTableCap")?.toLongOrNull() ?: 1_000_000L
        val parsed = com.eignex.klause.formats.xcsp3.Xcsp3.parse(file.readText(), negTableCap)
        return Ingested(parsed.problem, parsed.objective)
    }
}

/** SMT-LIB QF_LIA ingest (pragmatic subset → klause Problem). Parser lives in
 *  `com.eignex.klause.formats.smtlib`; this wrapper reads bench-level config knobs. */
object SmtLibFormat : ProblemFormat {
    override val format = Format.SMTLIB_QF_LIA
    override val inProcess = true
    override fun ingest(file: File): Ingested {
        val intBound = System.getProperty("klause.bench.smtlib.intBound")?.toIntOrNull() ?: 100_000
        val strict = System.getProperty("klause.bench.smtlib.strictBounds")?.toBooleanStrictOrNull() ?: false
        val parsed = com.eignex.klause.formats.smtlib.SmtLibQfLia.parse(file.readText(), intBound, strict)
        return Ingested(parsed.problem, parsed.objective)
    }
}

object Formats {
    private val byFormat: Map<Format, ProblemFormat> = listOf(
        DimacsFormat, OpbFormat, JsonSchemaFormat, FlatZincFormat,
        MiniZincFormat, Xcsp3Format, SmtLibFormat,
    ).associateBy { it.format }

    operator fun get(format: Format): ProblemFormat =
        byFormat[format] ?: error("no ProblemFormat registered for $format")
}
