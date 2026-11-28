package com.eignex.klause.cli

import com.eignex.klause.formats.dimacs.Dimacs
import com.eignex.klause.formats.dimacs.dimacsSemantics
import com.eignex.klause.formats.dimacs.toProblem
import com.eignex.klause.solver.Sample
import com.eignex.util.io.openTextFile
import com.eignex.util.io.useSource

internal object DimacsMode : CliMode {
    override val names = listOf("dimacs", "cnf", "sat")
    override val extensions = listOf("cnf")
    override fun newSession(): ModeSession = Session()

    private class Session : ModeSession {
        override fun flags(): List<FlagSpec> = emptyList()

        override fun load(path: String, common: CommonOptions): Solvable {
            val overrides = common.sourceOverrides(dimacsSemantics)
            val problem = openTextFile(path).useSource { Dimacs.parse(it) }.toProblem(
                common.config.problemSettings(), semanticOverrides = overrides,
            )
            cliLogger(common.verbose).v {
                "parsed ${fileName(path)}: bool=${problem.numBoolVars} factors=${problem.numFactors}"
            }
            val render: (Sample) -> String = { s -> renderDimacsModel(problem.numBoolVars, s) }
            return sourceSolvable(
                problem, null, false, common, render,
                { assignment -> renderDimacsModel(problem.numBoolVars, assignment::boolValue) },
            )
        }

        override fun output(common: CommonOptions): OutputProtocol = DimacsOutput()
    }
}

internal fun renderDimacsModel(numVars: Int, s: Sample): String = renderDimacsModel(numVars, s::boolValue)

private fun renderDimacsModel(numVars: Int, boolValue: (Int) -> Boolean): String = buildString {
    append("v")
    for (v in 0 until numVars) append(" ${if (boolValue(v)) v + 1 else -(v + 1)}")
    append(" 0")
}

internal class DimacsOutput : BufferedBestOutput() {
    override val commentPrefix: String = "c"

    override fun statusLine(verdict: Verdict): String = when (verdict) {
        Verdict.SATISFIABLE, Verdict.OPTIMAL, Verdict.BEST_FOUND, Verdict.UNBOUNDED -> "s SATISFIABLE"
        Verdict.UNSATISFIABLE -> "s UNSATISFIABLE"
        Verdict.UNKNOWN -> "s UNKNOWN"
    }

    override fun keepStat(key: String): Boolean = true
}
