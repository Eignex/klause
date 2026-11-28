package com.eignex.klause.cli

import com.eignex.klause.formats.dimacs.Dimacs
import com.eignex.klause.formats.dimacs.toProblem
import com.eignex.klause.formats.dimacs.wcnfSemantics
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.toLinearObjective
import com.eignex.util.io.openTextFile
import com.eignex.util.io.useSource

// Soft clauses are relaxed with a fresh indicator; both `h`-style and `p wcnf … <top>` headers are read.
// The `v` line omits relaxation variables.
internal object WcnfMode : CliMode {
    override val names = listOf("wcnf", "maxsat")
    override val extensions = listOf("wcnf")
    override fun newSession(): ModeSession = Session()

    private class Session : ModeSession {
        override fun flags(): List<FlagSpec> = emptyList()

        override fun load(path: String, common: CommonOptions): Solvable {
            val overrides = common.sourceOverrides(wcnfSemantics)
            val parsed = openTextFile(path).useSource { Dimacs.parseWcnf(it) }.toProblem(
                common.config.problemSettings(), semanticOverrides = overrides,
            )
            cliLogger(common.verbose).v {
                "parsed ${fileName(path)}: vars=${parsed.numOriginalBoolVars} " +
                    "auxiliaries=${parsed.problem.numBoolVars - parsed.numOriginalBoolVars} factors=${parsed.problem.numFactors}"
            }
            val render: (Sample) -> String = { s -> renderWcnfModel(parsed.numOriginalBoolVars, s) }
            return sourceSolvable(
                parsed.problem, parsed.objective.toLinearObjective(), false, common, render,
                { assignment -> renderWcnfModel(parsed.numOriginalBoolVars, assignment::boolValue) },
                boolFolds = parsed.boolFolds,
            )
        }

        override fun output(common: CommonOptions): OutputProtocol = WcnfOutput()
    }
}

internal fun renderWcnfModel(numOriginalVars: Int, s: Sample): String =
    renderWcnfModel(numOriginalVars, s::boolValue)

private fun renderWcnfModel(numOriginalVars: Int, boolValue: (Int) -> Boolean): String = buildString {
    append("v ")
    for (v in 0 until numOriginalVars) append(if (boolValue(v)) '1' else '0')
}

internal class WcnfOutput : BufferedBestOutput() {
    override val commentPrefix: String = "c"
    override val streamObjective: Boolean = true
    override val streamFiniteWitness: Boolean = true

    override fun statusLine(verdict: Verdict): String = when (verdict) {
        Verdict.SATISFIABLE, Verdict.BEST_FOUND, Verdict.UNBOUNDED -> "s SATISFIABLE"
        Verdict.OPTIMAL -> "s OPTIMUM FOUND"
        Verdict.UNSATISFIABLE -> "s UNSATISFIABLE"
        Verdict.UNKNOWN -> "s UNKNOWN"
    }

    // Clause-learning counters are irrelevant to the MaxSAT search shape reported here.
    override fun keepStat(key: String): Boolean = key !in MAXSAT_OMITTED_KEYS

    private companion object {
        private val MAXSAT_OMITTED_KEYS = setOf("learned", "relearned")
    }
}
