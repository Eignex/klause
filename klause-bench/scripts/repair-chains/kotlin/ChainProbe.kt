package com.eignex.klause.localsearch

import com.eignex.klause.formats.dimacs.Dimacs
import com.eignex.klause.formats.dimacs.toProblem
import com.eignex.klause.localsearch.strategy.Cbls
import com.eignex.klause.propagation.bake
import java.io.File
import java.lang.management.ManagementFactory
import java.security.MessageDigest
import kotlin.random.Random

fun main(args: Array<String>) {
    val seed = args.getOrNull(1)?.toLong() ?: 3L
    val problem = Dimacs.parse(File(args[0]).readText()).toProblem()
    val baked = problem.bake()
    val projection = LocalSearchProblem(problem)
    var repairCalls = 0L
    var factorProbes = 0L
    var factorApplies = 0L
    @Suppress("UNCHECKED_CAST")
    val factors = projection.invariants as Array<Invariant>
    for (i in factors.indices) {
        val original = factors[i]
        factors[i] = object : Invariant by original {
            override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
                repairCalls++
                original.proposeRepairMoves(state, factorId, sink)
            }
            override fun deltaIfBoolFlipped(state: LocalSearchState, factorId: Int, boolVar: Int): Int {
                factorProbes++
                return original.deltaIfBoolFlipped(state, factorId, boolVar)
            }
            override fun applyBoolFlip(state: LocalSearchState, factorId: Int, boolVar: Int): Int {
                factorApplies++
                return original.applyBoolFlip(state, factorId, boolVar)
            }
        }
    }
    val allocations = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
    for (round in 0..4) {
        val state = LocalSearchState(LocalSearchModel.of(baked), Random(seed), projection = projection)
        state.restart()
        val strategy = Cbls()
        repeat(500) { strategy.pickMove(state)?.let { state.apply(it) } }
        check(!state.violated.isEmpty()) { "the warmup solved this instance" }
        val before = BooleanArray(problem.numBoolVars) { state.assignment.boolValue(it) }
        val degrees = state.factorDegree.copyOf()
        val touches = state.tabu.touchCount.copyOf()
        val touched = state.tabu.lastTouched.copyOf()
        val conf = state.boolConfChange.copyOf()
        val breaks = state.boolBreakCount.copyOf()
        val makes = state.boolMakeCount.copyOf()
        val step = state.step
        val cost = state.cost
        val best = state.bestCostSeen
        val sink = MoveSink()
        val digest = MessageDigest.getInstance("SHA-256")
        repairCalls = 0
        factorProbes = 0
        factorApplies = 0
        val startBytes = allocations.getThreadAllocatedBytes(Thread.currentThread().threadId())
        val start = System.nanoTime()
        var emitted = 0
        repeat(1000) {
            sink.clear()
            emitted += state.proposeRepairChains(state.violated.random(state.rng), 16, 4, sink)
            for (move in sink.list) digest.update(move.toString().toByteArray())
        }
        val ns = System.nanoTime() - start
        val bytes = allocations.getThreadAllocatedBytes(Thread.currentThread().threadId()) - startBytes
        check(before.contentEquals(BooleanArray(problem.numBoolVars) { state.assignment.boolValue(it) }))
        check(degrees.contentEquals(state.factorDegree))
        check(touches.contentEquals(state.tabu.touchCount))
        check(touched.contentEquals(state.tabu.lastTouched))
        check(conf.contentEquals(state.boolConfChange))
        check(breaks.contentEquals(state.boolBreakCount))
        check(makes.contentEquals(state.boolMakeCount))
        check(step == state.step && cost == state.cost && best == state.bestCostSeen)
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        println("CHAIN_PROBE round=$round calls=1000 emitted=$emitted repairCalls=$repairCalls factorProbes=$factorProbes factorApplies=$factorApplies ns=$ns bytes=$bytes hash=$hash rng=${state.rng.nextLong()}")
    }
}
