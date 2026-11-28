package com.eignex.klause.bench

import com.eignex.klause.bench.catalog.Format
import com.eignex.klause.bench.source.CorpusFiles
import com.eignex.klause.compile.compile
import com.eignex.klause.formats.dimacs.Dimacs
import com.eignex.klause.formats.dimacs.toProblem
import com.eignex.klause.formats.json.JsonSchema
import com.eignex.klause.formats.mps.Mps
import com.eignex.klause.formats.mps.toProblem
import com.eignex.klause.formats.opb.Opb
import com.eignex.klause.formats.opb.toProblem
import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.formats.xcsp3.Xcsp3
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.hasFiniteIntegerRanges
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.objective.toLinearObjective
import com.eignex.klause.solver.pipeline.ExecutionOperation
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.ProblemPipeline
import com.eignex.klause.solver.pipeline.SourceProblemRoute
import com.eignex.klause.solver.pipeline.SourceSetup
import com.eignex.klause.solver.pipeline.SourceSetupRequest
import com.eignex.klause.solver.pipeline.sourceRoute
import com.eignex.klause.util.ExecutionControl
import java.io.File

// The objective is minimization-canonical: the XCSP3 and SMT-LIB parsers negate a maximized objective themselves, an
// MPS objective arrives in its source sense. The model is built on first read, so a format whose in-process model needs
// finite bounds still reports its sense for an instance only the subprocess route can solve.
internal class Ingested(
    private val model: Lazy<Problem>,
    val objective: LinearObjective? = null,
    val maximize: Boolean = false,
) {
    constructor(problem: Problem, objective: LinearObjective? = null) : this(lazyOf(problem), objective)

    // Throws when the in-process frontend cannot build the model.
    val problem: Problem get() = model.value
}

internal interface ProblemFormat {
    val format: Format
    val inProcess: Boolean
    fun ingest(file: File): Ingested = error("format $format has no in-process ingest; resolve it through its runner")
}

internal object DimacsFormat : ProblemFormat {
    override val format = Format.DIMACS
    override val inProcess = true
    override fun ingest(file: File) = Ingested(Dimacs.parse(CorpusFiles.readText(file)).toProblem())
}

internal object WcnfFormat : ProblemFormat {
    override val format = Format.WCNF
    override val inProcess = true
    override fun ingest(file: File): Ingested {
        val wcnf = Dimacs.parseWcnf(CorpusFiles.readText(file)).toProblem()
        return Ingested(wcnf.problem, wcnf.objective.toLinearObjective())
    }
}

internal object OpbFormat : ProblemFormat {
    override val format = Format.OPB
    override val inProcess = true
    override fun ingest(file: File): Ingested {
        val opb = Opb.parse(CorpusFiles.readText(file)).toProblem()
        return Ingested(opb.problem, opb.objective?.toLinearObjective())
    }
}

internal object JsonSchemaFormat : ProblemFormat {
    override val format = Format.JSON_SCHEMA
    override val inProcess = true
    override fun ingest(file: File) = Ingested(JsonSchema.parse(CorpusFiles.readText(file)).compile().problem)
}

internal object MiniZincFormat : ProblemFormat {
    override val format = Format.MINIZINC
    override val inProcess = false
}

internal object Xcsp3Format : ProblemFormat {
    override val format = Format.XCSP3
    override val inProcess = true
    override fun ingest(file: File): Ingested {
        val parsed = Xcsp3.parse(CorpusFiles.readText(file))
        return Ingested(
            lazyOf(parsed.problem),
            parsed.objective?.toLinearObjective(),
            parsed.sense == ObjectiveSense.MAXIMIZE,
        )
    }
}

internal object SmtLibFormat : ProblemFormat {
    override val format = Format.SMTLIB
    override val inProcess = true
    override fun ingest(file: File): Ingested {
        val parsed = SmtLib.parse(CorpusFiles.readText(file))
        require(parsed.quadraticObjective == null) { "finite in-process benchmark requires an affine source objective" }
        val objective = parsed.objective?.toLinearObjective()
        val maximize = parsed.sense == ObjectiveSense.MAXIMIZE
        return Ingested(
            lazy {
                val source = parsed.source.originalSource as? Problem
                check(source?.hasFiniteIntegerRanges() == true) {
                    "${file.name}: in-process benchmarks require a finite factor source; use the CLI for open models"
                }
                val operation = if (objective != null || parsed.source.embeddedObjectiveSense != null) {
                    ExecutionOperation.OPTIMIZE
                } else ExecutionOperation.SATISFY
                val invocation = SourceSetup.prepare(SourceSetupRequest(
                    parsed.source, operation, FiniteEngine.BACKTRACK, objective, maximize,
                    boundCancellation = ExecutionControl { true },
                ))
                check(invocation.route is SourceProblemRoute.Finite) {
                    "${file.name}: in-process benchmarks require finite source bounds; use the CLI for open models"
                }
                invocation.finiteShape().problem.bake()
            },
            if (maximize) objective?.negated() else objective,
            parsed.sense == ObjectiveSense.MAXIMIZE,
        )
    }
}

// An MPS `OBJSENSE MAX` negates every coefficient to reach the minimization-canonical objective.
internal object MpsFormat : ProblemFormat {
    override val format = Format.MPS
    override val inProcess = true
    override fun ingest(file: File): Ingested {
        val compiled = Mps.parse(CorpusFiles.readText(file)).toProblem()
        return Ingested(
            lazy { compiled.model.requireFiniteBenchModel(file) },
            compiled.objective?.toLinearObjective()?.let { if (compiled.maximize) it.negated() else it },
            compiled.maximize,
        )
    }
}

private fun Problem.requireFiniteBenchModel(file: File): Problem {
    check(sourceRoute() == ProblemPipeline.FINITE_CP) {
        "${file.name}: in-process benchmarks require finite integer bounds; use the CLI theory route for open models"
    }
    return bake()
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
