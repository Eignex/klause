package com.eignex.klause.bench.tools

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
object CblsDiag {
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
        var bestBoolVals: BooleanArray? = null
        var bestIntVals: IntArray? = null
        for (r in 0 until restarts) {
            val rng = Random(1000L + r)
            val state = LocalSearchState(problem, rng, Assumptions.None)
            randomize(state, rng)
            state.recompute()
            val noTabu = System.getProperty("klause.cblsdiag.notabu") == "true"
            val noise = System.getProperty("klause.cblsdiag.noise")?.toDouble() ?: 0.05
            val maxNbhd = System.getProperty("klause.cblsdiag.maxnbhd")?.toInt() ?: 1
            val skew = System.getProperty("klause.cblsdiag.skew")?.toDouble() ?: 0.0
            val scoring = if (System.getProperty("klause.cblsdiag.scoring")?.lowercase() == "raw") {
                com.eignex.klause.solver.localsearch.strategy.MoveScoring.Raw
            } else {
                com.eignex.klause.solver.localsearch.strategy.MoveScoring.Weighted
            }
            // Defaults to the shipped Cbls default (0 = plateau-buster off); override with
            // -Dklause.cblsdiag.swapcap=16 to probe the stall-swap configuration.
            val swapCap = System.getProperty("klause.cblsdiag.swapcap")?.toInt() ?: 0
            val strat = Cbls(
                noiseProbability = noise,
                tabu = if (noTabu) TabuFilter.Disabled
                else TabuFilter(tenure = 10, aspiration = AspirationCriterion.OrImproving),
                maxNeighborhood = maxNbhd,
                skewAlpha = skew,
                scoring = scoring,
                stallSwapCap = swapCap,
            )
            var minCost = state.cost
            var flipsToMin = 0L
            var f = 0L
            // Snapshot the assignment whenever a new min cost is reached, so the post-run dump
            // analyses the TRUE min-cost state (where the search got stuck), not the drifted end.
            var minBool = snapshotBool(state)
            var minInt = snapshotInt(state)
            while (f < flips && state.cost > 0) {
                val move = strat.pickMove(state) ?: break
                state.apply(move)
                f++
                if (state.cost < minCost) {
                    minCost = state.cost; flipsToMin = f
                    minBool = snapshotBool(state); minInt = snapshotInt(state)
                }
            }
            val solved = state.cost == 0L
            println("restart $r: min=$minCost (at flip $flipsToMin / $f)${if (solved) "  SOLVED" else ""}")
            if (minCost < globalBest) {
                globalBest = minCost
                bestBoolVals = minBool; bestIntVals = minInt
            }
        }
        println("global best cost (sum of violation degrees): $globalBest")

        if (bestBoolVals != null) {
            val st = LocalSearchState(problem, Random(7), Assumptions.None)
            for (b in 0 until problem.numBoolVars) st.assignment.setBool(b, bestBoolVals!![b])
            for (i in 0 until problem.numIntVars) st.assignment.setInt(i, bestIntVals!![i])
            st.recompute()
            dumpMinState(st)
        }
    }

    /** Detailed analysis at the (snapshotted) min-cost state: every violated factor, its
     *  degree, and the best achievable repair-move scores (netDelta + weighted) — to reveal
     *  whether the search sits in a strict local minimum it can't single-move out of. */
    private fun dumpMinState(st: LocalSearchState) {
        val violatedIds = st.violated.toIntArray()
        val byClass = HashMap<String, Int>()
        for (fid in violatedIds) {
            val name = st.factors[fid]::class.simpleName ?: "?"
            byClass[name] = (byClass[name] ?: 0) + 1
        }
        println("--- ${violatedIds.size} violated factor(s) at min cost ---")
        byClass.entries.sortedByDescending { it.value }.forEach { (k, v) -> println("  %5d  %s".format(v, k)) }

        // Per-violated-factor: degree + the best repair move it proposes (and that move's
        // GLOBAL netDelta — repairing one factor may break others). Reveals whether the min is
        // a strict local minimum (every repair worsens the total) vs a flat plateau.
        println("--- per-violated-factor repair analysis ---")
        var bestGlobal = Long.MAX_VALUE
        for (fid in violatedIds.take(20)) {
            val f = st.factors[fid]
            val sink = MoveSink(Assumptions.None)
            sink.clear()
            f.proposeRepairMoves(st, fid, sink)
            var bestForFactor = Long.MAX_VALUE
            var bestMoveStr = "(none)"
            for (m in sink.list) {
                val d = st.netDelta(m)
                if (d < bestForFactor) { bestForFactor = d; bestMoveStr = "$m Δ=$d" }
                if (d < bestGlobal) bestGlobal = d
            }
            println("  ${f::class.simpleName} fid=$fid degree=${f.violationDegree(st, fid)} " +
                "moves=${sink.list.size} bestΔ=$bestMoveStr")
        }
        println("--- best single repair move over all violated factors: Δ=$bestGlobal " +
            "(${if (bestGlobal < 0) "improving — not a local min" else "≥0 — STRICT LOCAL MIN, needs worsening move/restart"}) ---")
    }

    private fun snapshotBool(s: LocalSearchState): BooleanArray =
        BooleanArray(s.problem.numBoolVars) { s.assignment.boolValue(it) }

    private fun snapshotInt(s: LocalSearchState): IntArray =
        IntArray(s.problem.numIntVars) { s.assignment.intValue(it) }

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
