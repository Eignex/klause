package com.eignex.klause.cli

import com.eignex.klause.formats.dimacs.Dimacs
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.result.SolveStats

/**
 * DIMACS **CNF** front-end (`.cnf`) for Boolean satisfiability. Emits the SAT-competition
 * convention: an `s SATISFIABLE` / `s UNSATISFIABLE` / `s UNKNOWN` status line, and on sat a
 * `v`-prefixed model listing each variable as `i` (true) or `-i` (false), 1-based, terminated
 * by `0`. `-s` statistics are emitted as `c` comment lines. Weighted CNF (MaxSAT) is a
 * distinct format with an objective and is out of scope here.
 */
internal object DimacsMode : CliMode {
    override val names = listOf("dimacs", "cnf", "sat")
    override val extensions = listOf("cnf")
    override fun newSession(): ModeSession = Session()

    private class Session : ModeSession {
        override fun flags(): List<FlagSpec> = emptyList()

        override fun load(path: String, common: CommonOptions): Solvable {
            val problem = Dimacs.parse(readTextFile(path))
            cliLogger(common.verbose).v {
                "parsed ${fileName(path)}: bool=${problem.numBoolVars} factors=${problem.numFactors}"
            }
            val render: (Sample) -> String = { s -> renderDimacsModel(problem.numBoolVars, s) }
            return linearSolvable(problem, null, false, render)
        }

        override fun output(common: CommonOptions): OutputProtocol = DimacsOutput()
    }
}

/** Render a DIMACS SAT model line: each variable `i` (1-based) as `i` when true, `-i` when
 *  false, terminated by `0`. */
internal fun renderDimacsModel(numVars: Int, s: Sample): String = buildString {
    append("v")
    for (v in 0 until numVars) append(" ${if (s.bools[v]) v + 1 else -(v + 1)}")
    append(" 0")
}

/** DIMACS output protocol: `s SATISFIABLE`/`s UNSATISFIABLE`/`s UNKNOWN` + the buffered model on sat. */
internal class DimacsOutput : OutputProtocol {
    private var best: String? = null

    override fun onSolution(rendered: String, objective: Long?) {
        best = rendered
    }

    override fun onComplete(verdict: Verdict) {
        when (verdict) {
            Verdict.SATISFIABLE, Verdict.OPTIMAL, Verdict.BEST_FOUND -> {
                println("s SATISFIABLE")
                best?.let { println(it) }
            }

            Verdict.UNSATISFIABLE -> println("s UNSATISFIABLE")

            Verdict.UNKNOWN -> println("s UNKNOWN")
        }
    }

    override fun onStatistics(stats: SolveStats, solveTimeMs: Long, solutions: Long) {
        println("c solveTime=${solveTimeMs / 1000.0}")
        if (stats.run.backend.isNotEmpty()) {
            println("c nodes=${stats.search.nodes.sum.toLong()}")
            println("c failures=${stats.search.fails.sum.toLong()}")
            println("c propagations=${stats.search.propagations.sum.toLong()}")
        }
    }
}
