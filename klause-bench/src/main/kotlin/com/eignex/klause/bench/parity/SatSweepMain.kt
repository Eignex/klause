package com.eignex.klause.bench.parity

import com.eignex.klause.formats.flatzinc.SolveDirective
import com.eignex.klause.formats.flatzinc.parseFlatZinc
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.localsearch.LocalSearchParams
import com.eignex.klause.solver.localsearch.LocalSearchSolver
import com.eignex.klause.solver.localsearch.strategy.AspirationCriterion
import com.eignex.klause.solver.localsearch.strategy.Cbls
import com.eignex.klause.solver.localsearch.strategy.ProbSat
import com.eignex.klause.solver.localsearch.strategy.Strategy
import com.eignex.klause.solver.localsearch.strategy.TabuFilter
import java.io.File

/**
 * Bench to decide the MiniZinc CLI's *satisfy* default. For each satisfaction `.fzn`, it
 * sweeps BOTH candidate strategies — FocusedLs/probSAT vs CBLS — across seeds under a
 * wall-clock budget, then scores several candidate routing rules by *regret* (how much
 * solve-rate a rule leaves on the table vs always picking the per-instance winner).
 *
 * The output answers two things in order:
 *   1. Is there any region where FocusedLs beats CBLS? (if not → ship "always CBLS")
 *   2. If so, does a `bool-only [& no-globals]` discriminator capture it? (R2/R3 vs R0/R1)
 *
 * Properties:
 *   - `klause.satsweep.dir`        — dir of `.fzn`. Default `klause-bench/build/sat-sweep`.
 *   - `klause.satsweep.timeoutSec` — per-run wall-clock budget. Default 30.
 *   - `klause.satsweep.seeds`      — number of seeds (1..N). Default 3.
 */
object SatSweepMain {

    private fun baseTabu() = TabuFilter(tenure = 10, aspiration = AspirationCriterion.OrImproving)
    private fun focused(): Strategy = ProbSat.adaptive(tabu = baseTabu())
    private fun cbls(): Strategy = Cbls(tabu = baseTabu())

    private data class Features(
        val boolVars: Int, val intVars: Int, val factorKinds: Map<String, Int>,
    ) {
        val boolOnly: Boolean get() = intVars == 0
        // "no globals" = every factor is a plain Clause (the boolean-SAT primitive).
        val noGlobals: Boolean get() = factorKinds.keys.all { it == "Clause" }
        val intFraction: Double get() {
            val total = boolVars + intVars
            return if (total == 0) 0.0 else intVars.toDouble() / total
        }
    }

    /** Per-strategy result on one instance, aggregated over seeds. */
    private data class Result(val solved: Int, val seeds: Int, val medianMs: Long?) {
        val solveRate: Double get() = solved.toDouble() / seeds
    }

    private data class Row(val name: String, val feat: Features, val focused: Result, val cbls: Result) {
        /** Ground-truth winner by solve-rate, tiebreak faster median. "FOCUSED" / "CBLS" / "TIE". */
        val winner: String get() = when {
            focused.solveRate > cbls.solveRate -> "FOCUSED"
            cbls.solveRate > focused.solveRate -> "CBLS"
            else -> {
                val f = focused.medianMs ?: Long.MAX_VALUE; val c = cbls.medianMs ?: Long.MAX_VALUE
                if (f < c) "FOCUSED" else if (c < f) "CBLS" else "TIE"
            }
        }
        val bestRate: Double get() = maxOf(focused.solveRate, cbls.solveRate)
    }

    // Candidate routing rules: given features, pick "FOCUSED" or "CBLS".
    private val rules: List<Pair<String, (Features) -> String>> = listOf(
        "R0 always-CBLS" to { _ -> "CBLS" },
        "R1 always-FOCUSED" to { _ -> "FOCUSED" },
        "R2 boolOnly->FOCUSED" to { f -> if (f.boolOnly) "FOCUSED" else "CBLS" },
        "R3 boolOnly&noGlobals->FOCUSED" to { f -> if (f.boolOnly && f.noGlobals) "FOCUSED" else "CBLS" },
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val root = System.getProperty("klause.workspace.root")?.let { File(it) } ?: File(".")
        val dir = File(root, System.getProperty("klause.satsweep.dir", "klause-bench/build/sat-sweep"))
        val timeoutSec = System.getProperty("klause.satsweep.timeoutSec", "30").toInt()
        val nSeeds = System.getProperty("klause.satsweep.seeds", "3").toInt()
        val seeds = (1L..nSeeds.toLong()).toList()

        val fznFiles = dir.listFiles { f -> f.isFile && f.extension == "fzn" }?.sortedBy { it.name } ?: emptyList()
        require(fznFiles.isNotEmpty()) { "no .fzn in $dir" }
        println("[satsweep] instances=${fznFiles.size} seeds=$nSeeds budget=${timeoutSec}s")

        val rows = ArrayList<Row>()
        for (f in fznFiles) {
            val program = runCatching { parseFlatZinc(f.readText()) }.getOrElse {
                println("  skip ${f.name}: parse failed (${it.message})"); continue
            }
            if (program.solve !is SolveDirective.Satisfy) { println("  skip ${f.name}: not satisfy"); continue }
            val p = program.problem
            val kinds = p.factors.groupingBy { it::class.simpleName ?: "?" }.eachCount()
            val feat = Features(p.numBoolVars, p.numIntVars, kinds)
            val fr = runStrategy(p, ::focused, seeds, timeoutSec)
            val cr = runStrategy(p, ::cbls, seeds, timeoutSec)
            val row = Row(f.nameWithoutExtension, feat, fr, cr)
            rows.add(row)
            println("\n== ${row.name} ==  boolVars=${feat.boolVars} intVars=${feat.intVars} " +
                "boolOnly=${feat.boolOnly} noGlobals=${feat.noGlobals} kinds=${feat.factorKinds}")
            println("  FOCUSED  solved ${fr.solved}/${fr.seeds}  median=${fr.medianMs ?: "-"}ms")
            println("  CBLS     solved ${cr.solved}/${cr.seeds}  median=${cr.medianMs ?: "-"}ms")
            println("  winner=${row.winner}")
        }

        // ---- Candidate-rule regret ----
        println("\n[satsweep] ROUTING RULES (regret = Σ solve-rate left vs per-instance best; lower=better)")
        for ((label, rule) in rules) {
            var regret = 0.0
            var picksWorse = 0
            for (row in rows) {
                val picked = rule(row.feat)
                val pickedRate = if (picked == "FOCUSED") row.focused.solveRate else row.cbls.solveRate
                val r = row.bestRate - pickedRate
                regret += r
                if (r > 1e-9) picksWorse++
            }
            println("  %-32s regret=%.2f  picks-worse-on=%d/%d".format(label, regret, picksWorse, rows.size))
        }
    }

    private fun runStrategy(
        problem: com.eignex.klause.solver.Problem, strat: () -> Strategy, seeds: List<Long>, timeoutSec: Int,
    ): Result {
        val times = ArrayList<Long>()
        for (seed in seeds) {
            val deadline = System.currentTimeMillis() + timeoutSec * 1000L
            val cancel = Cancellation { System.currentTimeMillis() > deadline }
            val solver = LocalSearchSolver(problem, strategy = strat(), pairSwapBudget = 1024)
            val params = LocalSearchParams(randomSeed = seed, cancellation = cancel)
            val started = System.currentTimeMillis()
            val s: Sample? = runCatching { solver.samples(params).firstOrNull() }.getOrNull()
            if (s != null) times.add(System.currentTimeMillis() - started)
        }
        val median = if (times.isEmpty()) null else times.sorted()[times.size / 2]
        return Result(times.size, seeds.size, median)
    }
}
