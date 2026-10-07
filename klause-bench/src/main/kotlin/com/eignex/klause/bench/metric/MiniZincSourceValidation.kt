package com.eignex.klause.bench.metric

import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.runner.MZN_RANDOM_SEED
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.formats.flatzinc.FlatZincProgram
import com.eignex.klause.formats.flatzinc.SolveDirective
import com.eignex.klause.formats.flatzinc.parseFlatZinc
import com.eignex.klause.ir.Lit
import kotlinx.serialization.Serializable
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

@Serializable
internal data class SourceValidation(val status: String, val reason: String)

internal object MiniZincSourceValidation {
    private const val TIMEOUT_SECONDS = 10L
    private val ASSIGNMENT = Regex("^\\s*[A-Za-z_][A-Za-z_0-9]*\\s*=")

    fun validate(ref: ProblemRef, rawOutput: String, objective: Double?): SourceValidation {
        val candidate = candidate(rawOutput) ?: return SourceValidation("unknown", "no complete DZN solution")
        val directory = Files.createTempDirectory("klause-source-check-").toFile()
        return runCatching {
            val data = File(directory, "candidate.dzn").apply { writeText(candidate) }
            val fzn = File(directory, "checked.fzn")
            val log = File(directory, "compile.log")
            val command = buildList {
                addAll(listOf("minizinc", "--solver", "org.minizinc.mzn-fzn", "-c", "--allow-multiple-assignments"))
                addAll(listOf("--random-seed", MZN_RANDOM_SEED.toString(), "--output-fzn-to-file", fzn.absolutePath))
                add(CorpusFetcher.resolve(ref.source).absolutePath)
                ref.data?.let { add(CorpusFetcher.resolve(it).absolutePath) }
                add(data.absolutePath)
            }
            val process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log).start()
            try {
                when {
                    !process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS) ->
                        SourceValidation("unknown", "source compilation timed out")

                    process.exitValue() != 0 || !fzn.exists() ->
                        SourceValidation("unknown", "source compilation failed: ${log.readText().take(500)}")

                    else -> inspect(fzn.readText(), objective)
                }
            } finally {
                if (process.isAlive) process.destroyForcibly().waitFor()
            }
        }.getOrElse { failure ->
            SourceValidation("unknown", "source checking failed: ${failure.message.orEmpty().take(500)}")
        }.also { directory.deleteRecursively() }
    }

    fun candidate(rawOutput: String): String? {
        val complete = rawOutput.split("----------").dropLast(1).lastOrNull() ?: return null
        val lines = complete.lineSequence().filter { line ->
            val trimmed = line.trim()
            trimmed.isNotEmpty() && !trimmed.startsWith('%') && !trimmed.startsWith("=====")
        }.toList()
        val start = lines.indexOfFirst { ASSIGNMENT.containsMatchIn(it) }
        return if (start < 0) null else lines.drop(start).joinToString("\n")
    }

    fun inspect(flatZinc: String, objective: Double?): SourceValidation = runCatching {
        inspectProgram(parseFlatZinc(flatZinc, exactFloats = true), objective)
    }.getOrElse { failure ->
        SourceValidation(
            "unknown",
            "source checking cannot inspect compiled model: ${failure.message.orEmpty().take(500)}",
        )
    }

    private fun inspectProgram(program: FlatZincProgram, objective: Double?): SourceValidation {
        val problem = program.problem
        val values = HashMap<Int, Boolean>()
        val clauses = problem.factors.filterIsInstance<Clause>()
        for (clause in clauses.filter { it.literals.size == 1 }) {
            val literal = clause.literals.single()
            val value = Lit.isPositive(literal)
            val previous = values.put(Lit.variable(literal), value)
            if (previous != null && previous != value) {
                return SourceValidation("invalid", "source constraints reject candidate")
            }
        }
        val falseClause = clauses.any { clause ->
            clause.literals.all { literal ->
                values[Lit.variable(literal)]?.let { !Lit.evaluate(literal, it) } == true
            }
        }
        if (falseClause) return SourceValidation("invalid", "source constraints reject candidate")
        val fixedIntegers = (0 until problem.numIntVars).all { v ->
            problem.intBounds.hasLower(v) && problem.intBounds.hasUpper(v) &&
                problem.intBounds.lower(v) == problem.intBounds.upper(v)
        }
        val fixedReals = problem.realLower.indices.all { v ->
            problem.realLower[v].isFinite() && problem.realLower[v] == problem.realUpper[v]
        }
        val trueClauses = clauses.all { clause ->
            clause.literals.any { literal ->
                values[Lit.variable(literal)]?.let { Lit.evaluate(literal, it) } == true
            }
        }
        return if (!fixedIntegers || !fixedReals || values.size != problem.numBoolVars ||
            clauses.size != problem.factors.size || !trueClauses
        ) {
            SourceValidation("unknown", "source compilation leaves residual variables or constraints")
        } else if (objective != null && fixedObjective(program, values) != objective) {
            SourceValidation("invalid", "reported objective differs from pinned source objective")
        } else {
            SourceValidation("valid", "source constraints evaluate true with candidate pinned")
        }
    }

    private fun fixedObjective(program: FlatZincProgram, bools: Map<Int, Boolean>): Double? {
        val name = when (val solve = program.solve) {
            is SolveDirective.Minimize -> solve.objVar
            is SolveDirective.Maximize -> solve.objVar
            SolveDirective.Satisfy -> return null
        }
        program.floatVarsByName[name]?.let { return it.lo }
        program.intVarsByName[name]?.let { return program.problem.intBounds.lower(it).toDouble() }
        return program.boolVarsByName[name]?.let { if (bools[it] == true) 1.0 else 0.0 }
    }
}

internal fun SolveRecord.sourceChecked(validation: SourceValidation, approximation: Boolean): SolveRecord {
    val reject = validation.status == "invalid" ||
        (approximation && (feasible == false || (feasible == true && validation.status != "valid")))
    val verifiedStats = stats + mapOf(
        "sourceValidation" to validation.status,
        "sourceValidationReason" to validation.reason,
        "floatApproximation" to approximation.toString(),
        "reportedFeasible" to feasible.toString(),
        "reportedObjective" to objective.toString(),
        "reportedProven" to proven.toString(),
    )
    return copy(
        feasible = if (reject) null else feasible,
        objective = if (reject) null else objective,
        proven = proven && !reject && !approximation,
        timeToBestMs = if (reject) null else timeToBestMs,
        timeToFirstFeasibleMs = if (reject) null else timeToFirstFeasibleMs,
        attribution = if (reject) {
            emptyList()
        } else {
            attribution.filter { incumbent ->
                objective == null ||
                    (incumbent.continuousObjective ?: incumbent.exactObjective?.toDoubleOrNull()) == objective
            }
        },
        stats = verifiedStats,
    )
}
