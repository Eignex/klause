package com.eignex.klause.backtrack

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.formats.xcsp3.Xcsp3
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationPreparation
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Cancellation
import java.io.File
import java.lang.management.ManagementFactory
import java.security.MessageDigest

fun main(args: Array<String>) {
    val input = args[0]
    val count = args.getOrNull(1)?.toInt() ?: 8192
    val rounds = args.getOrNull(2)?.toInt() ?: 4
    val problem: Problem
    val objective: LinearObjective
    val assumptions: Assumptions
    if (input == "synthetic") {
        require(count >= 2)
        problem = Problem(
            0, count, Array(count) { IntDomain(0, 10) },
            Array(count) { Linear(intArrayOf(1), intArrayOf(it), LinearOp.LE, 9) },
        )
        objective = LinearObjective(intCoefficients = LongArray(count) { 1L })
        assumptions = Assumptions(
            intArrayOf(), booleanArrayOf(), IntArray(minOf(count, 512)) { it }, LongArray(minOf(count, 512)) { 1L },
        )
    } else {
        val source = Xcsp3.parse(File(input).readText())
        problem = source.problem
        val spec = checkNotNull(source.objective)
        objective = LinearObjective(spec.boolWeights, spec.intCoefficients, spec.constant, spec.realCoefficients)
        assumptions = Assumptions.None
    }
    val baked = problem.bake()
    val allocations = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
    check(allocations.isThreadAllocatedMemorySupported)
    allocations.isThreadAllocatedMemoryEnabled = true
    val thread = Thread.currentThread().threadId()
    fun bytes() = allocations.getThreadAllocatedBytes(thread)
    println("MODEL input=$input ints=${problem.numIntVars} bools=${problem.numBoolVars} factors=${problem.numFactors}")
    for (round in 0..rounds) {
        val times = mutableListOf<Long>()
        var allocated = 0L
        val preparation = PropagationPreparation(baked, Cancellation.Never, 0, true)
        var ready: PropagationSession? = null
        while (ready == null) {
            val before = bytes()
            val started = System.nanoTime()
            ready = preparation.advance()
            times.add(System.nanoTime() - started)
            allocated += bytes() - before
        }
        val session = checkNotNull(ready)
        val digest = MessageDigest.getInstance("SHA-256")
        fun hash(text: String) {
            digest.update(text.toByteArray())
            digest.update(0.toByte())
        }
        for (v in 0 until problem.numIntVars) hash(session.intDomain(v).toString())
        for (v in 0 until problem.numBoolVars) hash(session.boolValue(v).toString())
        val rootHash = digest.digest().joinToString("") { "%02x".format(it) }
        println("PROPAGATION round=$round batches=${times.size} ns=${times.sum()} maxNs=${times.max()} bytes=$allocated work=${preparation.work} hash=$rootHash")
        times.forEachIndexed { batch, ns -> println("BATCH round=$round batch=$batch ns=$ns") }
        for (lp in listOf(LpConfig.OFF, LpConfig.AGGRESSIVE)) for (slice in listOf(Long.MAX_VALUE, 1L)) {
            val params = BacktrackParams(
                nativeSat = false, randomSeed = 3L, nodeBudget = NodeBudget(0L), lpConfig = lp, assumptions = assumptions,
            )
            val before = bytes()
            val started = System.nanoTime()
            val handle = BacktrackSolver(baked).resumable(objective, params)
            val openNs = System.nanoTime() - started
            val segments = mutableListOf<Long>()
            handle.use {
                while (it.preparationPending) {
                    val segmentStart = System.nanoTime()
                    it.runSlice(Cancellation.Never, slice, -1L) { error("zero decision allowance published an incumbent") }
                    segments.add(System.nanoTime() - segmentStart)
                }
                val stats = it.stats
                check(stats.search.nodes.sum == 0.0)
                println("INSTALLATION round=$round lp=$lp slice=$slice segments=${segments.size} openNs=$openNs ns=${segments.sum()} maxNs=${segments.maxOrNull() ?: 0L} bytes=${bytes() - before} work=${it.work} propagationWork=${stats.search.propagationWork.sum.toLong()} nodes=${stats.search.nodes.sum} rootHash=$rootHash")
            }
            segments.forEachIndexed { segment, ns -> println("SEGMENT round=$round lp=$lp slice=$slice segment=$segment ns=$ns") }
        }
    }
}
