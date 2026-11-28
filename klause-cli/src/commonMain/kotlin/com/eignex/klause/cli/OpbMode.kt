package com.eignex.klause.cli

import com.eignex.klause.formats.opb.Opb
import com.eignex.klause.formats.opb.opbSemantics
import com.eignex.klause.formats.opb.toProblem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.toLinearObjective
import com.eignex.util.io.openTextFile
import com.eignex.util.io.useSource

// The objective is always a minimization. Satisfaction emits its `v` line after the final status.
internal object OpbMode : CliMode {
    override val names = listOf("opb", "pb", "wbo")
    override val extensions = listOf("opb", "wbo")
    override fun newSession(): ModeSession = Session()

    private class Session : ModeSession {
        override fun flags(): List<FlagSpec> = emptyList()

        override fun load(path: String, common: CommonOptions): Solvable {
            val overrides = common.sourceOverrides(opbSemantics)
            val parsed = openTextFile(path).useSource { Opb.parse(it) }.toProblem(
                common.config.problemSettings(), semanticOverrides = overrides,
            )
            cliLogger(common.verbose).v {
                "parsed ${fileName(path)}: bool=${parsed.problem.numBoolVars} factors=${parsed.problem.numFactors}"
            }
            // Only the declared x1..xN belong in the `v` line, not the auxiliary indicators.
            val render: (Sample) -> String = { s -> renderPbModel(parsed.numDeclaredVars, s) }
            return sourceSolvable(
                parsed.problem, parsed.objective?.toLinearObjective(), false, common, render,
                { assignment -> renderPbModel(parsed.numDeclaredVars, assignment::boolValue) },
                boolFolds = parsed.boolFolds,
            )
        }

        override fun output(common: CommonOptions): OutputProtocol = OpbOutput()
    }
}

internal fun renderPbModel(numVars: Int, s: Sample): String = renderPbModel(numVars, s::boolValue)

private fun renderPbModel(numVars: Int, boolValue: (Int) -> Boolean): String = buildString {
    append("v")
    for (v in 0 until numVars) append(if (boolValue(v)) " x${v + 1}" else " -x${v + 1}")
}

internal class OpbOutput : BufferedBestOutput() {
    override val commentPrefix: String = "c"
    override val streamObjective: Boolean = true
    override val streamFiniteWitness: Boolean = true

    override fun statusLine(verdict: Verdict): String = when (verdict) {
        Verdict.SATISFIABLE, Verdict.BEST_FOUND, Verdict.UNBOUNDED -> "s SATISFIABLE"
        Verdict.OPTIMAL -> "s OPTIMUM FOUND"
        Verdict.UNSATISFIABLE -> "s UNSATISFIABLE"
        Verdict.UNKNOWN -> "s UNKNOWN"
    }

    // Clause-learning counters are irrelevant to the PB search shape reported here.
    override fun keepStat(key: String): Boolean = key !in PB_OMITTED_KEYS

    private companion object {
        private val PB_OMITTED_KEYS = setOf("learned", "relearned")
    }
}
