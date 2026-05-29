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

/**
 * Solver-agnostic in-process sweep over a corpus of pre-compiled `.fzn` instances under a
 * wall-clock budget. The mechanism doesn't care which solver a config wraps — a [SolverConfig]
 * is just a label plus an optional satisfy runner and/or optimize runner, each building and
 * driving its own solver. Built-in helpers cover local search ([lsSat] / [lsOpt]) and complete
 * search ([backtrack]); add others (LogicNG, brute) the same way.
 *
 * Two phase-correct sub-sweeps, dispatched per instance by its solve goal:
 *  - **optimization** → run each [optConfigs] entry, report the true objective + an avg rank.
 *  - **satisfaction** → run each [satConfigs] entry, report solve-rate + median time-to-feasible,
 *    extract cheap features, and score candidate routing rules by regret.
 *
 * Properties:
 *  - `klause.solversweep.dir`        — dir of `.fzn`. Default `klause-bench/build/ls-sweep`.
 *  - `klause.solversweep.timeoutSec` — per-run wall-clock budget. Default 30.
 *  - `klause.solversweep.seeds`      — number of seeds (1..N). Default 3.
 */
object SolverSweepMain {

    private fun baseTabu() = TabuFilter(tenure = 10, aspiration = AspirationCriterion.OrImproving)

    /**
     * A solver to sweep. [sat] returns the first feasible sample (or null) within the deadline;
     * [opt] returns the best feasible sample (or null). Either may be absent if the config is
     * only used in one sub-sweep. Both receive a per-run seed and a deadline [Cancellation].
     */
    class SolverConfig(
        val label: String,
        val sat: ((Problem, Long, Cancellation) -> Sample?)? = null,
        val opt: ((Problem, objVar: Int, maximize: Boolean, Long, Cancellation) -> Sample?)? = null,
    )

    /** Local search with [make] as the satisfy strategy (rebuilt per run — strategies are stateful). */
    private fun lsSat(label: String, make: () -> Strategy) = SolverConfig(label, sat = { p, seed, cancel ->
        LocalSearchSolver(p, strategy = make(), pairSwapBudget = 1024)
            .samples(LocalSearchParams(randomSeed = seed, cancellation = cancel)).firstOrNull()
    })

    /** Local search with [make] as the optimize strategy (CBLS engages the unified minimize path). */
    private fun lsOpt(label: String, make: () -> Strategy) = SolverConfig(label, opt = { p, objVar, maximize, seed, cancel ->
        val solver = LocalSearchSolver(p, optimizeStrategy = make(), pairSwapBudget = 1024)
        val params = LocalSearchParams(randomSeed = seed, cancellation = cancel, costShaping = CostShaping.Linear(lambda = 1.0))
        val obj = if (maximize) p.maximizeInt(objVar) else p.minimizeInt(objVar)
        (solver.minimize(obj, params) as? MinimizeResult.WithSample)?.sample
    })

    /** Complete backtracking search — usable in both sub-sweeps. Demonstrates the abstraction
     *  handling a non-LS solver; drop into either config list to compare against LS. */
    private fun backtrack(label: String = "backtrack") = SolverConfig(
        label,
        sat = { p, seed, cancel -> BacktrackSolver(p).samples(BacktrackParams(randomSeed = seed, cancellation = cancel)).firstOrNull() },
        opt = { p, objVar, maximize, seed, cancel ->
            val obj = if (maximize) p.maximizeInt(objVar) else p.minimizeInt(objVar)
            (BacktrackSolver(p).minimize(obj, BacktrackParams(randomSeed = seed, cancellation = cancel)) as? MinimizeResult.WithSample)?.sample
        },
    )

    /** OPT sub-sweep: CBLS optimize-param variants. (Add `backtrack()` to compare vs B&B.) */
    private val optConfigs: List<SolverConfig> = listOf(
        lsOpt("baseline") { Cbls(tabu = baseTabu()) },
        lsOpt("noise0.0") { Cbls(noiseProbability = 0.0, tabu = baseTabu()) },
        lsOpt("noise0.15") { Cbls(noiseProbability = 0.15, tabu = baseTabu()) },
        lsOpt("stallSteps3") { Cbls(stallSteps = 3, tabu = baseTabu()) },
        lsOpt("stallIncr2.0") { Cbls(stallIncrement = 2.0, tabu = baseTabu()) },
        lsOpt("smooth0.2/0.8") { Cbls(smoothProb = 0.2, smoothFactor = 0.8, tabu = baseTabu()) },
        lsOpt("smooth0.5/0.5") { Cbls(smoothProb = 0.5, smoothFactor = 0.5, tabu = baseTabu()) },
        lsOpt("noTabu") { Cbls(tabu = TabuFilter.Disabled) },
    )

    /** SAT sub-sweep: satisfy-strategy candidates. (Add `backtrack()` to compare vs B&B.) */
    private val satConfigs: List<SolverConfig> = listOf(
        lsSat("FOCUSED") { ProbSat.adaptive(tabu = baseTabu()) },
        lsSat("CBLS") { Cbls(tabu = baseTabu()) },
    )

    /** Candidate routing rules for the satisfy default: features → config label. */
    private val rules: List<Pair<String, (Features) -> String>> = listOf(
        "R0 always-CBLS" to { _ -> "CBLS" },
        "R1 always-FOCUSED" to { _ -> "FOCUSED" },
        "R2 boolOnly->FOCUSED" to { f -> if (f.boolOnly) "FOCUSED" else "CBLS" },
        "R3 boolOnly&noGlobals->FOCUSED" to { f -> if (f.boolOnly && f.noGlobals) "FOCUSED" else "CBLS" },
    )

    private enum class Goal { MIN, MAX, SAT }
    private data class Features(val boolVars: Int, val intVars: Int, val factorKinds: Map<String, Int>) {
        val boolOnly: Boolean get() = intVars == 0
        val noGlobals: Boolean get() = factorKinds.keys.all { it == "Clause" }
    }
    private data class Instance(val name: String, val goal: Goal, val program: com.eignex.klause.formats.flatzinc.FlatZincProgram, val objVar: Int, val feat: Features)
    private data class OptCell(val feasible: Boolean, val objective: Long?)
    private data class SatCell(val solved: Int, val seeds: Int, val medianMs: Long?) {
        val solveRate: Double get() = solved.toDouble() / seeds
    }

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
            val p = program.problem
            val feat = Features(p.numBoolVars, p.numIntVars, p.factors.groupingBy { it::class.simpleName ?: "?" }.eachCount())
            val (goal, objVar) = when (val s = program.solve) {
                is SolveDirective.Satisfy -> Goal.SAT to -1
                is SolveDirective.Minimize -> Goal.MIN to (program.intVarsByName[s.objVar] ?: -1)
                is SolveDirective.Maximize -> Goal.MAX to (program.intVarsByName[s.objVar] ?: -1)
            }
            Instance(f.nameWithoutExtension, goal, program, objVar, feat)
        }
        val optInst = instances.filter { it.goal != Goal.SAT }
        val satInst = instances.filter { it.goal == Goal.SAT }
        println("[solversweep] instances=${instances.size} (${optInst.size} opt, ${satInst.size} sat) seeds=$nSeeds budget=${timeoutSec}s")

        val optResults = LinkedHashMap<String, Map<String, OptCell>>()
        for (inst in optInst) {
            val row = LinkedHashMap<String, OptCell>()
            println("\n== OPT ${inst.name} [${inst.goal.name.lowercase()}] ==")
            for (cfg in optConfigs) {
                val cell = bestOpt(inst, cfg, seeds, timeoutSec)
                row[cfg.label] = cell
                println("  %-16s %s".format(cfg.label, if (cell.feasible) "obj=${cell.objective}" else "NO feasible"))
            }
            optResults[inst.name] = row
        }

        val satResults = LinkedHashMap<String, Pair<Features, Map<String, SatCell>>>()
        for (inst in satInst) {
            val row = LinkedHashMap<String, SatCell>()
            println("\n== SAT ${inst.name} ==  boolOnly=${inst.feat.boolOnly} noGlobals=${inst.feat.noGlobals} kinds=${inst.feat.factorKinds}")
            for (cfg in satConfigs) {
                val cell = runSat(inst.program.problem, cfg, seeds, timeoutSec)
                row[cfg.label] = cell
                println("  %-10s solved %d/%d  median=%sms".format(cfg.label, cell.solved, cell.seeds, cell.medianMs ?: "-"))
            }
            satResults[inst.name] = inst.feat to row
        }

        if (optInst.isNotEmpty()) optSummary(optInst, optResults)
        if (satInst.isNotEmpty()) satRegret(satResults)
    }

    private fun bestOpt(inst: Instance, cfg: SolverConfig, seeds: List<Long>, timeoutSec: Int): OptCell {
        val run = cfg.opt ?: return OptCell(false, null)
        var best: OptCell? = null
        for (seed in seeds) {
            val cell = runOnce(inst, run, seed, timeoutSec)
            best = when {
                best == null || (!best.feasible && cell.feasible) -> cell
                !cell.feasible -> best
                inst.goal == Goal.MIN -> if ((cell.objective ?: Long.MAX_VALUE) < (best.objective ?: Long.MAX_VALUE)) cell else best
                else -> if ((cell.objective ?: Long.MIN_VALUE) > (best.objective ?: Long.MIN_VALUE)) cell else best
            }
        }
        return best!!
    }

    private fun runOnce(inst: Instance, run: (Problem, Int, Boolean, Long, Cancellation) -> Sample?, seed: Long, timeoutSec: Int): OptCell {
        val deadline = System.currentTimeMillis() + timeoutSec * 1000L
        val cancel = Cancellation { System.currentTimeMillis() > deadline }
        return try {
            val sample = run(inst.program.problem, inst.objVar, inst.goal == Goal.MAX, seed, cancel)
            if (sample != null) OptCell(true, sample.ints[inst.objVar].toLong()) else OptCell(false, null)
        } catch (t: Throwable) {
            println("    ! ${inst.name}/$seed threw: ${t::class.simpleName}: ${t.message}"); OptCell(false, null)
        }
    }

    private fun runSat(problem: Problem, cfg: SolverConfig, seeds: List<Long>, timeoutSec: Int): SatCell {
        val run = cfg.sat ?: return SatCell(0, seeds.size, null)
        val times = ArrayList<Long>()
        for (seed in seeds) {
            val deadline = System.currentTimeMillis() + timeoutSec * 1000L
            val cancel = Cancellation { System.currentTimeMillis() > deadline }
            val started = System.currentTimeMillis()
            val s = runCatching { run(problem, seed, cancel) }.getOrNull()
            if (s != null) times.add(System.currentTimeMillis() - started)
        }
        return SatCell(times.size, seeds.size, if (times.isEmpty()) null else times.sorted()[times.size / 2])
    }

    private fun optSummary(instances: List<Instance>, results: Map<String, Map<String, OptCell>>) {
        val labels = optConfigs.map { it.label }
        val avgRank = LinkedHashMap<String, Double>(); val wins = LinkedHashMap<String, Int>()
        labels.forEach { avgRank[it] = 0.0; wins[it] = 0 }
        for (inst in instances) {
            val row = results.getValue(inst.name)
            fun score(c: OptCell) = when {
                !c.feasible -> Double.NEGATIVE_INFINITY
                inst.goal == Goal.MIN -> -(c.objective ?: Long.MAX_VALUE).toDouble()
                else -> (c.objective ?: Long.MIN_VALUE).toDouble()
            }
            val scored = labels.map { it to score(row.getValue(it)) }
            val best = scored.maxOf { it.second }
            val distinct = scored.map { it.second }.distinct().sortedDescending()
            for ((label, sc) in scored) {
                avgRank[label] = avgRank.getValue(label) + (distinct.indexOf(sc) + 1)
                if (sc == best && sc != Double.NEGATIVE_INFINITY) wins[label] = wins.getValue(label) + 1
            }
        }
        val n = instances.size.toDouble()
        println("\n[solversweep] OPT SUMMARY (objective; avg rank lower=better; over ${instances.size} instances)")
        labels.sortedBy { avgRank.getValue(it) / n }.forEach { println("  %-16s avgRank=%.2f  wins=%d".format(it, avgRank.getValue(it) / n, wins.getValue(it))) }
    }

    private fun satRegret(results: Map<String, Pair<Features, Map<String, SatCell>>>) {
        println("\n[solversweep] SAT ROUTING RULES (regret = Σ solve-rate left vs per-instance best; lower=better)")
        for ((label, rule) in rules) {
            var regret = 0.0; var worse = 0
            for ((_, fr) in results) {
                val (feat, row) = fr
                val best = row.values.maxOf { it.solveRate }
                val picked = row[rule(feat)]?.solveRate ?: 0.0
                val r = best - picked; regret += r; if (r > 1e-9) worse++
            }
            println("  %-32s regret=%.2f  picks-worse-on=%d/%d".format(label, regret, worse, results.size))
        }
    }
}
