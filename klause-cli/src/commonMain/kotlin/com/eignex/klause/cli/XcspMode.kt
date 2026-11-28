package com.eignex.klause.cli

import com.eignex.klause.formats.xcsp3.Xcsp3
import com.eignex.klause.formats.xcsp3.xcsp3Semantics
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.toLinearObjective
import com.eignex.util.io.openTextFile
import com.eignex.util.io.useSource

internal object Xcsp3Mode : CliMode {
    override val names = listOf("xcsp3", "xcsp")
    override val extensions = listOf("xml", "xcsp", "xcsp3")
    override fun newSession(): ModeSession = Session()

    private class Session : ModeSession {
        override fun flags(): List<FlagSpec> = emptyList()

        override fun load(path: String, common: CommonOptions): Solvable {
            val overrides = common.sourceOverrides(xcsp3Semantics)
            // Parsing only reads; the base bake runs as presolve step 0, bounded by the presolve deadline.
            val parsed = openTextFile(path).useSource { source ->
                Xcsp3.parse(source, common.config.problemSettings(), semanticOverrides = overrides)
            }
            cliLogger(common.verbose).v {
                "parsed ${fileName(path)}: bool=${parsed.problem.numBoolVars} int=${parsed.problem.numIntVars} " +
                    "factors=${parsed.problem.numFactors}"
            }
            val names = parsed.intVarNames
            val render: (Sample) -> String = { s -> renderInstantiation(names, s) }
            return sourceSolvable(
                parsed.problem,
                parsed.objective?.toLinearObjective(),
                parsed.sense == ObjectiveSense.MAXIMIZE,
                common,
                render,
                { assignment -> renderInstantiation(names, assignment::intValue) },
                definedVars = parsed.definedVars,
                definitionHints = parsed.definitionHints,
            )
        }

        override fun output(common: CommonOptions): OutputProtocol = XcspOutput()
    }
}

internal fun renderInstantiation(names: Map<String, Int>, s: Sample): String =
    renderInstantiation(names) { s.intValue(it).toString() }

private fun renderInstantiation(names: Map<String, Int>, intValue: (Int) -> String): String {
    val list = names.keys.joinToString(" ")
    val values = names.values.joinToString(" ") { intValue(it) }
    return "v <instantiation> <list> $list </list> <values> $values </values> </instantiation>"
}

internal class XcspOutput : BufferedBestOutput() {
    override val commentPrefix: String = "c"
    override val streamObjective: Boolean = true

    override fun statusLine(verdict: Verdict): String = when (verdict) {
        Verdict.SATISFIABLE, Verdict.BEST_FOUND, Verdict.UNBOUNDED -> "s SATISFIABLE"
        Verdict.OPTIMAL -> "s OPTIMUM FOUND"
        Verdict.UNSATISFIABLE -> "s UNSATISFIABLE"
        Verdict.UNKNOWN -> "s UNKNOWN"
    }

    // XCSP comments track search shape only.
    override fun keepStat(key: String): Boolean = key !in XCSP_OMITTED_KEYS

    private companion object {
        private val XCSP_OMITTED_KEYS = setOf("learned", "relearned")
    }
}
