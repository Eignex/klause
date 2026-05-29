package com.eignex.klause.bench.parity

import com.eignex.klause.formats.flatzinc.SolveDirective
import com.eignex.klause.formats.flatzinc.parseFlatZinc
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.MinimizeResult
import com.eignex.klause.solver.localsearch.CostShaping
import com.eignex.klause.solver.localsearch.LocalSearchParams
import com.eignex.klause.solver.localsearch.LocalSearchSolver
import com.eignex.klause.solver.localsearch.strategy.AspirationCriterion
import com.eignex.klause.solver.localsearch.strategy.Cbls
import com.eignex.klause.solver.localsearch.strategy.TabuFilter
import com.eignex.klause.solver.maximizeInt
import com.eignex.klause.solver.minimizeInt
import java.io.File

/**
 * CBLS optimize-parameter sweep. Runs the minimize path in-process over a fixed set of
 * pre-compiled *optimization* `.fzn` instances under a wall-clock budget, varying CBLS
 * parameters (noise, stall schedule, smoothing, tabu) installed as `optimizeStrategy` — when
 * it is CBLS the minimize loop is unified, so the strategy drives the whole solve. Reports the
 * true objective each config reaches and an average rank, to tune the shipped CBLS defaults.
 *
 * (Satisfaction instances are skipped — the satisfy-strategy question lives in [SatSweepMain].)
 *
 * Properties:
 *  - `klause.cblssweep.dir`        — dir of `.fzn`. Default `klause-bench/build/cbls-sweep`.
 *  - `klause.cblssweep.timeoutSec` — per-run wall-clock budget. Default 60.
 *  - `klause.cblssweep.seeds`      — comma-separated seeds (best kept). Default `1`.
 */
object CblsSweepMain {

    private fun baseTabu() = TabuFilter(tenure = 10, aspiration = AspirationCriterion.OrImproving)

    /** CBLS `optimizeStrategy` variants. Baseline mirrors the shipped CLI CBLS. */
    private val configs: List<Pair<String, Cbls>> = listOf(
        "baseline" to Cbls(tabu = baseTabu()),
        "noise0.0" to Cbls(noiseProbability = 0.0, tabu = baseTabu()),
        "noise0.15" to Cbls(noiseProbability = 0.15, tabu = baseTabu()),
        "stallSteps3" to Cbls(stallSteps = 3, tabu = baseTabu()),
        "stallIncr2.0" to Cbls(stallIncrement = 2.0, tabu = baseTabu()),
        "smooth0.2/0.8" to Cbls(smoothProb = 0.2, smoothFactor = 0.8, tabu = baseTabu()),
        "smooth0.5/0.5" to Cbls(smoothProb = 0.5, smoothFactor = 0.5, tabu = baseTabu()),
        "noTabu" to Cbls(tabu = TabuFilter.Disabled),
    )

    private enum class Goal { MIN, MAX }
    private data class Instance(
        val name: String, val goal: Goal,
        val program: com.eignex.klause.formats.flatzinc.FlatZincProgram, val objVar: Int,
    )
    private data class Cell(val feasible: Boolean, val objective: Long?)

    @JvmStatic
    fun main(args: Array<String>) {
        val root = System.getProperty("klause.workspace.root")?.let { File(it) } ?: File(".")
        val dir = File(root, System.getProperty("klause.cblssweep.dir", "klause-bench/build/cbls-sweep"))
        val timeoutSec = System.getProperty("klause.cblssweep.timeoutSec", "60").toInt()
        val seeds = System.getProperty("klause.cblssweep.seeds", "1").split(",").map { it.trim().toLong() }

        val fznFiles = dir.listFiles { f -> f.isFile && f.extension == "fzn" }?.sortedBy { it.name } ?: emptyList()
        require(fznFiles.isNotEmpty()) { "no .fzn instances in $dir" }

        val instances = fznFiles.mapNotNull { f ->
            val program = runCatching { parseFlatZinc(f.readText()) }.getOrElse {
                println("[cblssweep] skip ${f.name}: parse failed (${it.message})"); return@mapNotNull null
            }
            when (val s = program.solve) {
                is SolveDirective.Minimize -> Instance(f.nameWithoutExtension, Goal.MIN, program, program.intVarsByName[s.objVar] ?: -1)
                is SolveDirective.Maximize -> Instance(f.nameWithoutExtension, Goal.MAX, program, program.intVarsByName[s.objVar] ?: -1)
                is SolveDirective.Satisfy -> { println("[cblssweep] skip ${f.name}: satisfy (see SatSweepMain)"); null }
            }
        }
        require(instances.isNotEmpty()) { "no optimization instances in $dir" }
        println("[cblssweep] instances=${instances.size} seeds=$seeds budget=${timeoutSec}s")

        val results = LinkedHashMap<String, LinkedHashMap<String, Cell>>()
        for (inst in instances) {
            val rowMap = LinkedHashMap<String, Cell>()
            results[inst.name] = rowMap
            println("\n== ${inst.name} [${inst.goal.name.lowercase()}] ==")
            for ((label, cbls) in configs) {
                val cell = bestOverSeeds(inst, cbls, seeds, timeoutSec)
                rowMap[label] = cell
                println("  %-16s %s".format(label, if (cell.feasible) "obj=${cell.objective}" else "NO feasible"))
            }
        }
        printSummary(instances, configs.map { it.first }, results)
    }

    private fun bestOverSeeds(inst: Instance, cbls: Cbls, seeds: List<Long>, timeoutSec: Int): Cell {
        var best: Cell? = null
        for (seed in seeds) {
            val c = runOne(inst, cbls, seed, timeoutSec)
            best = when {
                best == null -> c
                !c.feasible -> best
                !best.feasible -> c
                inst.goal == Goal.MIN -> if ((c.objective ?: Long.MAX_VALUE) < (best.objective ?: Long.MAX_VALUE)) c else best
                else -> if ((c.objective ?: Long.MIN_VALUE) > (best.objective ?: Long.MIN_VALUE)) c else best
            }
        }
        return best!!
    }

    private fun runOne(inst: Instance, cbls: Cbls, seed: Long, timeoutSec: Int): Cell {
        val deadline = System.currentTimeMillis() + timeoutSec * 1000L
        val cancel = Cancellation { System.currentTimeMillis() > deadline }
        return try {
            val solver = LocalSearchSolver(inst.program.problem, optimizeStrategy = cbls, pairSwapBudget = 1024)
            val params = LocalSearchParams(randomSeed = seed, cancellation = cancel, costShaping = CostShaping.Linear(lambda = 1.0))
            val objective = if (inst.goal == Goal.MAX) inst.program.problem.maximizeInt(inst.objVar)
                            else inst.program.problem.minimizeInt(inst.objVar)
            val sample = (solver.minimize(objective, params) as? MinimizeResult.WithSample)?.sample
            if (sample != null) Cell(true, sample.ints[inst.objVar].toLong()) else Cell(false, null)
        } catch (t: Throwable) {
            println("    ! ${inst.name}/$seed threw: ${t::class.simpleName}: ${t.message}")
            Cell(false, null)
        }
    }

    private fun printSummary(
        instances: List<Instance>, labels: List<String>, results: Map<String, Map<String, Cell>>,
    ) {
        val avgRank = LinkedHashMap<String, Double>()
        val wins = LinkedHashMap<String, Int>()
        labels.forEach { avgRank[it] = 0.0; wins[it] = 0 }
        for (inst in instances) {
            val row = results.getValue(inst.name)
            fun score(c: Cell): Double = when {
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
        println("\n[cblssweep] SUMMARY (objective; avg rank lower=better; over ${instances.size} instances)")
        labels.sortedBy { avgRank.getValue(it) / n }.forEach { label ->
            println("  %-16s avgRank=%.2f  wins=%d".format(label, avgRank.getValue(label) / n, wins.getValue(label)))
        }
    }
}
