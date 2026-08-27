package com.eignex.klause.bench

import com.eignex.klause.bench.catalog.Format
import com.eignex.klause.compile.compile
import com.eignex.klause.formats.dimacs.Dimacs
import com.eignex.klause.formats.json.JsonSchema
import com.eignex.klause.formats.mps.Mps
import com.eignex.klause.formats.opb.Opb
import com.eignex.klause.lowering.dimacs.toProblem
import com.eignex.klause.lowering.mps.toProblem
import com.eignex.klause.lowering.opb.toProblem
import com.eignex.klause.lowering.smtlib.SmtLib
import com.eignex.klause.lowering.xcsp3.Xcsp3
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.ProblemSpec
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.pipeline.ProblemPipeline
import com.eignex.klause.solver.pipeline.sourceRoute
import java.io.File

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
    override fun ingest(file: File) = Ingested(Dimacs.parse(file.readText()).toProblem())
}

internal object WcnfFormat : ProblemFormat {
    override val format = Format.WCNF
    override val inProcess = true
    override fun ingest(file: File): Ingested {
        val wcnf = Dimacs.parseWcnf(file.readText()).toProblem()
        return Ingested(wcnf.problem, wcnf.objective)
    }
}

internal object OpbFormat : ProblemFormat {
    override val format = Format.OPB
    override val inProcess = true
    override fun ingest(file: File): Ingested {
        val opb = Opb.parse(file.readText()).toProblem()
        return Ingested(opb.problem, opb.objective)
    }
}

internal object JsonSchemaFormat : ProblemFormat {
    override val format = Format.JSON_SCHEMA
    override val inProcess = true
    override fun ingest(file: File) = Ingested(JsonSchema.parse(file.readText()).compile().problem)
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
        val parsed = Xcsp3.parse(file.readText())
        return Ingested(parsed.problem, parsed.objective)
    }
}

/** SMT-LIB ingest (pragmatic subset → klause Problem). Parser lives in
 *  `com.eignex.klause.formats.smtlib`; this wrapper reads bench-level config knobs. */
internal object SmtLibFormat : ProblemFormat {
    override val format = Format.SMTLIB
    override val inProcess = true
    override fun ingest(file: File): Ingested {
        val strict = System.getProperty("klause.bench.smtlib.strictBounds")?.toBooleanStrictOrNull() ?: false
        val parsed = SmtLib.parse(file.readText(), strictBounds = strict)
        return Ingested(parsed.model.requireFiniteBenchModel(file), parsed.objective)
    }
}

/** MPS (MIP) ingest → klause integer model. The parser lives in `com.eignex.klause.formats.mps`; lowering
 *  lives in `com.eignex.klause.lowering.mps`.
 *  this wrapper normalises to the minimise-canonical objective the runner expects (an MPS `OBJSENSE MAX`
 *  negates every coefficient). */
internal object MpsFormat : ProblemFormat {
    override val format = Format.MPS
    override val inProcess = true
    override fun ingest(file: File): Ingested {
        val compiled = Mps.parse(file.readText()).toProblem()
        val problem = compiled.model.requireFiniteBenchModel(file)
        val objective = if (compiled.maximize) compiled.objective?.negated() else compiled.objective
        return Ingested(problem, objective)
    }

    /** Minimise-canonical view of a raw MPS maximise objective (the bench always minimises). */
    private fun LinearObjective.negated(): LinearObjective = LinearObjective(
        boolWeights = LongArray(boolWeights.size) { -boolWeights[it] },
        intCoefficients = LongArray(intCoefficients.size) { -intCoefficients[it] },
        constant = -constant,
    )
}

private fun ProblemSpec.requireFiniteBenchModel(file: File): Problem {
    check(sourceRoute() == ProblemPipeline.FINITE_CP) {
        "${file.name}: in-process benchmarks require finite integer bounds; use the CLI theory route for open models"
    }
    return materializeFiniteBounds()
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
