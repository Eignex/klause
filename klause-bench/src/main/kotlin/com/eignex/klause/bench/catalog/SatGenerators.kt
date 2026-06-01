package com.eignex.klause.bench.catalog

import com.eignex.klause.formats.dimacs.Dimacs
import com.eignex.klause.solver.Problem
import kotlin.random.Random

/**
 * Parametric SAT instance generators for in-code (`InCode`) bench suites — no fetch, no
 * license, fully tunable. Each returns a klause [Problem] via the DIMACS parser, so they
 * exercise the same path as a `.cnf` file.
 *
 *  - [php] — pigeonhole PHPₙ (n+1 pigeons, n holes): **UNSAT**, exponential without effective
 *    clause learning — the canonical CDCL stress test.
 *  - [random3Sat] — uniform random 3-SAT at a clause/var [ratio] (4.26 ≈ the phase transition);
 *    sweep `n` to plot solver scaling.
 */
object SatGenerators {
    fun php(n: Int): Problem = Dimacs.parse(phpCnf(n))
    fun random3Sat(n: Int, ratio: Double = 4.26, seed: Long = 1L): Problem = Dimacs.parse(random3SatCnf(n, ratio, seed))

    /** PHPₙ as DIMACS CNF. Var `p*n+h+1` = "pigeon p sits in hole h". */
    private fun phpCnf(n: Int): String {
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
    private fun random3SatCnf(n: Int, ratio: Double, seed: Long): String {
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
}
