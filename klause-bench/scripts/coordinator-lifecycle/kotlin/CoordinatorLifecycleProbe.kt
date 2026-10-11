package com.eignex.klause.localsearch

import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.formats.dimacs.Dimacs
import com.eignex.klause.formats.dimacs.toProblem
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.InstructionSlicedSearch
import com.eignex.klause.solver.InstructionSlicedSolve
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.util.Cancellation
import java.io.File
import java.lang.management.ManagementFactory
import java.security.MessageDigest

fun main(args: Array<String>) {
    val source = Dimacs.parse(File(args[0]).readText())
    val model = LocalSearchModel.of(source.toProblem().bake())
    val seed = args.getOrNull(1)?.toLong() ?: 3L
    val instructions = args.getOrNull(2)?.toLong() ?: 107L
    val repetitions = args.getOrNull(3)?.toInt() ?: 32
    require(instructions > 0L && repetitions > 0)
    val objective = LinearObjective(boolWeights = LongArray(source.numBoolVars) { 1L })
    val allocations = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
    check(allocations.isThreadAllocatedMemorySupported)
    allocations.isThreadAllocatedMemoryEnabled = true
    val thread = Thread.currentThread().threadId()
    for (round in 0..4) for (optimizing in listOf(false, true)) for (slice in listOf(0L, 7L)) {
        val digest = MessageDigest.getInstance("SHA-256")
        var moves = 0L
        var charged = 0L
        var witnesses = 0
        fun record(text: String) {
            digest.update(text.toByteArray())
            digest.update(0.toByte())
        }
        fun witness(sample: Sample) {
            check(!source.triviallyUnsat)
            check(source.clauses.all { clause ->
                clause.any { literal -> sample.boolValue(literal ushr 1) == (literal and 1 == 0) }
            }) { "witness violates a source clause" }
            witnesses++
            repeat(sample.numBoolVars) { digest.update(if (sample.boolValue(it)) 1.toByte() else 0.toByte()) }
        }
        fun incumbent(result: MinimizeResult) {
            record(result::class.simpleName.orEmpty())
            if (result is MinimizeResult.WithSample) {
                witness(result.sample)
                val sourceObjective = (0 until source.numBoolVars).count { result.sample.boolValue(it) }.toDouble()
                check(result.objective == sourceObjective) { "objective disagrees with the source assignment" }
                record(result.objective.toString())
            }
        }
        fun counters(stats: SolveStats, budget: NodeBudget) {
            moves += stats.ls.moves.sum.toLong()
            charged += budget.spent
            record("${stats.ls.moves.sum}/${stats.ls.stalls.sum}/${stats.ls.incumbentViolation}/${budget.spent}")
        }
        val startBytes = allocations.getThreadAllocatedBytes(thread)
        val start = System.nanoTime()
        repeat(repetitions) {
            val engine = LocalSearchEngine(model, greedyRepairOnRestart = false)
            val budget = NodeBudget(Long.MAX_VALUE)
            val params = LocalSearchParams(maxInstructions = instructions, randomSeed = seed, nodeBudget = budget)
            val warm = WarmState()
            if (optimizing) {
                val result = if (slice == 0L) {
                    var terminal: MinimizeResult? = null
                    engine.improvements(objective, params, warm).forEach {
                        incumbent(it)
                        terminal = it
                    }
                    checkNotNull(terminal)
                } else {
                    (engine.resumable(objective, params, warm) as InstructionSlicedSearch).use { handle ->
                        var terminal: MinimizeResult? = null
                        while (terminal == null) {
                            terminal = handle.runInstructionSlice(Cancellation.Never, Long.MAX_VALUE, slice, ::incumbent)
                        }
                        incumbent(checkNotNull(terminal))
                        checkNotNull(terminal)
                    }
                }
                counters(result.stats, budget)
            } else {
                val result = if (slice == 0L) engine.solve(params, warm) else {
                    (engine.resumableSolve(params, warm) as InstructionSlicedSolve).use { handle ->
                        var terminal: SolveResult? = null
                        while (terminal == null) {
                            terminal = handle.runInstructionSlice(Cancellation.Never, Long.MAX_VALUE, slice)
                        }
                        checkNotNull(terminal)
                    }
                }
                record(result::class.simpleName.orEmpty())
                if (result is SolveResult.Sat) witness(result.assignment)
                counters(result.stats, budget)
            }
            warm.factorWeights?.forEach { record(it.toBits().toString()) }
            warm.activityTouches().forEach { record(it.toString()) }
            record(warm.bestCostSeen.toString())
        }
        val nanos = System.nanoTime() - start
        val bytes = allocations.getThreadAllocatedBytes(thread) - startBytes
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        println("LIFECYCLE_PROBE round=$round optimizing=$optimizing slice=$slice runs=$repetitions moves=$moves charged=$charged witnesses=$witnesses ns=$nanos bytes=$bytes hash=$hash")
    }
}
