package com.eignex.klause.bench.parity

import com.eignex.klause.formats.dimacs.Dimacs
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.backtrack.Vsids
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

/**
 * Diagnostic: run [BacktrackSolver] over a generated scaling series and dump the engine's
 * [com.eignex.klause.solver.SolveStats] per instance — to see *where* the complete search
 * spends effort and whether clause learning is engaging, without needing a downloaded
 * corpus.
 *
 *  - **PHPₙ** (pigeonhole: n+1 pigeons, n holes) — UNSAT, exponential for a solver without
 *    effective learning; the canonical CDCL stress test. Watch `fails`/`learned` grow and
 *    whether larger n still closes in budget.
 *  - **rand3sat-n** — uniform random 3-SAT at ratio 4.26 (phase transition); mixed
 *    SAT/UNSAT, exercises decision throughput and restarts.
 *
 * Run: `./gradlew :klause-bench:runMeasureBacktrack` (knobs via `-Dklause.measure.*`).
 */
fun main() {
    val budgetMs = System.getProperty("klause.measure.budgetMs")?.toLongOrNull() ?: 5_000L
    val seed = System.getProperty("klause.measure.seed")?.toLongOrNull() ?: 1L

    val instances = buildList {
        for (n in 5..8) add("php$n" to generatePhp(n))
        for (n in intArrayOf(50, 100, 150, 200)) add("rand3sat-$n" to generateRandom3Sat(n, ratio = 4.26, seed = seed))
    }

    println()
    println("=== BacktrackSolver measurement (CDCL config: VSIDS + Luby(100) + phase-saving + LBD forget; ${budgetMs}ms budget) ===")
    println(
        "%-14s %-8s %12s %12s %10s %9s %14s %8s %12s".format(
            "instance", "verdict", "decisions", "conflicts", "learned", "restarts", "propagations", "depth", "dec/sec"
        )
    )
    for ((name, cnf) in instances) {
        val problem = Dimacs.parse(cnf)
        val params = BacktrackParams(
            randomSeed = seed,
            variableHeuristic = Vsids(),
            lubyRestartBase = 100L,
            phaseSaving = true,
            maxLearnedClauses = 20_000,
            cancellation = Cancellation.after(budgetMs.milliseconds),
        )
        val result = BacktrackSolver(problem).solve(params)
        val s = result.stats
        val verdict = when (result) {
            is SolveResult.Sat -> "SAT"
            is SolveResult.Unsat -> "UNSAT"
            is SolveResult.Unknown -> "timeout"
        }
        val decisions = s.nodes.sum
        val depth = if (s.peakDepth.max < 0.0) 0.0 else s.peakDepth.max
        val decPerSec = if (s.wallMs > 0) decisions * 1000.0 / s.wallMs else 0.0
        println(
            "%-14s %-8s %12.0f %12.0f %10.0f %9.0f %14.0f %8.0f %12.0f".format(
                name, verdict, decisions, s.fails.sum, s.learnedClauses.sum, s.restarts.sum,
                s.propagations.sum, depth, decPerSec
            )
        )
    }
    println()
}

/** Pigeonhole PHPₙ as DIMACS CNF: n+1 pigeons into n holes (UNSAT). Var p*n+h+1 = "pigeon
 *  p sits in hole h". Each pigeon needs a hole; no hole holds two pigeons. */
private fun generatePhp(n: Int): String {
    val pigeons = n + 1
    fun v(p: Int, h: Int) = p * n + h + 1
    val clauses = StringBuilder()
    var count = 0
    for (p in 0 until pigeons) {                       // each pigeon in ≥1 hole
        for (h in 0 until n) clauses.append(v(p, h)).append(' ')
        clauses.append("0\n"); count++
    }
    for (h in 0 until n) {                             // ≤1 pigeon per hole
        for (p in 0 until pigeons) for (q in p + 1 until pigeons) {
            clauses.append(-v(p, h)).append(' ').append(-v(q, h)).append(" 0\n"); count++
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
