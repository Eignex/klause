package com.eignex.klause.cli

import com.eignex.klause.formats.smtlib.SmtLibQfLia
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveStats

/**
 * SMT-LIB 2 **QF_LIA** front-end (`.smt2` / `.smt`). Emits the SMT-LIB convention: a
 * `sat` / `unsat` / `unknown` status line, followed (when sat) by a `(get-model)`-style
 * `(define-fun …)` block. `-s` statistics are emitted as `;` comment lines.
 */
internal object SmtLibMode : CliMode {
    override val names = listOf("smtlib", "smt", "smt2")
    override val extensions = listOf("smt2", "smt")
    override fun newSession(): ModeSession = Session()

    private class Session : ModeSession {
        override fun flags(): List<FlagSpec> = emptyList()

        override fun load(path: String, common: CommonOptions): Solvable {
            val parsed = SmtLibQfLia.parse(readTextFile(path))
            cliLogger(common.verbose).v {
                "parsed ${fileName(path)}: bool=${parsed.problem.numBoolVars} int=${parsed.problem.numIntVars} " +
                    "factors=${parsed.problem.numFactors}"
            }
            val ints = parsed.intVarNames
            val bools = parsed.boolVarNames
            val render: (Sample) -> String = { s -> renderModel(ints, bools, s) }
            return linearSolvable(parsed.problem, parsed.objective, parsed.maximize, render)
        }

        override fun output(common: CommonOptions): OutputProtocol = SmtLibOutput()
    }
}

/** Render an SMT-LIB `(get-model)`-style model: one `(define-fun name () Sort value)` per
 *  declared variable. */
internal fun renderModel(ints: Map<String, Int>, bools: Map<String, Int>, s: Sample): String = buildString {
    append("(\n")
    for ((name, id) in ints) append("  (define-fun $name () Int ${s.ints[id]})\n")
    for ((name, id) in bools) append("  (define-fun $name () Bool ${s.bools[id]})\n")
    append(")")
}

/** SMT-LIB output protocol: `sat`/`unsat`/`unknown` + the buffered model on sat. */
internal class SmtLibOutput : OutputProtocol {
    private var best: String? = null

    override fun onSolution(rendered: String, objective: Long?) {
        best = rendered
    }

    override fun onComplete(verdict: Verdict) {
        when (verdict) {
            Verdict.SATISFIABLE, Verdict.OPTIMAL, Verdict.BEST_FOUND -> {
                println("sat")
                best?.let { println(it) }
            }

            Verdict.UNSATISFIABLE -> println("unsat")

            Verdict.UNKNOWN -> println("unknown")
        }
    }

    override fun onStatistics(stats: SolveStats, solveTimeMs: Long, solutions: Long) {
        println("; solveTime=${solveTimeMs / 1000.0}")
        println("; solutions=$solutions")
        if (stats.backend.isNotEmpty()) {
            println("; nodes=${stats.nodes.sum.toLong()}")
            println("; failures=${stats.fails.sum.toLong()}")
            println("; propagations=${stats.propagations.sum.toLong()}")
        }
    }
}
