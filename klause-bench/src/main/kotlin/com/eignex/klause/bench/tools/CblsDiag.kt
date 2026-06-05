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
        // Mirror the engine's per-move invariants path (issue #153, default-on for FZN in
        // the portfolio): definitional sweep at restart + invariant network per move. The
        // raw diag (default off) and the engine differ in move space — defined vars are
        // search-excluded under invariants — so plateau findings MUST be validated in both
        // modes before trusting them.
        val useInvariants = System.getProperty("klause.cblsdiag.invariants") == "true"
        for (r in 0 until restarts) {
            val rng = Random(1000L + r)
            val state = LocalSearchState(problem, rng, Assumptions.None)
            randomize(state, rng)
            state.recompute()
            if (useInvariants) {
                val sweep = prog.definitionalSweep
                if (sweep == null) {
                    println("(no definitional sweep available for this program; invariants ignored)")
                } else {
                    sweep.sweep(state.assignment, problem.intDomains, problem.factors) { false }
                    state.invariants = sweep.network(problem.numIntVars, problem.numBoolVars)
                    state.recompute()
                }
            }
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
            // -Dklause.cblsdiag.swapcap=16 to probe the stall-swap configuration, or
            // -Dklause.cblsdiag.chaincap=8 (+ optional chaindepth) for ejection chains.
            val swapCap = System.getProperty("klause.cblsdiag.swapcap")?.toInt() ?: 0
            val chainCap = System.getProperty("klause.cblsdiag.chaincap")?.toInt() ?: 0
            val chainDepth = System.getProperty("klause.cblsdiag.chaindepth")?.toInt() ?: 4
            val kickAfter = System.getProperty("klause.cblsdiag.kickafter")?.toInt() ?: 0
            val kickVars = System.getProperty("klause.cblsdiag.kickvars")?.toInt() ?: 8
            val strat = Cbls(
                noiseProbability = noise,
                tabu = if (noTabu) TabuFilter.Disabled
                else TabuFilter(tenure = 10, aspiration = AspirationCriterion.OrImproving),
                maxNeighborhood = maxNbhd,
                skewAlpha = skew,
                scoring = scoring,
                stallSwapCap = swapCap,
                stallChainCap = chainCap,
                stallChainDepth = chainDepth,
                stallKickAfter = kickAfter,
                stallKickVars = kickVars,
            )
            var minCost = state.cost
            var flipsToMin = 0L
            var f = 0L
            // Snapshot the assignment whenever a new min cost is reached, so the post-run dump
            // analyses the TRUE min-cost state (where the search got stuck), not the drifted end.
            var minBool = snapshotBool(state)
            var minInt = snapshotInt(state)
            // Null picks are the engine's restart signal — mirror it instead of aborting the
            // epoch: a short streak of nulls re-randomizes in place (with the sweep re-applied
            // when invariants are on), so starvation-prone configurations (defined-var-heavy
            // models under invariants) get the same many-restart treatment the engine gives.
            var nullStreak = 0
            while (f < flips && state.cost > 0) {
                val move = strat.pickMove(state)
                f++
                if (move == null) {
                    nullStreak++
                    if (nullStreak >= NULL_STREAK_RESTART) {
                        randomize(state, rng)
                        state.recompute()
                        if (useInvariants) {
                            prog.definitionalSweep?.let {
                                it.sweep(state.assignment, problem.intDomains, problem.factors) { false }
                                state.recompute()
                            }
                        }
                        nullStreak = 0
                    }
                    continue
                }
                nullStreak = 0
                state.apply(move)
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
            val ringProbe = System.getProperty("klause.cblsdiag.ringprobe")?.toInt() ?: 0
            if (ringProbe > 0) ringProbe(st, ringProbe)
        }
    }

    /**
     * Exhaustive BFS over the **repair graph** from the min-cost state: nodes are assignments,
     * edges are the repair moves proposed by currently-violated factors, and expansion is
     * restricted to states at or below the start cost (the delta-zero ring). Answers the
     * plateau question directly: is there ANY bounded repair sequence that exits the orbit to
     * cost 0 — and how long is it — or is the ring closed under the factors' own repair
     * vocabulary? Enable with `-Dklause.cblsdiag.ringprobe=<maxDepth>`.
     */
    private fun ringProbe(st: LocalSearchState, maxDepth: Int) {
        val problem = st.problem
        val slack = System.getProperty("klause.cblsdiag.ringslack")?.toLong() ?: 0L
        val frontierMoves = System.getProperty("klause.cblsdiag.ringfrontier") == "true"
        val baseCost = st.cost + slack
        println(
            "--- ring probe: BFS over repair moves${if (frontierMoves) " + neighbour primitives" else ""}, " +
                "depth ≤ $maxDepth, cost cap $baseCost (slack $slack) ---",
        )
        fun snapshot() = Pair(
            BooleanArray(problem.numBoolVars) { st.assignment.boolValue(it) },
            IntArray(problem.numIntVars) { st.assignment.intValue(it) },
        )
        fun load(s: Pair<BooleanArray, IntArray>) {
            for (b in 0 until problem.numBoolVars) st.assignment.setBool(b, s.first[b])
            for (i in 0 until problem.numIntVars) st.assignment.setInt(i, s.second[i])
            st.recompute()
        }
        fun key(s: Pair<BooleanArray, IntArray>): Long {
            var h = 1469598103934665603L
            for (b in s.first) { h = h xor (if (b) 1L else 0L); h *= 1099511628211L }
            for (v in s.second) { h = h xor v.toLong(); h *= 1099511628211L }
            return h
        }

        val start = snapshot()
        val seen = HashSet<Long>().apply { add(key(start)) }
        var frontier = mutableListOf(start)
        var depth = 0
        var expanded = 0
        val sink = MoveSink(Assumptions.None)
        while (frontier.isNotEmpty() && depth < maxDepth && expanded < RING_PROBE_NODE_CAP) {
            depth++
            val next = mutableListOf<Pair<BooleanArray, IntArray>>()
            for (node in frontier) {
                if (expanded >= RING_PROBE_NODE_CAP) break
                expanded++
                load(node)
                val violatedIds = st.violated.toIntArray()
                for (fid in violatedIds) {
                    load(node)
                    sink.clear()
                    st.factors[fid].proposeRepairMoves(st, fid, sink)
                    if (frontierMoves) addNeighbourPrimitives(st, fid, sink)
                    for (m in sink.list) {
                        load(node)
                        st.apply(m)
                        val c = st.cost
                        if (c == 0L) {
                            println("  EXIT FOUND at depth $depth (cost 0) via ${st.factors[fid]::class.simpleName} $m")
                            println("  explored=$expanded — the move vocabulary suffices; the orbit is escapable")
                            return
                        }
                        if (c <= baseCost) {
                            val s = snapshot()
                            if (seen.add(key(s))) next.add(s)
                        }
                    }
                }
            }
            println("  depth $depth: frontier=${next.size} (expanded=$expanded, seen=${seen.size})")
            frontier = next
        }
        println(
            "  NO EXIT within depth $maxDepth (expanded=$expanded, distinct=${seen.size}) — " +
                "the ring is closed under repair moves at cost ≤ $baseCost",
        )
    }

    /** Node-expansion cap for [ringProbe] — bounds runtime on wide rings. */
    private const val RING_PROBE_NODE_CAP = 200_000

    /** Consecutive null picks before the diag loop re-randomizes in place (engine-restart mirror). */
    private const val NULL_STREAK_RESTART = 50

    /** Emit ±1 int steps and bool flips on every variable of every factor *adjacent* to
     *  violated factor [fid] (sharing a variable) — the frontier move class. The repair-only
     *  BFS proved the prize-collecting orbit closed under violated-factor repairs; the escape
     *  must perturb a satisfied neighbour first (the "ejection" step). */
    private fun addNeighbourPrimitives(st: LocalSearchState, fid: Int, sink: MoveSink) {
        val problem = st.problem
        val f = st.factors[fid]
        val seenFactors = HashSet<Int>()
        fun emit(nf: Int) {
            if (nf == fid || !seenFactors.add(nf)) return
            val nfac = st.factors[nf]
            for (u in nfac.intVars) {
                val cur = st.assignment.intValue(u)
                val d = problem.intDomains[u]
                if (cur < d.max) sink.addChannelingIntSet(st, u, cur + 1)
                if (cur > d.min) sink.addChannelingIntSet(st, u, cur - 1)
            }
            for (u in nfac.boolVars) sink.addBoolFlip(u)
        }
        for (v in f.intVars) for (nf in problem.intOccurrences[v]) emit(nf)
        for (v in f.boolVars) for (nf in problem.boolOccurrences[v]) emit(nf)
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

        // Optional raw-state dump for semantic plateau analysis: values of the first N int
        // vars (decision arrays come first in FZN declaration order) plus each violated
        // factor's variable lists. Enable with -Dklause.cblsdiag.dumpints=<N>.
        val dumpInts = System.getProperty("klause.cblsdiag.dumpints")?.toInt() ?: 0
        if (dumpInts > 0) {
            val n = minOf(dumpInts, st.problem.numIntVars)
            println("--- first $n int vars at min cost ---")
            println("  " + (0 until n).joinToString(" ") { "i$it=${st.assignment.intValue(it)}" })
            for (fid in violatedIds.take(5)) {
                val f = st.factors[fid]
                println(
                    "  violated fid=$fid ${f::class.simpleName} ints=${f.intVars.toList()} " +
                        "bools=${f.boolVars.toList()}",
                )
            }
        }

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
