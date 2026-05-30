package com.eignex.klause.bench.parity

import com.eignex.klause.formats.flatzinc.SolveDirective
import com.eignex.klause.formats.flatzinc.parseFlatZinc
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.MinimizeResult
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.localsearch.CostShaping
import com.eignex.klause.solver.localsearch.LocalSearchParams
import com.eignex.klause.solver.localsearch.LocalSearchSolver
import com.eignex.klause.solver.localsearch.strategy.AspirationCriterion
import com.eignex.klause.solver.localsearch.strategy.Cbls
import com.eignex.klause.solver.localsearch.strategy.ProbSat
import com.eignex.klause.solver.localsearch.strategy.Strategy
import com.eignex.klause.solver.localsearch.strategy.TabuFilter
import com.eignex.klause.solver.maximizeInt
import com.eignex.klause.solver.minimizeInt
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Solver-agnostic in-process sweep over an `.fzn` corpus under a wall-clock budget. Each
 * [SolverConfig] is a label plus an optional satisfy runner and/or optimize runner, each
 * building and driving its own solver — so the mechanism is independent of which solver a
 * config wraps. Built-in helpers cover local search ([cbls] for CBLS-with-both-phases, [lsSat],
 * [lsOpt]) and complete search ([backtrack]).
 *
 * Every config is run on every instance, dispatched by solve goal:
 *  - **optimization** → the config's optimize runner; scored by true objective.
 *  - **satisfaction** → the config's satisfy runner; scored by solve-rate then time-to-feasible.
 *
 * The headline is the OVERALL average rank across the whole corpus (per-instance dense rank,
 * averaged) — used to pick decent CBLS parameters for a mixed (opt + sat) workload like the
 * MiniZinc Challenge. Per-goal ranks are also reported.
 *
 * Properties:
 *  - `klause.solversweep.dir`        — dir of `.fzn`. Default `klause-bench/build/ls-sweep`.
 *  - `klause.solversweep.timeoutSec` — per-run wall-clock budget. Default 30.
 *  - `klause.solversweep.seeds`      — number of seeds (1..N). Default 3.
 */
object SolverSweepMain {

    private fun baseTabu() = TabuFilter(tenure = 10, aspiration = AspirationCriterion.OrImproving)

    class SolverConfig(
        val label: String,
        val sat: ((Problem, Long, Cancellation) -> Sample?)? = null,
        val opt: ((Problem, objVar: Int, maximize: Boolean, Long, Cancellation) -> Sample?)? = null,
    )

    /** A CBLS config exercised in BOTH phases with the same params (rebuilt per run — CBLS is
     *  stateful): satisfy via `strategy`, optimize via `optimizeStrategy` (unified minimize). */
    private fun cbls(label: String, make: () -> Cbls) = SolverConfig(
        label,
        sat = { p, seed, cancel ->
            LocalSearchSolver(p, strategy = make(), pairSwapBudget = 1024)
                .samples(LocalSearchParams(randomSeed = seed, cancellation = cancel)).firstOrNull()
        },
        opt = { p, objVar, maximize, seed, cancel ->
            val solver = LocalSearchSolver(p, optimizeStrategy = make(), pairSwapBudget = 1024)
            val params = LocalSearchParams(randomSeed = seed, cancellation = cancel, costShaping = CostShaping.Linear(lambda = 1.0))
            val obj = if (maximize) p.maximizeInt(objVar) else p.minimizeInt(objVar)
            (solver.minimize(obj, params) as? MinimizeResult.WithSample)?.sample
        },
    )

    /** FocusedLs (probSAT family) in both phases — klause's "other" LS, not CBLS. For opt it
     *  reuses the satisfy strategy for the feasibility fight, then the built-in greedy descent
     *  (probSAT has no objective-aware move at feasibility). */
    private fun focused(label: String) = SolverConfig(
        label,
        sat = { p, seed, cancel ->
            LocalSearchSolver(p, strategy = ProbSat.adaptive(tabu = baseTabu()), pairSwapBudget = 1024)
                .samples(LocalSearchParams(randomSeed = seed, cancellation = cancel)).firstOrNull()
        },
        opt = { p, objVar, maximize, seed, cancel ->
            val solver = LocalSearchSolver(p, strategy = ProbSat.adaptive(tabu = baseTabu()), pairSwapBudget = 1024)
            val params = LocalSearchParams(randomSeed = seed, cancellation = cancel, costShaping = CostShaping.Linear(lambda = 1.0))
            val obj = if (maximize) p.maximizeInt(objVar) else p.minimizeInt(objVar)
            (solver.minimize(obj, params) as? MinimizeResult.WithSample)?.sample
        },
    )

    /** Local search, satisfy-only (a non-CBLS satisfy strategy). */
    private fun lsSat(label: String, make: () -> Strategy) = SolverConfig(label, sat = { p, seed, cancel ->
        LocalSearchSolver(p, strategy = make(), pairSwapBudget = 1024)
            .samples(LocalSearchParams(randomSeed = seed, cancellation = cancel)).firstOrNull()
    })

    /** Local search, optimize-only. */
    private fun lsOpt(label: String, make: () -> Strategy) = SolverConfig(label, opt = { p, objVar, maximize, seed, cancel ->
        val solver = LocalSearchSolver(p, optimizeStrategy = make(), pairSwapBudget = 1024)
        val params = LocalSearchParams(randomSeed = seed, cancellation = cancel, costShaping = CostShaping.Linear(lambda = 1.0))
        val obj = if (maximize) p.maximizeInt(objVar) else p.minimizeInt(objVar)
        (solver.minimize(obj, params) as? MinimizeResult.WithSample)?.sample
    })

    /** Complete backtracking search (both phases). Drop into [configs] to compare against LS. */
    private fun backtrack(label: String = "backtrack") = SolverConfig(
        label,
        sat = { p, seed, cancel -> BacktrackSolver(p).samples(BacktrackParams(randomSeed = seed, cancellation = cancel)).firstOrNull() },
        opt = { p, objVar, maximize, seed, cancel ->
            val obj = if (maximize) p.maximizeInt(objVar) else p.minimizeInt(objVar)
            (BacktrackSolver(p).minimize(obj, BacktrackParams(randomSeed = seed, cancellation = cancel)) as? MinimizeResult.WithSample)?.sample
        },
    )

    /** CBLS parameter variants (one-factor-at-a-time around the baseline). */
    private val tuneConfigs: List<SolverConfig> = listOf(
        cbls("baseline") { Cbls(tabu = baseTabu()) },
        cbls("noise0.0") { Cbls(noiseProbability = 0.0, tabu = baseTabu()) },
        cbls("noise0.15") { Cbls(noiseProbability = 0.15, tabu = baseTabu()) },
        cbls("stallSteps3") { Cbls(stallSteps = 3, tabu = baseTabu()) },
        cbls("stallIncr2.0") { Cbls(stallIncrement = 2.0, tabu = baseTabu()) },
        cbls("smooth0.2/0.8") { Cbls(smoothProb = 0.2, smoothFactor = 0.8, tabu = baseTabu()) },
        cbls("smooth0.5/0.5") { Cbls(smoothProb = 0.5, smoothFactor = 0.5, tabu = baseTabu()) },
        cbls("noTabu") { Cbls(tabu = TabuFilter.Disabled) },
    )

    /** Strategy comparison: CBLS vs FocusedLs (klause's two LS families). */
    private val compareConfigs: List<SolverConfig> = listOf(
        cbls("CBLS") { Cbls(tabu = baseTabu()) },
        focused("FOCUSED"),
    )

    /** `-Dklause.solversweep.set=` → `compare` (CBLS vs FocusedLs), `focused` (FocusedLs only,
     *  for discovering which instances it handles), else the CBLS tuning grid. */
    private val configs: List<SolverConfig> by lazy {
        when (System.getProperty("klause.solversweep.set")) {
            "compare" -> compareConfigs
            "focused" -> listOf(focused("FOCUSED"))
            "cbls" -> listOf(cbls("CBLS") { Cbls(tabu = baseTabu()) })
            else -> tuneConfigs
        }
    }

    private enum class Goal { MIN, MAX, SAT }
    private data class Instance(val name: String, val goal: Goal, val program: com.eignex.klause.formats.flatzinc.FlatZincProgram, val objVar: Int)
    /** Unified result: opt fills [objective]; sat fills [solved]/[medianMs]. */
    private data class Cell(val feasible: Boolean, val objective: Long?, val solved: Int, val seeds: Int, val medianMs: Long?)

    @JvmStatic
    fun main(args: Array<String>) {
        val root = System.getProperty("klause.workspace.root")?.let { File(it) } ?: File(".")
        val dir = File(root, System.getProperty("klause.solversweep.dir", "klause-bench/build/ls-sweep"))
        val timeoutSec = System.getProperty("klause.solversweep.timeoutSec", "30").toInt()
        val nSeeds = System.getProperty("klause.solversweep.seeds", "3").toInt()
        val seeds = (1L..nSeeds.toLong()).toList()

        val fznFiles = dir.listFiles { f -> f.isFile && f.extension == "fzn" }?.sortedBy { it.name } ?: emptyList()
        require(fznFiles.isNotEmpty()) { "no .fzn instances in $dir" }
        val instances = fznFiles.mapNotNull { f ->
            val program = runCatching { parseFlatZinc(f.readText()) }.getOrElse {
                println("[solversweep] skip ${f.name}: parse failed (${it.message})"); return@mapNotNull null
            }
            val (goal, objVar) = when (val s = program.solve) {
                is SolveDirective.Satisfy -> Goal.SAT to -1
                is SolveDirective.Minimize -> Goal.MIN to (program.intVarsByName[s.objVar] ?: -1)
                is SolveDirective.Maximize -> Goal.MAX to (program.intVarsByName[s.objVar] ?: -1)
            }
            Instance(f.nameWithoutExtension, goal, program, objVar)
        }
        val optCount = instances.count { it.goal != Goal.SAT }
        val parallelism = System.getProperty("klause.solversweep.parallelism")?.toIntOrNull()
            ?: maxOf(1, Runtime.getRuntime().availableProcessors() - 4)
        println("[solversweep] instances=${instances.size} (${optCount} opt, ${instances.size - optCount} sat) " +
            "configs=${configs.size} seeds=$nSeeds budget=${timeoutSec}s parallelism=$parallelism")

        // Each (instance, config) is an independent in-process solve — fan them out across a
        // bounded pool. Parallelism is kept under the core count so every run still gets a full
        // core, keeping the wall-clock budget fair. The deadline is computed when a job starts
        // (inside bestOpt/runSat), so queued jobs get their full budget.
        val jobs = instances.flatMap { inst ->
            configs.map { cfg ->
                Callable {
                    val cell = if (inst.goal == Goal.SAT) runSat(inst.program.problem, cfg, seeds, timeoutSec)
                               else bestOpt(inst, cfg, seeds, timeoutSec)
                    Triple(inst.name, cfg.label, cell)
                }
            }
        }
        val total = jobs.size
        val done = AtomicInteger(0)
        val lock = Any()
        val pool = Executors.newFixedThreadPool(parallelism)
        val flat = try {
            pool.invokeAll(jobs.map { job ->
                Callable {
                    val r = job.call()
                    val n = done.incrementAndGet()
                    synchronized(lock) { println("[solversweep] %d/%d  %s / %s".format(n, total, r.first, r.second)) }
                    r
                }
            }).map { it.get() }
        } finally {
            pool.shutdown()
        }
        val results: Map<String, Map<String, Cell>> = flat.groupBy { it.first }
            .mapValues { (_, l) -> l.associate { it.second to it.third } }

        // Ordered, deterministic result tables (computed in parallel above).
        for (inst in instances) {
            val row = results.getValue(inst.name)
            println("\n== ${inst.name} [${inst.goal.name.lowercase()}] ==")
            for (cfg in configs) {
                val cell = row.getValue(cfg.label)
                val metric = if (inst.goal == Goal.SAT) "solved ${cell.solved}/${cell.seeds} median=${cell.medianMs ?: "-"}ms"
                             else if (cell.feasible) "obj=${cell.objective}" else "NO feasible"
                println("  %-16s %s".format(cfg.label, metric))
            }
        }

        rankSummary("OVERALL", instances, results)
        rankSummary("OPT", instances.filter { it.goal != Goal.SAT }, results)
        rankSummary("SAT", instances.filter { it.goal == Goal.SAT }, results)
    }

    private fun bestOpt(inst: Instance, cfg: SolverConfig, seeds: List<Long>, timeoutSec: Int): Cell {
        val run = cfg.opt ?: return Cell(false, null, 0, seeds.size, null)
        var best: Cell? = null
        for (seed in seeds) {
            val deadline = System.currentTimeMillis() + timeoutSec * 1000L
            val cancel = Cancellation { System.currentTimeMillis() > deadline }
            val cell = try {
                val s = run(inst.program.problem, inst.objVar, inst.goal == Goal.MAX, seed, cancel)
                if (s != null) Cell(true, s.ints[inst.objVar].toLong(), 0, seeds.size, null) else Cell(false, null, 0, seeds.size, null)
            } catch (t: Throwable) { println("    ! ${inst.name}/$seed threw: ${t::class.simpleName}: ${t.message}"); Cell(false, null, 0, seeds.size, null) }
            best = when {
                best == null || (!best.feasible && cell.feasible) -> cell
                !cell.feasible -> best
                inst.goal == Goal.MIN -> if ((cell.objective ?: Long.MAX_VALUE) < (best.objective ?: Long.MAX_VALUE)) cell else best
                else -> if ((cell.objective ?: Long.MIN_VALUE) > (best.objective ?: Long.MIN_VALUE)) cell else best
            }
        }
        return best!!
    }

    private fun runSat(problem: Problem, cfg: SolverConfig, seeds: List<Long>, timeoutSec: Int): Cell {
        val run = cfg.sat ?: return Cell(false, null, 0, seeds.size, null)
        val times = ArrayList<Long>()
        for (seed in seeds) {
            val deadline = System.currentTimeMillis() + timeoutSec * 1000L
            val cancel = Cancellation { System.currentTimeMillis() > deadline }
            val started = System.currentTimeMillis()
            val s = runCatching { run(problem, seed, cancel) }.getOrNull()
            if (s != null) times.add(System.currentTimeMillis() - started)
        }
        val median = if (times.isEmpty()) null else times.sorted()[times.size / 2]
        return Cell(times.isNotEmpty(), null, times.size, seeds.size, median)
    }

    /** Higher = better, comparable WITHIN an instance (used for ranking, not across instances). */
    private fun score(inst: Instance, c: Cell): Double = when {
        inst.goal == Goal.SAT -> if (c.solved == 0) Double.NEGATIVE_INFINITY
                                 else c.solved.toDouble() * 1e12 - (c.medianMs ?: 0L).toDouble() // solve-rate first, then faster
        !c.feasible -> Double.NEGATIVE_INFINITY
        inst.goal == Goal.MIN -> -(c.objective ?: Long.MAX_VALUE).toDouble()
        else -> (c.objective ?: Long.MIN_VALUE).toDouble()
    }

    private fun rankSummary(title: String, instances: List<Instance>, results: Map<String, Map<String, Cell>>) {
        if (instances.isEmpty()) return
        val labels = configs.map { it.label }
        val sumRank = LinkedHashMap<String, Double>(); val wins = LinkedHashMap<String, Int>()
        labels.forEach { sumRank[it] = 0.0; wins[it] = 0 }
        for (inst in instances) {
            val row = results.getValue(inst.name)
            val scored = labels.map { it to score(inst, row.getValue(it)) }
            val best = scored.maxOf { it.second }
            val distinct = scored.map { it.second }.distinct().sortedDescending()
            for ((label, sc) in scored) {
                sumRank[label] = sumRank.getValue(label) + (distinct.indexOf(sc) + 1)
                if (sc == best && sc != Double.NEGATIVE_INFINITY) wins[label] = wins.getValue(label) + 1
            }
        }
        val n = instances.size.toDouble()
        println("\n[solversweep] $title rank (avg dense-rank lower=better; over ${instances.size} instances)")
        labels.sortedBy { sumRank.getValue(it) / n }.forEach {
            println("  %-16s avgRank=%.2f  wins=%d".format(it, sumRank.getValue(it) / n, wins.getValue(it)))
        }
    }
}
