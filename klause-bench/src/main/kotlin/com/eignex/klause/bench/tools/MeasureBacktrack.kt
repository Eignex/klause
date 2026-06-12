package com.eignex.klause.bench.tools

import com.eignex.klause.formats.dimacs.Dimacs
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.backtrack.selector.RandomVariable
import com.eignex.klause.solver.backtrack.selector.Vsids
import java.util.Locale
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

/**
 * Diagnostic: run [BacktrackSolver] over a generated scaling series and dump the engine's
 * [com.eignex.klause.solver.result.SolveStats] per instance — to see *where* the complete search
 * spends effort and whether clause learning is engaging, without needing a downloaded
 * corpus.
 *
 *  - **PHPₙ** (pigeonhole: n+1 pigeons, n holes) — UNSAT, exponential for a solver without
 *    effective learning; the canonical CDCL stress test. Watch `fails`/`learned` grow and
 *    whether larger n still closes in budget.
 *  - **rand3sat-n** — uniform random 3-SAT at ratio 4.26 (phase transition); mixed
 *    SAT/UNSAT, exercises decision throughput and restarts.
 *
 * Invoked via the bench CLI: `./gradlew :klause-bench:bench --args="diag:backtrack"`
 * (knobs via `-Dklause.measure.*`).
 */
internal object MeasureBacktrack {
    @JvmStatic
    fun run() = runImpl()
}

private fun runImpl() {
    val budgetMs = System.getProperty("klause.measure.budgetMs")?.toLongOrNull() ?: 5_000L
    val seed = System.getProperty("klause.measure.seed")?.toLongOrNull() ?: 1L

    val instances = buildList {
        for (n in 5..8) add("php$n" to generatePhp(n))
        for (n in intArrayOf(50, 100, 150, 200)) add("rand3sat-$n" to generateRandom3Sat(n, ratio = 4.26, seed = seed))
        // Under-constrained (ratio well below the 4.26 phase transition): satisfiable, found by
        // a long run of decisions + propagation with few conflicts — a decision-throughput probe
        // that isolates per-decision cost from conflict-analysis cost and amortizes per-solve setup.
        for (n in intArrayOf(2000, 5000)) add("rand3sat-lite-$n" to generateRandom3Sat(n, ratio = 3.0, seed = seed))
    }

    // Config knobs (so the restart cadence / heuristic can be swept):
    //   -Dklause.measure.lubyBase=N   restart base in attempt-units; 0 disables restarts.
    //   -Dklause.measure.phaseSaving=true|false
    //   -Dklause.measure.vsids=true|false   (false ⇒ default RandomVariable)
    val lubyBaseProp = System.getProperty("klause.measure.lubyBase")?.toLongOrNull() ?: 100L
    val lubyBase: Long? = if (lubyBaseProp <= 0) null else lubyBaseProp
    val phaseSaving = System.getProperty("klause.measure.phaseSaving")?.toBooleanStrictOrNull() ?: true
    val useVsids = System.getProperty("klause.measure.vsids")?.toBooleanStrictOrNull() ?: true

    println()
    println(
        "=== BacktrackSolver measurement (heuristic=${if (useVsids) "VSIDS" else "Random"}, " +
            "lubyBase=${lubyBase ?: "off"}, phaseSaving=$phaseSaving, LBD forget; ${budgetMs}ms budget) ===",
    )
    println(
        "%-14s %-8s %12s %12s %10s %9s %14s %8s %12s".format(
            Locale.ROOT,
            "instance", "verdict", "decisions", "conflicts", "learned", "restarts", "propagations", "depth", "dec/sec",
        ),
    )
    // Re-solve each instance `repeat` times and report the *best* decisions/sec — the last,
    // JIT-warmed runs reflect steady-state throughput rather than cold-start (the default 1
    // keeps the original single-shot behaviour). Set via -Dklause.measure.repeat=N.
    val reps = System.getProperty("klause.measure.repeat")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
    for ((name, cnf) in instances) {
        val problem = Dimacs.parse(cnf)
        val params = BacktrackParams(
            randomSeed = seed,
            variableHeuristic = if (useVsids) Vsids() else RandomVariable,
            lubyRestartBase = lubyBase,
            phaseSaving = phaseSaving,
            maxLearnedClauses = 20_000,
            cancellation = Cancellation.after(budgetMs.milliseconds),
        )
        var result = BacktrackSolver(problem).solve(params)
        var bestDecPerSec = decisionsPerSec(result)
        repeat(reps - 1) {
            val r = BacktrackSolver(problem).solve(params)
            val dps = decisionsPerSec(r)
            if (dps > bestDecPerSec) {
                bestDecPerSec = dps
                result = r
            }
        }
        val s = result.stats
        val verdict = when (result) {
            is SolveResult.Sat -> "SAT"
            is SolveResult.Unsat -> "UNSAT"
            is SolveResult.Unknown -> "timeout"
        }
        val decisions = s.nodes.sum
        val depth = if (s.peakDepth.max < 0.0) 0.0 else s.peakDepth.max
        val decPerSec = bestDecPerSec
        println(
            "%-14s %-8s %12.0f %12.0f %10.0f %9.0f %14.0f %8.0f %12.0f".format(
                Locale.ROOT,
                name, verdict, decisions, s.fails.sum, s.learnedClauses.sum, s.restarts.sum,
                s.propagations.sum, depth, decPerSec,
            ),
        )
    }
    println()
}

/** Decisions per second for one solve, or 0 when the run was too short to time. */
private fun decisionsPerSec(result: SolveResult): Double {
    val s = result.stats
    return if (s.wallMs > 0) s.nodes.sum * 1000.0 / s.wallMs else 0.0
}

/** Pigeonhole PHPₙ as DIMACS CNF: n+1 pigeons into n holes (UNSAT). Var p*n+h+1 = "pigeon
 *  p sits in hole h". Each pigeon needs a hole; no hole holds two pigeons. */
private fun generatePhp(n: Int): String {
    val pigeons = n + 1
    fun v(p: Int, h: Int) = p * n + h + 1
    val clauses = StringBuilder()
    var count = 0
    for (p in 0 until pigeons) { // each pigeon in ≥1 hole
        for (h in 0 until n) clauses.append(v(p, h)).append(' ')
        clauses.append("0\n")
        count++
    }
    for (h in 0 until n) { // ≤1 pigeon per hole
        for (p in 0 until pigeons) {
            for (q in p + 1 until pigeons) {
                clauses.append(-v(p, h)).append(' ').append(-v(q, h)).append(" 0\n")
                count++
            }
        }
    }
    return "p cnf ${pigeons * n} $count\n$clauses"
}

/** Uniform random 3-SAT: [n] vars, `ratio*n` clauses of 3 distinct vars with random signs. */
private fun generateRandom3Sat(n: Int, ratio: Double, seed: Long): String {
    val rng = Random(seed + n)
    val m = (ratio * n).toInt()
    val clauses = StringBuilder()
    repeat(m) {
        val vars = HashSet<Int>(3)
        while (vars.size < 3) vars.add(rng.nextInt(n) + 1)
        for (x in vars) clauses.append(if (rng.nextBoolean()) x else -x).append(' ')
        clauses.append("0\n")
    }
    return "p cnf $n $m\n$clauses"
}
