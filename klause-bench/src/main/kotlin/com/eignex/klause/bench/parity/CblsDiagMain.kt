package com.eignex.klause.bench.parity

import com.eignex.klause.formats.flatzinc.parseFlatZinc
import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Move
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.solver.localsearch.strategy.AspirationCriterion
import com.eignex.klause.solver.localsearch.strategy.Cbls
import com.eignex.klause.solver.localsearch.strategy.TabuFilter
import java.io.File
import kotlin.random.Random

/**
 * Diagnostic for the "CBLS can't even reach feasible" failures. Drives CBLS directly over a
 * parsed FZN from random restarts and reports, per restart: the min cost (violated-factor
 * count) reached and how fast it plateaued. At the best plateau it also dumps the histogram
 * of *which factor classes* remain violated, and the fraction of candidate repair moves whose
 * netDelta == 0 (a flat, gradient-free landscape — the signature of binary-violation cost on
 * tight arithmetic/global constraints).
 *
 * Run: `./gradlew :klause-bench:runCblsDiag -Dklause.cblsdiag.file=klause-bench/build/easiest/amaze.fzn`
 */
object CblsDiagMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val path = System.getProperty("klause.cblsdiag.file") ?: args.getOrNull(0)
        ?: error("set -Dklause.cblsdiag.file=<fzn>")
        val flips = System.getProperty("klause.cblsdiag.flips")?.toLong() ?: 3_000_000L
        val restarts = System.getProperty("klause.cblsdiag.restarts")?.toInt() ?: 3
        val prog = parseFlatZinc(File(path).readText())
        val problem = prog.problem
        println("=== ${File(path).name} ===")
        println("vars: bool=${problem.numBoolVars} int=${problem.numIntVars}  factors=${problem.numFactors}")

        var globalBest = Long.MAX_VALUE
        var bestState: LocalSearchState? = null
        for (r in 0 until restarts) {
            val rng = Random(1000L + r)
            val state = LocalSearchState(problem, rng, Assumptions.None)
            randomize(state, rng)
            state.recompute()
            val noTabu = System.getProperty("klause.cblsdiag.notabu") == "true"
            val strat = Cbls(
                tabu = if (noTabu) TabuFilter.Disabled
                else TabuFilter(tenure = 10, aspiration = AspirationCriterion.OrImproving),
            )
            var minCost = state.cost
            var flipsToMin = 0L
            var f = 0L
            while (f < flips && state.cost > 0) {
                val move = strat.pickMove(state) ?: break
                state.apply(move)
                f++
                if (state.cost < minCost) { minCost = state.cost; flipsToMin = f }
            }
            val solved = state.cost == 0L
            println("restart $r: start=${"%6d".format(/* approx */ minCostStart(state))} " +
                "min=$minCost (at flip $flipsToMin / $f)${if (solved) "  SOLVED" else ""}")
            if (minCost < globalBest) {
                globalBest = minCost
                // recompute leaves state AT min only if we never moved past it; for the dump we
                // re-run to the min by keeping the last state — good enough as a plateau sample.
                bestState = state
            }
        }
        println("global best cost (violated factors): $globalBest")

        bestState?.let { st ->
            // Histogram of violated factor classes at the final plateau state.
            val byClass = HashMap<String, Int>()
            val violatedIds = st.violated.toIntArray()
            for (fid in violatedIds) {
                val name = st.factors[fid]::class.simpleName ?: "?"
                byClass[name] = (byClass[name] ?: 0) + 1
            }
            println("--- violated factor classes at plateau (${violatedIds.size} total) ---")
            byClass.entries.sortedByDescending { it.value }.forEach { (k, v) -> println("  %5d  %s".format(v, k)) }

            // Zero-delta fraction: collect repair moves from every violated factor and check
            // how many have netDelta == 0 (no immediate effect on violated-factor count).
            val sink = MoveSink(Assumptions.None)
            sink.clear()
            for (fid in violatedIds) st.factors[fid].proposeRepairMoves(st, fid, sink)
            val moves = sink.list
            var zero = 0
            var improving = 0
            for (m in moves) {
                val d = st.netDelta(m)
                if (d == 0L) zero++ else if (d < 0) improving++
            }
            val n = moves.size
            println("--- repair-move gradient at plateau ---")
            println("  candidate repair moves: $n")
            if (n > 0) {
                println("  netDelta == 0 (flat):   $zero (${"%.1f".format(100.0 * zero / n)}%)")
                println("  netDelta <  0 (improve): $improving (${"%.1f".format(100.0 * improving / n)}%)")
            }
        }
    }

    private fun minCostStart(state: LocalSearchState): Long = state.cost

    private fun randomize(state: LocalSearchState, rng: Random) {
        val p = state.problem
        for (b in 0 until p.numBoolVars) state.assignment.setBool(b, rng.nextBoolean())
        for (i in 0 until p.numIntVars) {
            val d = p.intDomains[i]
            val lo = d.min
            val hi = d.max
            val span = (hi.toLong() - lo.toLong()).coerceAtMost(1_000_000L).toInt()
            state.assignment.setInt(i, if (span <= 0) lo else lo + rng.nextInt(span + 1))
        }
    }
}
