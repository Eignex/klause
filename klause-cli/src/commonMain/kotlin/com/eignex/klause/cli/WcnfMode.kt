package com.eignex.klause.cli

import com.eignex.klause.formats.dimacs.wcnfSemantics
import com.eignex.klause.formats.dimacs.Dimacs
import com.eignex.klause.formats.dimacs.toProblem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.toLinearObjective

/**
 * DIMACS **WCNF** (MaxSAT) front-end (`.wcnf`). Soft clauses are relaxed with a fresh indicator and
 * the total weight of falsified soft clauses is minimised, so both new-format (`h` hard clauses,
 * `<weight>` soft) and old-format (`p wcnf … <top>`) instances are handled. Emits the MaxSAT-Evaluation
 * protocol: an `o <cost>` line paired with a `v` line per improving incumbent, followed by a final
 * `s OPTIMUM FOUND` / `s SATISFIABLE` / `s UNSATISFIABLE` / `s UNKNOWN` status. The `v` line gives
 * each original variable's value as a `0`/`1` bit-string (relaxation variables are not reported).
 * `-s` statistics are `c` comment lines.
 */
internal object WcnfMode : CliMode {
    override val names = listOf("wcnf", "maxsat")
    override val extensions = listOf("wcnf")
    override fun newSession(): ModeSession = Session()

    private class Session : ModeSession {
        override fun flags(): List<FlagSpec> = emptyList()

        override fun load(path: String, common: CommonOptions): Solvable {
            val overrides = common.sourceOverrides(wcnfSemantics)
            val parsed = openFileSource(path).useSource { Dimacs.parseWcnf(it) }.toProblem(
                common.config.problemSettings(), semanticOverrides = overrides,
            )
            cliLogger(common.verbose).v {
                "parsed ${fileName(path)}: vars=${parsed.numOriginalBoolVars} " +
                    "auxiliaries=${parsed.problem.numBoolVars - parsed.numOriginalBoolVars} factors=${parsed.problem.numFactors}"
            }
            val render: (Sample) -> String = { s -> renderWcnfModel(parsed.numOriginalBoolVars, s) }
            // MaxSAT minimises the weight of falsified soft clauses.
            return sourceSolvable(
                parsed.problem, parsed.objective.toLinearObjective(), false, common, render,
                { assignment -> renderWcnfModel(parsed.numOriginalBoolVars, assignment::boolValue) },
                boolFolds = parsed.boolFolds,
            )
        }

        override fun output(common: CommonOptions): OutputProtocol = WcnfOutput()
    }
}

/** Render a MaxSAT-Evaluation `v` line: `v` followed by one `0`/`1` bit per original variable (1-based). */
internal fun renderWcnfModel(numOriginalVars: Int, s: Sample): String =
    renderWcnfModel(numOriginalVars, s::boolValue)

private fun renderWcnfModel(numOriginalVars: Int, boolValue: (Int) -> Boolean): String = buildString {
    append("v ")
    for (v in 0 until numOriginalVars) append(if (boolValue(v)) '1' else '0')
}

/** MaxSAT-Evaluation output protocol. Each incumbent streams its `o` cost and `v` model together;
 *  the final `s` status is emitted at completion. */
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
