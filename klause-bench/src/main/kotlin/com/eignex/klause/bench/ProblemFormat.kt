package com.eignex.klause.bench

import com.eignex.klause.bench.catalog.Format
import com.eignex.klause.formats.dimacs.Dimacs
import com.eignex.klause.formats.json.JsonSchema
import com.eignex.klause.formats.mps.Mps
import com.eignex.klause.formats.mps.toProblem
import com.eignex.klause.formats.opb.Opb
import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.formats.xcsp3.Xcsp3
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.objective.LinearObjective
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

/** A parsed instance lifted into klause's solver representation, plus an optional objective. */
internal data class Ingested(val problem: Problem, val objective: LinearObjective? = null)

/**
 * Turns a file in some [Format] into an [Ingested] klause [Problem]. Only **in-process**
 * formats implement [ingest] here; formats that need an external compile step (MiniZinc) or
 * a net-new parser (XCSP3, SMT-LIB) are handled by their runner and report [inProcess]=false.
 *
 * Each implementation is a thin wrapper over the format parser already shipped in
 * `com.eignex.klause.formats.*` — this layer exists only to dispatch uniformly by [Format].
 */
internal interface ProblemFormat {
    val format: Format
    val inProcess: Boolean
    fun ingest(file: File): Ingested = error("format $format has no in-process ingest; resolve it through its runner")
}

internal object DimacsFormat : ProblemFormat {
    override val format = Format.DIMACS
    override val inProcess = true
    override fun ingest(file: File) = Ingested(Dimacs.parse(file.readText()))
}

internal object WcnfFormat : ProblemFormat {
    override val format = Format.WCNF
    override val inProcess = true
    override fun ingest(file: File): Ingested {
        val wcnf = Dimacs.parseWcnf(file.readText())
        return Ingested(wcnf.problem, wcnf.objective)
    }
}

internal object OpbFormat : ProblemFormat {
    override val format = Format.OPB
    override val inProcess = true
    override fun ingest(file: File): Ingested {
        val opb = Opb.parse(file.readText())
        return Ingested(opb.problem, opb.objective)
    }
}

internal object JsonSchemaFormat : ProblemFormat {
    override val format = Format.JSON_SCHEMA
    override val inProcess = true
    override fun ingest(file: File) = Ingested(JsonSchema.parseProblem(file.readText()))
}

/** Compiled by the `minizinc` CLI, then parsed in-process — see `runner.MiniZincRunner`. */
internal object MiniZincFormat : ProblemFormat {
    override val format = Format.MINIZINC
    override val inProcess = false
}

/** XCSP3 ingest (pragmatic integer CSP/COP subset → klause Problem). Parser lives in
 *  `com.eignex.klause.formats.xcsp3`; this wrapper reads bench-level config knobs. */
internal object Xcsp3Format : ProblemFormat {
    override val format = Format.XCSP3
    override val inProcess = true
    override fun ingest(file: File): Ingested {
        val negTableCap = System.getProperty("klause.bench.xcsp3.negTableCap")?.toLongOrNull() ?: 1_000_000L
        val parsed = Xcsp3.parse(file.readText(), negTableCap)
        return Ingested(parsed.problem, parsed.objective)
    }
}

/** SMT-LIB QF_LIA ingest (pragmatic subset → klause Problem). Parser lives in
 *  `com.eignex.klause.formats.smtlib`; this wrapper reads bench-level config knobs. */
internal object SmtLibFormat : ProblemFormat {
    override val format = Format.SMTLIB_QF_LIA
    override val inProcess = true
    override fun ingest(file: File): Ingested {
        val intBound = System.getProperty("klause.bench.smtlib.intBound")?.toLongOrNull() ?: 100_000L
        val strict = System.getProperty("klause.bench.smtlib.strictBounds")?.toBooleanStrictOrNull() ?: false
        val parsed = SmtLib.parse(file.readText(), -intBound, intBound, strict)
        return Ingested(parsed.problem, parsed.objective)
    }
}

/** MPS (MIP) ingest → klause integer model. Parser + lowering live in `com.eignex.klause.formats.mps`;
 *  this wrapper reads the bench-level search-bound knob and normalises to the minimise-canonical
 *  objective the runner expects (an MPS `OBJSENSE MAX` negates every coefficient). */
internal object MpsFormat : ProblemFormat {
    override val format = Format.MPS
    override val inProcess = true
    override fun ingest(file: File): Ingested {
        val searchBound = System.getProperty("klause.bench.mps.searchBound")?.toLongOrNull() ?: 1_000_000L
        // Bound load-time OBBT: on a large MPS it solves an LP per open-integer variable and can run for
        // many minutes, wedging a sweep before the instance is ever solved. A side left un-tightened when
        // the budget trips is clamped to searchBound (sound — the clamp only loosens).
        val budgetMs = System.getProperty("klause.bench.mps.ingestBudgetMs")?.toLongOrNull() ?: 5_000L
        val cancel = if (budgetMs > 0) Cancellation.after(budgetMs.milliseconds) else Cancellation.Never
        val compiled = Mps.parse(file.readText()).toProblem(searchBound, cancellation = cancel)
        val objective = if (compiled.maximize) compiled.objective?.negated() else compiled.objective
        return Ingested(compiled.problem, objective)
    }

    /** Minimise-canonical view of a raw MPS maximise objective (the bench always minimises). */
    private fun LinearObjective.negated(): LinearObjective = LinearObjective(
        boolWeights = LongArray(boolWeights.size) { -boolWeights[it] },
        intCoefficients = LongArray(intCoefficients.size) { -intCoefficients[it] },
        constant = -constant,
    )
}

internal object Formats {
    private val byFormat: Map<Format, ProblemFormat> = listOf(
        DimacsFormat,
        WcnfFormat,
        OpbFormat,
        JsonSchemaFormat,
        MiniZincFormat,
        Xcsp3Format,
        SmtLibFormat,
        MpsFormat,
    ).associateBy { it.format }

    operator fun get(format: Format): ProblemFormat =
        byFormat[format] ?: error("no ProblemFormat registered for $format")
}
