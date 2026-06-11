package com.eignex.klause.cli

import com.eignex.klause.formats.xcsp3.Xcsp3
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.result.SolveStats

/**
 * XCSP3 front-end (`.xml` / `.xcsp` / `.xcsp3`). Emits the XCSP3 competition output protocol:
 * an `o <cost>` line per improving incumbent, a final `s SATISFIABLE` / `s OPTIMUM FOUND` /
 * `s UNSATISFIABLE` / `s UNKNOWN` status, and a `v <instantiation>` line with named values.
 * `-s` statistics are emitted as `c` comment lines.
 */
internal object Xcsp3Mode : CliMode {
    override val names = listOf("xcsp3", "xcsp")
    override val extensions = listOf("xml", "xcsp", "xcsp3")
    override fun newSession(): ModeSession = Session()

    private class Session : ModeSession {
        override fun flags(): List<FlagSpec> = emptyList()

        override fun load(path: String, common: CommonOptions): Solvable {
            val parsed = Xcsp3.parse(readTextFile(path))
            cliLogger(common.verbose).v {
                "parsed ${fileName(path)}: bool=${parsed.problem.numBoolVars} int=${parsed.problem.numIntVars} " +
                    "factors=${parsed.problem.numFactors}"
            }
            val names = parsed.intVarNames
            val render: (Sample) -> String = { s -> renderInstantiation(names, s) }
            return linearSolvable(parsed.problem, parsed.objective, parsed.maximize, render)
        }

        override fun output(common: CommonOptions): OutputProtocol = XcspOutput()
    }
}

/** Render an XCSP3 competition `v <instantiation>` line: the declared variables (array cells
 *  as `id[i]`) in declaration order paired with their solution values. */
internal fun renderInstantiation(names: Map<String, Int>, s: Sample): String {
    val list = names.keys.joinToString(" ")
    val values = names.values.joinToString(" ") { s.ints[it].toString() }
    return "v <instantiation> <list> $list </list> <values> $values </values> </instantiation>"
}

/** XCSP3 competition output protocol. Incumbent `o` costs stream live; the final `s` status
 *  and `v` instantiation are emitted together at completion, after the best solution is known. */
internal class XcspOutput : OutputProtocol {
    private var best: String? = null

    override fun onSolution(rendered: String, objective: Long?) {
        best = rendered
        if (objective != null) println("o $objective")
    }

    override fun onComplete(verdict: Verdict) {
        when (verdict) {
            Verdict.SATISFIABLE, Verdict.BEST_FOUND -> {
                println("s SATISFIABLE")
                best?.let { println(it) }
            }

            Verdict.OPTIMAL -> {
                println("s OPTIMUM FOUND")
                best?.let { println(it) }
            }

            Verdict.UNSATISFIABLE -> println("s UNSATISFIABLE")

            Verdict.UNKNOWN -> println("s UNKNOWN")
        }
    }

    override fun onStatistics(stats: SolveStats, solveTimeMs: Long, solutions: Long) {
        println("c solveTime=${solveTimeMs / 1000.0}")
        println("c solutions=$solutions")
        if (stats.backend.isNotEmpty()) {
            println("c nodes=${stats.nodes.sum.toLong()}")
            println("c failures=${stats.fails.sum.toLong()}")
            println("c restarts=${stats.restarts.sum.toLong()}")
            println("c propagations=${stats.propagations.sum.toLong()}")
            if (stats.peakDepth.max.isFinite()) println("c peakDepth=${stats.peakDepth.max.toLong()}")
        }
    }
}
